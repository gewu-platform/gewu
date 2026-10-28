package com.gewu.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.module.chat.entity.ChatMessage;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.chat.mapper.ChatMessageMapper;
import com.gewu.module.chat.mapper.ChatSessionMapper;
import com.gewu.module.user.service.UserSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 上下文压缩服务（v1.0.1 B3）
 *
 * <p>compact = 把会话内未压缩的旧消息交给当前激活 chat 模型生成滚动摘要：</p>
 * <ul>
 *   <li>摘要写入 chat_session.context_summary（旧摘要一并喂入，迭代式合并）</li>
 *   <li>被摘要的消息打 compressed=1（构建 LLM 上下文时跳过，数据不删）</li>
 *   <li>保留最近 N 条原文不压缩（keepRecent，保证细节上下文）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final com.gewu.client.LangChain4jConfig langChain4jConfig;
    private final UserSettingService userSettingService;

    /** 默认配置（用户未设置时） */
    public static final boolean DEFAULT_AUTO_COMPACT = true;
    public static final int DEFAULT_THRESHOLD = 8000;
    public static final int DEFAULT_KEEP_RECENT = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用户压缩配置（委托 UserSettingService；键名映射 compactThreshold→threshold 等）
     */
    public Map<String, Object> loadUserSettings(Long userId) {
        Map<String, Object> raw = userSettingService.loadSettings(userId);
        Map<String, Object> conf = new HashMap<>();
        conf.put("autoCompact", raw.getOrDefault("autoCompact", DEFAULT_AUTO_COMPACT));
        conf.put("threshold", raw.getOrDefault("compactThreshold", DEFAULT_THRESHOLD));
        conf.put("keepRecent", raw.getOrDefault("compactKeepRecent", DEFAULT_KEEP_RECENT));
        return conf;
    }

    /**
     * 生成滚动摘要（同步调用当前激活 chat 模型）
     *
     * @return 新的 context_summary 全文
     */
    public String compact(String sessionId, Long userId, int keepRecent) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.gewu.common.BizException(com.gewu.common.ErrorCode.NOT_FOUND);
        }

        List<ChatMessage> messages = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        List<ChatMessage> active = messages.stream()
                .filter(m -> m.getCompressed() == null || m.getCompressed() != 1)
                .toList();
        if (active.size() <= keepRecent) {
            return session.getContextSummary(); // 没有可压缩的余量
        }
        List<ChatMessage> toCompact = active.subList(0, active.size() - keepRecent);

        String oldSummary = session.getContextSummary();
        StringBuilder transcript = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            transcript.append("【已有摘要】\n").append(oldSummary).append("\n\n【新增对话】\n");
        }
        for (ChatMessage m : toCompact) {
            String role = "assistant".equals(m.getRole()) ? "AI" : "用户";
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.length() > 2000) content = content.substring(0, 2000) + "…";
            transcript.append(role).append(": ").append(content).append("\n");
        }

        String prompt = "请把以下对话内容压缩成一份简洁的上下文摘要（中文，要点式，"
                + "保留：用户的目标与偏好、已做过的关键决定、重要结论与未完成事项。"
                + "若已有旧摘要，将新增对话合并进去而不是简单拼接，控制在 500 字以内）：\n\n"
                + transcript;

        String summary;
        try {
            var model = langChain4jConfig.getActiveChatModel();
            var reply = model.chat(dev.langchain4j.data.message.UserMessage.from(prompt));
            summary = reply.aiMessage().text();
        } catch (Exception e) {
            log.error("[T3] compact 摘要生成失败：sessionId={}", sessionId, e);
            throw new com.gewu.common.BizException(500, "压缩失败：" + e.getMessage());
        }
        if (summary != null && summary.length() > 4000) {
            summary = summary.substring(0, 4000);
        }

        // 1) 写摘要
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getContextSummary, summary));
        // 2) 打压缩标记
        for (ChatMessage m : toCompact) {
            messageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .eq(ChatMessage::getId, m.getId())
                    .set(ChatMessage::getCompressed, 1));
        }
        log.info("[T3] compact 完成：sessionId={}, compressed={}, kept={}, summaryLen={}",
                sessionId, toCompact.size(), keepRecent, summary == null ? 0 : summary.length());
        return summary;
    }

    /**
     * 是否应自动压缩（发送前调用）：autoCompact 开且（摘要为空 ? 未压缩消息 token 估算 : 累计估算）超阈值
     */
    public boolean shouldAutoCompact(String sessionId, Long userId) {
        Map<String, Object> conf = loadUserSettings(userId);
        if (!Boolean.TRUE.equals(conf.get("autoCompact"))) {
            return false;
        }
        int threshold = ((Number) conf.get("threshold")).intValue();
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return false;
        }
        List<ChatMessage> messages = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        long est = 0;
        for (ChatMessage m : messages) {
            if (m.getCompressed() != null && m.getCompressed() == 1) continue;
            est += (m.getContent() == null ? 0 : m.getContent().length()) / 4;
        }
        String summary = session.getContextSummary();
        if (summary != null) est += summary.length() / 4;
        return est > threshold;
    }
}
