package com.gewu.module.chat.service;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.module.chat.entity.ChatMessage;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.monitor.service.LlmMonitorService;
import dev.langchain4j.data.message.AiMessage;
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
     * 发送文本消息并获取回复（支持指定模型）
     */
    @Transactional
    public String sendTextMessage(String sessionId, String message, String systemPrompt, String modelId, Long userId) {
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

        // 添加用户消息
        messages.add(UserMessage.from(message));

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

        messages.add(UserMessage.from(message));

        // 异步保存用户消息
        messageService.saveMessage(
                sessionId, "user", message, "text", null,
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
