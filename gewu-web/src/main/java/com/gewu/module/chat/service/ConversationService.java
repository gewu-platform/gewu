package com.gewu.module.chat.service;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.infrastructure.storage.FileStorage;
import com.gewu.module.chat.entity.ChatMessage;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.monitor.service.LlmMonitorService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 会话上下文管理服务（对齐 java-ai-native ConversationService，数据库版本）
 * LangChain4j 实现：从数据库加载历史消息构建上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final LangChain4jConfig langChain4jConfig;
    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;
    private final TokenUsageService tokenUsageService;
    private final LlmMonitorService llmMonitorService;
    /** V2.5.0 M1.2：图片从 MinIO 读出后以 base64 传入模型（云端无法访问自部署地址） */
    private final FileStorage fileStorage;

    /**
     * 创建新会话
     */
    @Transactional
    public String createSession() {
        return createSession(null, null, null);
    }

    /**
     * 创建新会话（带参数）
     */
    @Transactional
    public String createSession(String mode, String model, String systemPrompt) {
        return createSession(mode, model, systemPrompt, null);
    }

    /**
     * T1.1：创建会话（仅归属，mode/model 走默认）
     */
    @Transactional
    public String createSession(Long userId) {
        return createSession(null, null, null, userId);
    }

    /**
     * T1.1：创建会话（带归属）——userId 来自服务端解析的当前登录账号
     */
    @Transactional
    public String createSession(String mode, String model, String systemPrompt, Long userId) {
        String sessionId = UUID.randomUUID().toString();

        if (mode == null) {
            mode = "text";
        }
        if (model == null) {
            model = langChain4jConfig.getActiveModel();
        }

        sessionService.createSession(sessionId, mode, model, systemPrompt, userId);
        log.info("创建会话成功：sessionId={}, mode={}, model={}, userId={}", sessionId, mode, model, userId);
        return sessionId;
    }


    /**
     * V1.1.0 C1/T1.2：派生子会话——克隆父 systemPrompt + context_summary 作为起点，父子独立演进。
     * 编排树仅一层：父会话已是派生子会话时禁止再派生（对齐 V2.0.0 Non-Goals）。
     */
    @Transactional
    public String deriveSession(String parentSessionId, Long userId) {
        ChatSession parent = sessionService.getSession(parentSessionId);
        if (parent == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "父会话不存在：" + parentSessionId);
        }
        if (StringUtils.hasText(parent.getParentSessionId())) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "仅支持一层派生：当前会话已是子会话");
        }
        // 无压缩摘要时，取最近 N 条未压缩消息做机械摘录（预算截断）作为派生起点
        String summary = parent.getContextSummary();
        if (!StringUtils.hasText(summary)) {
            summary = buildRecentDigest(parentSessionId, 10, 2000);
        }
        String childId = createSession(parent.getMode(), parent.getModel(), parent.getSystemPrompt(), userId);
        ChatSession child = sessionService.getSession(childId);
        child.setParentSessionId(parentSessionId);
        if (StringUtils.hasText(summary)) {
            child.setContextSummary(summary);
        }
        sessionService.updateSession(child);
        log.info("派生子会话成功：childId={}, parentId={}, userId={}", childId, parentSessionId, userId);
        return childId;
    }

    /**
     * V1.1.0 C1/T1.2：最近消息机械摘录（无 context_summary 时的派生起点）
     */
    private String buildRecentDigest(String sessionId, int limit, int budgetChars) {
        List<ChatMessage> all = messageService.getMessagesBySessionId(sessionId);
        if (all == null || all.isEmpty()) {
            return null;
        }
        int from = Math.max(0, all.size() - limit);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : all.subList(from, all.size())) {
            if (m.getCompressed() != null && m.getCompressed() == 1) {
                continue;
            }
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            String c = m.getContent() == null ? "" : m.getContent().replaceAll("\\s+", " ").trim();
            if (c.isEmpty()) {
                continue;
            }
            if (c.length() > 200) {
                c = c.substring(0, 200) + "…";
            }
            sb.append(role).append("：").append(c).append('\n');
            if (sb.length() > budgetChars) {
                break;
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * V1.1.0 C1/T1.4：子会话完成后自动回传结果摘要到父会话（只向上一层，防循环）。
     * 落库为 user 角色 + 「[子会话结果]」前缀：历史构建只映射 user/assistant，
     * user 角色确保父会话下一轮 LLM 上下文自然包含结论（system 角色会被历史构建跳过）。
     * 回传消息不走 saveExchangeAndStats，天然不触发对祖父会话的再回传。
     */
    public void propagateToParent(String childSessionId, String finalAnswer) {
        try {
            ChatSession child = sessionService.getSession(childSessionId);
            if (child == null || !StringUtils.hasText(child.getParentSessionId())) {
                return;
            }
            ChatSession parent = sessionService.getSession(child.getParentSessionId());
            if (parent == null) {
                return;
            }
            String digest = finalAnswer == null ? "" : finalAnswer.trim();
            if (digest.length() > 2000) {
                digest = digest.substring(0, 2000) + "…（已截断）";
            }
            String title = StringUtils.hasText(child.getRemark()) ? child.getRemark() : childSessionId;
            String content = "[子会话结果] 来自子会话「" + title + "」：" + digest;
            messageService.saveMessage(parent.getId(), "user", content, "text", null,
                    child.getModel(), content.length() / 4, 0, null,
                    UUID.randomUUID().toString(), null);
            sessionService.incrementMessageCount(parent.getId());
            log.info("子会话结果已回传父会话：child={}, parent={}", childSessionId, parent.getId());
        } catch (Exception e) {
            log.warn("子会话结果回传失败（不阻断主流程）：child={}, err={}", childSessionId, e.getMessage());
        }
    }

    /**
     * V2.5.0 M1.2：构造用户消息——无图时纯文本，有图时图文混合。
     *
     * <p>为何必须 base64：预签名 URL 指向自部署 MinIO，云端视觉模型无法访问，
     * 只能由后端读出字节后随请求传入。MIME 从对象键扩展名推导。</p>
     *
     * @param text      文本内容
     * @param imageKeys object key 列表（可为 null/空）
     */
    UserMessage buildUserMessage(String text, java.util.List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return UserMessage.from(text);
        }
        java.util.List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        contents.add(dev.langchain4j.data.message.TextContent.from(text));
        int attached = 0;
        for (String key : imageKeys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            try (var in = fileStorage.get(key)) {
                byte[] bytes = in.readAllBytes();
                String mime = mimeTypeOf(key);
                contents.add(ImageContent.from(Base64.getEncoder().encodeToString(bytes), mime));
                attached++;
            } catch (Exception e) {
                // 单张图失败不阻断对话（与 RAG 检索失败的降级口径一致）
                log.warn("图片附件读取失败（已跳过）：key={}, err={}", key, e.getMessage());
            }
        }
        log.info("[Multimodal] 本次请求携带图片 {} / {} 张", attached, imageKeys.size());
        return UserMessage.from(contents);
    }

    /** 由 object key 推导 MIME（仅覆盖白名单内的图片类型） */
    static String mimeTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /**
     * 图片 object key 列表 → JSON 数组字符串（存入 chat_message.image_urls 列）。
     *
     * <p>该列是历史遗留命名（原名 image_urls），V2.5.0 起存 object key 而非 URL；
     * 前端展示时按需换取预签名链接。手写序列化避免为一个字段引入 Jackson 依赖。</p>
     */
    static String toJsonArray(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"')
              .append(keys.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 发送文本消息并获取回复（支持指定模型）
     */
    @Transactional
    public String sendTextMessage(String sessionId, String message, String systemPrompt, String modelId, Long userId) {
        return sendTextMessage(sessionId, message, systemPrompt, modelId, userId, null);
    }

    /**
     * 发送消息（支持图片附件，V2.5.0 M1.2）
     */
    @Transactional
    public String sendTextMessage(String sessionId, String message, String systemPrompt, String modelId,
                                  Long userId, java.util.List<String> imageKeys) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话 ID 不能为空");
        }

        ChatSession session = sessionService.getSession(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话不存在：" + sessionId);
        }

        // 从数据库加载历史消息，构建 LangChain4j 上下文（T3：跳过 compressed，摘要置顶）
        List<ChatMessage> historyEntities = messageService.getMessagesBySessionId(sessionId);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        String compactSummary = session.getContextSummary();
        if (StringUtils.hasText(compactSummary)) {
            messages.add(SystemMessage.from("【早前对话摘要】\n" + compactSummary));
        }
        for (ChatMessage entity : historyEntities) {
            if (entity.getCompressed() != null && entity.getCompressed() == 1) {
                continue; // T3 上下文压缩：已并入摘要的消息不进 LLM 上下文
            }
            if ("user".equals(entity.getRole())) {
                messages.add(UserMessage.from(entity.getContent()));
            } else if ("assistant".equals(entity.getRole())) {
                messages.add(AiMessage.from(entity.getContent()));
            }
        }

        // 新对话且提供系统提示词时，插入系统消息
        if (messages.isEmpty() && StringUtils.hasText(systemPrompt)) {
            messages.add(0, SystemMessage.from(systemPrompt));
        }

        // 添加用户消息（V2.5.0 M1.2：有图时构造图文混合消息）
        messages.add(buildUserMessage(message, imageKeys));

        // 调用 AI（LangChain4j ChatModel）
        String replyText;
        long startTime = System.currentTimeMillis();
        try {
            replyText = langChain4jConfig.getChatModel(modelId).chat(messages).aiMessage().text();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("AI 调用失败：modelId={}, latency={}ms, error={}", modelId, latency, e.getMessage(), e);
            throw new BizException(ErrorCode.API_RESPONSE_ERROR.getCode(),
                    "调用 " + modelId + " 失败：" + e.getMessage());
        }
        long latency = System.currentTimeMillis() - startTime;

        if (!StringUtils.hasText(replyText)) {
            throw new BizException(ErrorCode.API_RESPONSE_ERROR.getCode(), "AI 服务返回空响应");
        }

        return saveExchangeAndStats(sessionId, message, replyText, modelId, latency, userId);
    }

    /**
     * 流式发送文本消息（SSE 流式输出）
     */
    public Flux<String> streamTextMessage(String sessionId, String message, String systemPrompt, String modelId, Long userId) {
        return streamTextMessage(sessionId, message, systemPrompt, modelId, userId, null);
    }

    /**
     * 流式发送消息（支持图片附件，V2.5.0 M1.2）
     */
    public Flux<String> streamTextMessage(String sessionId, String message, String systemPrompt, String modelId,
                                          Long userId, java.util.List<String> imageKeys) {
        if (!StringUtils.hasText(sessionId)) {
            return Flux.error(new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话 ID 不能为空"));
        }

        ChatSession session = sessionService.getSession(sessionId);
        if (session == null) {
            return Flux.error(new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话不存在：" + sessionId));
        }

        // 从数据库加载历史消息（T3：跳过 compressed，摘要置顶）
        List<ChatMessage> historyEntities = messageService.getMessagesBySessionId(sessionId);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        String compactSummary = session.getContextSummary();
        if (StringUtils.hasText(compactSummary)) {
            messages.add(SystemMessage.from("【早前对话摘要】\n" + compactSummary));
        }
        for (ChatMessage entity : historyEntities) {
            if (entity.getCompressed() != null && entity.getCompressed() == 1) {
                continue; // T3 上下文压缩：已并入摘要的消息不进 LLM 上下文
            }
            if ("user".equals(entity.getRole())) {
                messages.add(UserMessage.from(entity.getContent()));
            } else if ("assistant".equals(entity.getRole())) {
                messages.add(AiMessage.from(entity.getContent()));
            }
        }

        if (messages.isEmpty() && StringUtils.hasText(systemPrompt)) {
            messages.add(0, SystemMessage.from(systemPrompt));
        }

        // V2.5.0 M1.2：有图时构造图文混合消息
        messages.add(buildUserMessage(message, imageKeys));

        // 异步保存用户消息（图片 object key 一并落库，供历史重载显示）
        messageService.saveMessage(
                sessionId, "user", message, imageKeys != null && !imageKeys.isEmpty() ? "multimodal" : "text",
                imageKeys != null && !imageKeys.isEmpty() ? toJsonArray(imageKeys) : null,
                modelId, message.length() / 4, 0, null,
                UUID.randomUUID().toString(), null
        );

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullReply = new StringBuilder();
        long startTime = System.currentTimeMillis();

        // LangChain4j StreamingChatModel 流式调用
        ChatRequest chatRequest = ChatRequest.builder().messages(messages).build();
        langChain4jConfig.getStreamingChatModel(modelId).chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null) {
                    fullReply.append(partialResponse);
                }
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                String replyText = fullReply.toString();
                long latency = System.currentTimeMillis() - startTime;

                if (StringUtils.hasText(replyText)) {
                    try {
                        saveExchangeAndStats(sessionId, message, replyText, modelId, latency, userId);
                    } catch (Exception e) {
                        log.error("流式完成后保存统计失败：{}", e.getMessage(), e);
                    }
                }
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }

    /**
     * 保存一轮对话（user + assistant 消息）并更新统计
     */
    private String saveExchangeAndStats(String sessionId, String userMessage, String replyText,
                                        String modelId, long latency, Long userId) {
        int inputTokens = userMessage.length() / 4;
        int outputTokens = replyText.length() / 4;

        // 保存用户消息
        messageService.saveMessage(
                sessionId, "user", userMessage, "text", null,
                modelId, inputTokens, 0, null,
                UUID.randomUUID().toString(), null
        );

        // 保存 AI 回复
        ChatMessage aiMessage = messageService.saveMessage(
                sessionId, "assistant", replyText, "text", null,
                modelId, 0, outputTokens, "stop",
                UUID.randomUUID().toString(), null
        );

        // 更新会话统计
        sessionService.incrementMessageCount(sessionId);
        sessionService.updateTokenStats(sessionId, inputTokens, outputTokens);

        // 记录 Token 使用
        if (aiMessage.getId() != null) {
            tokenUsageService.recordUsage(
                    sessionId, aiMessage.getId(), modelId,
                    inputTokens, outputTokens, 0
            );
        }

        // 异步记录 LLM 调用日志（P6 监控埋点，不阻塞主流程；sync/stream 两路在此汇合）
        llmMonitorService.asyncRecordCall(
                sessionId, modelId, null,
                inputTokens, outputTokens, latency,
                "success", null,
                userMessage, replyText, userId
        );

        log.info("对话完成：sessionId={}, latency={}ms, in={}, out={}",
                sessionId, latency, inputTokens, outputTokens);
        // V1.1.0 C1/T1.4：子会话完成后自动回传结果摘要到父会话（sync/stream 两路汇合点，只向上一层）
        propagateToParent(sessionId, replyText);
        return replyText;
    }

    /**
     * 清除会话历史
     */
    @Transactional
    public void clearSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话 ID 不能为空");
        }

        ChatSession session = sessionService.getSession(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话不存在：" + sessionId);
        }

        messageService.deleteBySessionId(sessionId);

        // 重置会话统计
        session.setMessageCount(0);
        session.setInputTokens(0L);
        session.setOutputTokens(0L);
        sessionService.updateSession(session);

        log.info("清除会话历史：sessionId={}", sessionId);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话 ID 不能为空");
        }

        ChatSession session = sessionService.getSession(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话不存在：" + sessionId);
        }

        messageService.deleteBySessionId(sessionId);
        sessionService.deleteSession(sessionId);

        log.info("删除会话：sessionId={}", sessionId);
    }

    /**
     * 分页获取会话
     */
    public List<ChatSession> getSessionsByPage(Integer page, Integer size) {
        return sessionService.getSessionsByPage(page, size);
    }

    /**
     * 获取会话总数
     */
    public long getSessionCount() {
        return sessionService.getSessionCount();
    }

    /**
     * 获取会话详情
     */
    public ChatSession getSession(String sessionId) {
        return sessionService.getSession(sessionId);
    }

    /**
     * 获取会话消息列表
     */
    public List<ChatMessage> getSessionMessages(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session == null) {
            return List.of();
        }
        return messageService.getMessagesBySessionId(sessionId);
    }

    /**
     * 获取或创建会话（用于会话复用）
     */
    @Transactional
    public String getOrCreateSession() {
        return sessionService.getOrCreateSession(null);
    }

    /**
     * T1.1：获取或创建会话（带归属）——复用判定限定在当前用户名下
     */
    public String getOrCreateSession(Long userId) {
        return sessionService.getOrCreateSession(userId);
    }

    /**
     * T1.2：当前用户的会话列表（替代全库分页拉取）
     */
    public List<ChatSession> getSessionsByUserId(Long userId) {
        return sessionService.getSessionsByUserId(userId);
    }

    public long getSessionCountByUserId(Long userId) {
        return sessionService.getSessionCountByUserId(userId);
    }

    /**
     * T1.3：会话访问闸门透传
     */
    public ChatSession ensureAccessible(String sessionId, Long userId, boolean adminBypass) {
        return sessionService.ensureAccessible(sessionId, userId, adminBypass);
    }

    // ==================== T2 会话管理：重命名 / 分组 ====================

    public void renameSession(String sessionId, String remark) {
        sessionService.renameSession(sessionId, remark);
    }

    public void setSessionGroup(String sessionId, String groupName) {
        sessionService.setSessionGroup(sessionId, groupName);
    }

    public int dissolveGroup(String groupName, Long userId) {
        return sessionService.dissolveGroup(groupName, userId);
    }

    public List<String> listGroups(Long userId) {
        return sessionService.listGroups(userId);
    }
}
