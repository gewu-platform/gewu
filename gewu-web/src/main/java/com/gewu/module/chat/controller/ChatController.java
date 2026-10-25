package com.gewu.module.chat.controller;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.common.R;
import com.gewu.module.chat.dto.*;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.chat.service.ConversationService;
import com.gewu.module.chat.service.TokenUsageService;
import com.gewu.module.user.dto.UserInfoDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 聊天 REST API 控制器（对齐 java-ai-native ChatController，LangChain4j 实现）
 *
 * <p>端点同形：/api/chat/*，17 个端点。多模态预留（API 契约先落，图像输入后置）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;
    private final TokenUsageService tokenUsageService;
    private final LangChain4jConfig langChain4jConfig;
    private final com.gewu.module.monitor.service.UsageQuotaService usageQuotaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 会话管理 ====================

    /**
     * 创建新会话
     */
    @PostMapping("/session")
    public R<SessionResponseDTO> createSession() {
        String sessionId = conversationService.createSession();
        log.info("会话创建成功：sessionId={}", sessionId);
        return R.success("会话创建成功", SessionResponseDTO.of(sessionId, null));
    }

    /**
     * 获取或创建会话（用于会话复用）
     */
    @PostMapping("/session/active")
    public R<SessionResponseDTO> getOrCreateSession() {
        String sessionId = conversationService.getOrCreateSession();
        ChatSession session = conversationService.getSession(sessionId);
        SessionResponseDTO response = SessionResponseDTO.of(
                sessionId, session != null ? session.getMode() : "text",
                session != null ? session.getModel() : langChain4jConfig.getActiveModel(),
                session != null
        );
        log.info("会话获取/创建：sessionId={}, reused={}", sessionId, response.getReused());
        return R.success(response);
    }

    // ==================== 消息发送 ====================

    /**
     * 发送聊天消息
     */
    @PostMapping("/message")
    public R<ChatResponseDTO> sendMessage(@RequestBody ChatRequestDTO request) {
        log.info("收到聊天请求：sessionId={}, messageLength={}, model={}",
                request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getModel());

        if (!StringUtils.hasText(request.getMessage())) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());

        // 如果没有会话 ID，创建新会话
        String sessionId = request.getSessionId();
        if (!StringUtils.hasText(sessionId)) {
            sessionId = conversationService.createSession();
            log.info("自动创建新会话：sessionId={}", sessionId);
        }

        // 解析模型：请求指定 > 当前激活
        String model = StringUtils.hasText(request.getModel())
                ? request.getModel()
                : langChain4jConfig.getActiveModel();

        if (!langChain4jConfig.getModels().containsKey(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "未知模型 ID: " + model);
        }

        // 多模态预留：P3 先落 API 契约，图像输入后置（对齐计划决策 12）
        if (request.getUseMultimodal() != null && request.getUseMultimodal()) {
            log.info("多模态模式预留中，本次按文本模式处理（imageUrls 已接收 {} 条）",
                    request.getImageUrls() != null ? request.getImageUrls().size() : 0);
        }

        String response = conversationService.sendTextMessage(
                sessionId, request.getMessage(), request.getSystemPrompt(), model);

        log.info("聊天响应成功：sessionId={}, responseLength={}, model={}",
                sessionId, response.length(), model);
        return R.success(ChatResponseDTO.success(sessionId, response, model, false));
    }

    /**
     * 流式发送聊天消息（SSE 流式输出）
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@RequestBody ChatRequestDTO request) {
        log.info("收到流式聊天请求：sessionId={}, messageLength={}, model={}",
                request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getModel());

        if (!StringUtils.hasText(request.getMessage())) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());

        String sessionId = request.getSessionId();
        if (!StringUtils.hasText(sessionId)) {
            sessionId = conversationService.createSession();
        }
        final String sessionIdFinal = sessionId;

        String model = StringUtils.hasText(request.getModel())
                ? request.getModel()
                : langChain4jConfig.getActiveModel();

        if (!langChain4jConfig.getModels().containsKey(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "未知模型 ID: " + model);
        }

        // SSE emitter，超时 5 分钟（对齐 native）
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        StringBuilder fullReply = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        try {
            conversationService.streamTextMessage(sessionIdFinal, request.getMessage(),
                            request.getSystemPrompt(), model)
                    .doOnNext(chunk -> {
                        try {
                            if (chunk != null) {
                                fullReply.append(chunk);
                            }
                            String json = objectMapper.writeValueAsString(
                                    ChatStreamResponseDTO.chunk(sessionIdFinal, chunk));
                            emitter.send(SseEmitter.event().name("message").data(json));
                        } catch (Exception e) {
                            log.error("SSE 发送失败：{}", e.getMessage());
                        }
                    })
                    .doOnError(err -> {
                        try {
                            String json = objectMapper.writeValueAsString(
                                    ChatStreamResponseDTO.error(sessionIdFinal, err.getMessage()));
                            emitter.send(SseEmitter.event().name("error").data(json));
                        } catch (Exception e) {
                            log.error("SSE 错误发送失败：{}", e.getMessage());
                        }
                        emitter.completeWithError(err);
                    })
                    .doOnComplete(() -> {
                        try {
                            String reply = fullReply.toString();
                            outputTokens.set(reply.length() / 4);
                            inputTokens.set(request.getMessage().length() / 4);
                            String json = objectMapper.writeValueAsString(
                                    ChatStreamResponseDTO.done(sessionIdFinal, reply, model,
                                            inputTokens.get(), outputTokens.get()));
                            emitter.send(SseEmitter.event().name("done").data(json));
                        } catch (Exception e) {
                            log.error("SSE 完成事件发送失败：{}", e.getMessage());
                        }
                        emitter.complete();
                    })
                    .subscribe();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    // ==================== 会话操作 ====================

    /**
     * 清除会话历史
     */
    @PostMapping("/session/{sessionId}/clear")
    public R<SessionResponseDTO> clearSession(@PathVariable String sessionId) {
        conversationService.clearSession(sessionId);
        log.info("会话历史已清除：sessionId={}", sessionId);
        return R.success("会话历史已清除", SessionResponseDTO.of(sessionId, null));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public R<SessionResponseDTO> deleteSession(@PathVariable String sessionId) {
        conversationService.deleteSession(sessionId);
        log.info("会话已删除：sessionId={}", sessionId);
        return R.success("会话已删除", SessionResponseDTO.of(sessionId, null));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public R<SessionListResponseDTO> getSessions(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        List<ChatSession> sessions = conversationService.getSessionsByPage(page, size);
        long total = conversationService.getSessionCount();
        return R.success(SessionListResponseDTO.of(sessions, (int) total, page, size));
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/session/{sessionId}")
    public R<ChatSession> getSession(@PathVariable String sessionId) {
        ChatSession session = conversationService.getSession(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND.getCode(), "会话不存在：" + sessionId);
        }
        return R.success(session);
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/session/{sessionId}/messages")
    public R<MessageListResponseDTO> getMessages(@PathVariable String sessionId) {
        List<com.gewu.module.chat.entity.ChatMessage> messages =
                conversationService.getSessionMessages(sessionId);
        return R.success(MessageListResponseDTO.of(messages));
    }

    /**
     * 获取 Token 使用统计
     */
    @GetMapping("/session/{sessionId}/stats")
    public R<TokenStatsResponseDTO> getTokenStats(@PathVariable String sessionId) {
        com.gewu.module.chat.entity.TokenUsage stats = tokenUsageService.getSessionStats(sessionId);
        TokenStatsResponseDTO response;
        if (stats != null) {
            response = TokenStatsResponseDTO.of(sessionId,
                    stats.getInputTokens() != null ? stats.getInputTokens() : 0,
                    stats.getOutputTokens() != null ? stats.getOutputTokens() : 0,
                    stats.getTotalTokens() != null ? stats.getTotalTokens() : 0,
                    stats.getCost() != null ? stats.getCost() : BigDecimal.ZERO);
        } else {
            response = TokenStatsResponseDTO.of(sessionId, 0, 0, 0, BigDecimal.ZERO);
        }
        return R.success(response);
    }

    // ==================== 模式管理 ====================

    /**
     * 切换模式（文本/多模态，多模态预留）
     */
    @PostMapping("/mode")
    public R<ModeStatusDTO> switchMode(@RequestBody SwitchModeRequestDTO request) {
        boolean multimodal = request.getMultimodal() != null && request.getMultimodal();
        log.info("切换模式请求：multimodal={}", multimodal);

        if (multimodal) {
            log.info("多模态模式预留中（P3 后置图像输入），保持文本模式");
        }

        String currentModel = langChain4jConfig.getActiveModel();
        return R.success(ModeStatusDTO.of("text", false, currentModel));
    }

    /**
     * 获取当前模式状态
     */
    @GetMapping("/mode")
    public R<ModeStatusDTO> getMode() {
        String currentModel = langChain4jConfig.getActiveModel();
        return R.success(ModeStatusDTO.of("text", false, currentModel));
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        String activeModelId = langChain4jConfig.getActiveModel();
        LangChain4jConfig.ModelConfig activeConfig = langChain4jConfig.getModels().get(activeModelId);

        result.put("status", "UP");
        result.put("activeModel", activeModelId);
        result.put("activeModelName", activeConfig != null ? activeConfig.getModelName() : "unknown");
        result.put("activeProvider", activeConfig != null ? activeConfig.getProvider() : "unknown");
        result.put("apiKeyConfigured", activeConfig != null && StringUtils.hasText(activeConfig.getApiKey()));
        result.put("currentMode", "text");
        result.put("timestamp", System.currentTimeMillis());
        return R.success(result);
    }

    // ==================== 模型管理 ====================

    /**
     * 获取可用模型列表
     */
    @GetMapping("/models")
    public R<Map<String, Object>> getAvailableModels() {
        List<Map<String, Object>> models = langChain4jConfig.getModels().entrySet().stream()
                .map(entry -> {
                    String modelId = entry.getKey();
                    LangChain4jConfig.ModelConfig config = entry.getValue();
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", modelId);
                    item.put("provider", config.getProvider());
                    item.put("modelName", config.getModelName());
                    item.put("description", config.getDescription() != null
                            ? config.getDescription() : config.getModelName());
                    item.put("active", modelId.equals(langChain4jConfig.getActiveModel()));
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("models", models);
        response.put("activeModel", langChain4jConfig.getActiveModel());
        response.put("count", models.size());
        return R.success(response);
    }

    /**
     * 切换 AI 模型
     */
    @PostMapping("/model")
    public R<Map<String, Object>> switchModel(@RequestBody Map<String, String> request) {
        String modelId = request.get("modelId");
        log.info("切换 AI 模型：{}", modelId);

        LangChain4jConfig.ModelConfig config = langChain4jConfig.getModels().get(modelId);
        if (config == null) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(),
                    "未知模型 ID: " + modelId);
        }

        String oldModel = langChain4jConfig.getActiveModel();
        langChain4jConfig.setActiveModel(modelId);
        langChain4jConfig.evictAllChatModels();

        Map<String, Object> response = new HashMap<>();
        response.put("oldModel", oldModel);
        response.put("newModel", modelId);
        response.put("modelName", config.getModelName());
        return R.success("模型切换成功", response);
    }

    /**
     * 获取当前模型信息
     */
    @GetMapping("/model")
    public R<Map<String, Object>> getCurrentModel() {
        String modelId = langChain4jConfig.getActiveModel();
        LangChain4jConfig.ModelConfig config = langChain4jConfig.getModels().get(modelId);

        Map<String, Object> modelInfo = new HashMap<>();
        modelInfo.put("id", modelId);
        modelInfo.put("modelName", config != null ? config.getModelName() : "unknown");
        modelInfo.put("provider", config != null ? config.getProvider() : "unknown");
        modelInfo.put("description", config != null && config.getDescription() != null
                ? config.getDescription() : modelId);
        modelInfo.put("active", true);
        return R.success(modelInfo);
    }
}
