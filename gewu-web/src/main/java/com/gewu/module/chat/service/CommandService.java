package com.gewu.module.chat.service;

import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.module.chat.entity.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * T3.1 斜杠命令框架（v1.0.1 B4）：/clear /reset /compact
 *
 * <p>交互形态（老板拍板口径）：输入框输入命令回车后系统执行，
 * 执行过程与结果以 SSE 流式输出到聊天区。前端输入「/」弹补全提示。</p>
 */
@Slf4j
@Service
public class CommandService {

    public record CommandSpec(String name, String desc, String usage) {}

    private final Map<String, CommandExecutor> executors;
    private final ConversationService conversationService;

    public CommandService(ConversationService conversationService,
                          ChatSessionService sessionService,
                          ChatMessageService messageService,
                          ContextCompactService contextCompactService) {
        this.conversationService = conversationService;
        this.executors = List.of(
                new ClearCommand(conversationService, sessionService, messageService),
                new ResetCommand(conversationService),
                new CompactCommand(conversationService, sessionService, contextCompactService, messageService)
        ).stream().collect(Collectors.toMap(c -> c.name(), Function.identity()));
    }

    /** 命令清单（前端 / 补全提示用） */
    public List<CommandSpec> listCommands() {
        return executors.values().stream()
                .map(c -> new CommandSpec(c.name(), c.desc(), c.usage()))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /** 是否是可识别命令 */
    public boolean isCommand(String text) {
        return text != null && text.startsWith("/") && executors.containsKey(text.trim().split("\\s+")[0].substring(1).toLowerCase());
    }

    /**
     * 流式执行命令：delta 事件逐步推送执行过程，done 事件收尾
     *
     * @return SSE 事件流（JSON 串，形态对齐 ChatStreamResponseDTO：type=chunk/done/error）
     */
    public Flux<String> execute(String sessionId, String input, Long userId) {
        String trimmed = input.trim();
        String name = trimmed.split("\\s+")[0].substring(1).toLowerCase();
        CommandExecutor executor = executors.get(name);
        if (executor == null) {
            return Flux.just(err(sessionId, "未知命令：" + name + "（可用：" +
                    executors.keySet().stream().sorted().map(k -> "/" + k).collect(Collectors.joining(" ")) + "）"));
        }
        return executor.execute(sessionId, trimmed, userId);
    }

    // ==================== 事件 JSON 工具 ====================

    static String chunk(String sessionId, String text) {
        return "{\"type\":\"chunk\",\"sessionId\":\"" + esc(sessionId) + "\",\"chunk\":\"" + esc(text) + "\"}";
    }

    static String done(String sessionId, String reply) {
        return "{\"type\":\"done\",\"sessionId\":\"" + esc(sessionId) + "\",\"reply\":\"" + esc(reply) + "\"}";
    }

    static String err(String sessionId, String error) {
        return "{\"type\":\"error\",\"sessionId\":\"" + esc(sessionId) + "\",\"error\":\"" + esc(error) + "\"}";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    // ==================== 命令执行器 SPI ====================

    interface CommandExecutor {
        String name();

        String desc();

        String usage();

        Flux<String> execute(String sessionId, String input, Long userId);
    }

    // ==================== /clear 清当前会话历史 ====================

    @Slf4j
    static class ClearCommand implements CommandExecutor {

        private final ConversationService conversationService;
        private final ChatSessionService sessionService;
        private final ChatMessageService messageService;

        ClearCommand(ConversationService cs, ChatSessionService ss, ChatMessageService ms) {
            this.conversationService = cs;
            this.sessionService = ss;
            this.messageService = ms;
        }

        @Override public String name() { return "clear"; }
        @Override public String desc() { return "清空当前会话历史"; }
        @Override public String usage() { return "/clear"; }

        @Override
        public Flux<String> execute(String sessionId, String input, Long userId) {
            return Flux.create(sink -> {
                try {
                    sink.next(chunk(sessionId, "🧹 正在清理会话历史…\n"));
                    ChatSession session = sessionService.getSession(sessionId);
                    if (session == null) {
                        sink.next(err(sessionId, "会话不存在：" + sessionId));
                        sink.complete();
                        return;
                    }
                    int before = session.getMessageCount() == null ? 0 : session.getMessageCount();
                    // 复用 clearSession：删消息 + 重置统计；同时清压缩产物（历史没了，摘要也应归零）
                    conversationService.clearSession(sessionId);
                    // T3：清理标记与摘要（clearSession 本体不动这两列，命令层补齐语义）
                    messageService.markAllCompressed(sessionId);
                    sessionService.clearContextSummary(sessionId);

                    sink.next(chunk(sessionId, "已清理 " + before + " 条消息记录（含压缩摘要）。\n"));
                    sink.next(done(sessionId, "🧹 会话已清空。之前的历史已删除，我们可以重新开始。"));
                    sink.complete();
                } catch (Exception e) {
                    log.error("/clear 执行失败", e);
                    sink.next(err(sessionId, "清理失败：" + e.getMessage()));
                    sink.complete();
                }
            });
        }
    }

    // ==================== /reset 提示后新建会话并切换 ====================

    @Slf4j
    static class ResetCommand implements CommandExecutor {

        private final ConversationService conversationService;

        ResetCommand(ConversationService cs) {
            this.conversationService = cs;
        }

        @Override public String name() { return "reset"; }
        @Override public String desc() { return "新建会话并切换"; }
        @Override public String usage() { return "/reset"; }

        @Override
        public Flux<String> execute(String sessionId, String input, Long userId) {
            return Flux.create(sink -> {
                try {
                    sink.next(chunk(sessionId, "🔄 正在创建新会话…\n"));
                    // 带 userId：会话归属当前用户（否则普通用户 /reset 会造出孤儿会话）
                    String newId = conversationService.createSession(userId);
                    sink.next(chunk(sessionId, "新会话已创建：" + newId.substring(0, 8) + "…\n"));
                    // done 事件带特殊前缀指令，前端据此切换会话
                    sink.next("{\"type\":\"done\",\"sessionId\":\"" + esc(sessionId)
                            + "\",\"reply\":\"🔄 已切换到新会话，开始新的对话吧。\",\"newSessionId\":\"" + esc(newId) + "\"}");
                    sink.complete();
                } catch (Exception e) {
                    log.error("/reset 执行失败", e);
                    sink.next(err(sessionId, "新建会话失败：" + e.getMessage()));
                    sink.complete();
                }
            });
        }
    }

    // ==================== /compact 流式压缩 ====================

    @Slf4j
    static class CompactCommand implements CommandExecutor {

        private final ConversationService conversationService;
        private final ChatSessionService sessionService;
        private final ContextCompactService contextCompactService;
        private final ChatMessageService messageService;

        CompactCommand(ConversationService cs, ChatSessionService ss, ContextCompactService ccs,
                       ChatMessageService ms) {
            this.conversationService = cs;
            this.sessionService = ss;
            this.contextCompactService = ccs;
            this.messageService = ms;
        }

        @Override public String name() { return "compact"; }
        @Override public String desc() { return "压缩当前会话上下文"; }
        @Override public String usage() { return "/compact [保留条数]"; }

        @Override
        public Flux<String> execute(String sessionId, String input, Long userId) {
            return Flux.create(sink -> {
                try {
                    ChatSession session = sessionService.getSession(sessionId);
                    if (session == null) {
                        sink.next(err(sessionId, "会话不存在：" + sessionId));
                        sink.complete();
                        return;
                    }
                    int uncompressed = countUncompressed(sessionId);
                    if (uncompressed <= 4) {
                        sink.next(done(sessionId, "📦 当前会话只有 " + uncompressed + " 条有效消息，无需压缩。"));
                        sink.complete();
                        return;
                    }

                    int keepRecent = 4;
                    String[] parts = input.split("\\s+");
                    if (parts.length > 1) {
                        try { keepRecent = Math.max(2, Integer.parseInt(parts[1])); } catch (NumberFormatException ignore) { }
                    }

                    sink.next(chunk(sessionId, "📦 发现 " + uncompressed + " 条未压缩消息，开始生成摘要…\n"));
                    sink.next(chunk(sessionId, "（调用当前激活模型生成滚动摘要，保留最近 " + keepRecent + " 条原文）\n\n"));

                    String summary = contextCompactService.compact(sessionId, userId, keepRecent);
                    int compressedCount = uncompressed - keepRecent;
                    ChatSession after = sessionService.getSession(sessionId);
                    int summaryTokens = after == null || after.getContextSummary() == null ? 0
                            : after.getContextSummary().length() / 4;

                    sink.next(chunk(sessionId, "✅ 压缩完成：合并 " + compressedCount + " 条消息 → 摘要 " + summaryTokens + " tokens\n"));
                    sink.next(chunk(sessionId, "--- 摘要预览 ---\n" + preview(summary) + "\n"));
                    sink.next(done(sessionId, "📦 压缩完成（" + compressedCount + " 条 → " + summaryTokens
                            + " tokens 摘要）。早期上下文已收进摘要，后续对话自动引用。"));
                    sink.complete();
                } catch (Exception e) {
                    log.error("/compact 执行失败", e);
                    sink.next(err(sessionId, "压缩失败：" + e.getMessage()));
                    sink.complete();
                }
            });
        }

        private int countUncompressed(String sessionId) {
            List<com.gewu.module.chat.entity.ChatMessage> all =
                    messageService.getMessagesBySessionId(sessionId);
            return (int) all.stream()
                    .filter(m -> m.getCompressed() == null || m.getCompressed() != 1)
                    .count();
        }

        private String preview(String s) {
            if (s == null || s.isBlank()) return "（空）";
            String flat = s.length() > 300 ? s.substring(0, 300) + "…" : s;
            return flat;
        }
    }
}
