package com.gewu.module.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.common.R;
import com.gewu.module.chat.dto.*;
import com.gewu.module.chat.service.ChatAttachmentService;
import com.gewu.module.chat.service.CommandService;
import com.gewu.module.chat.service.ContextCompactService;
import com.gewu.module.chat.service.ConversationService;
import com.gewu.module.monitor.service.UsageQuotaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 聊天主链路 REST API（/api/chat）。
 *
 * <p>承载消息发送（同步 + SSE 流式）、斜杠命令执行、图片附件上传、健康探活四类端点。</p>
 *
 * <p>V2.5.0 重构：本类原为 586 行 / 27 端点的 God Controller，按资源边界拆为三个：
 * {@link ChatController}（消息收发）、{@link ChatSessionController}（会话生命周期）、
 * {@link ChatSettingsController}（压缩设置与模式/模型管理）。三者共享 /api/chat 前缀，
 * (verb, path) 组合互不重叠，对外契约完全不变。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;
    private final ContextCompactService contextCompactService;
    private final CommandService commandService;
    private final LangChain4jConfig langChain4jConfig;
    private final UsageQuotaService usageQuotaService;
    /** V2.5.0 M1.1：对话图片附件上传（写 MinIO，返回预签名 URL） */
    private final ChatAttachmentService attachmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 发送聊天消息
     */
    @PostMapping("/message")
    public R<ChatResponse> sendMessage(@RequestBody ChatRequest request) {
        log.info("收到聊天请求：sessionId={}, messageLength={}, model={}",
                request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getModel());

        if (!StringUtils.hasText(request.getMessage())) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);

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

        // T3.4 自动压缩：发送前估算超阈值则先压缩（失败不阻断对话）
        autoCompactIfNeeded(sessionId, currentUserId);

        // 多模态预留：P3 先落 API 契约，图像输入后置（对齐计划决策 12）
        if (request.getUseMultimodal() != null && request.getUseMultimodal()) {
            log.info("多模态模式预留中，本次按文本模式处理（imageUrls 已接收 {} 条）",
                    request.getImageUrls() != null ? request.getImageUrls().size() : 0);
        }

        String response = conversationService.sendTextMessage(
                sessionId, request.getMessage(), request.getSystemPrompt(), model, currentUserId);

        log.info("聊天响应成功：sessionId={}, responseLength={}, model={}",
                sessionId, response.length(), model);
        return R.success(ChatResponse.success(sessionId, response, model, false));
    }

    /**
     * 流式发送聊天消息（SSE 流式输出）
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@RequestBody ChatRequest request) {
        log.info("收到流式聊天请求：sessionId={}, messageLength={}, model={}",
                request.getSessionId(),
                request.getMessage() != null ? request.getMessage().length() : 0,
                request.getModel());

        if (!StringUtils.hasText(request.getMessage())) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);

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

        // T3.4 自动压缩：发送前估算超阈值则先压缩（失败不阻断对话）
        autoCompactIfNeeded(sessionIdFinal, currentUserId);

        // SSE emitter，超时 5 分钟（对齐 native）
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        StringBuilder fullReply = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        try {
            conversationService.streamTextMessage(sessionIdFinal, request.getMessage(),
                            request.getSystemPrompt(), model, currentUserId)
                    .doOnNext(chunk -> {
                        try {
                            if (chunk != null) {
                                fullReply.append(chunk);
                            }
                            String json = objectMapper.writeValueAsString(
                                    ChatStreamResponse.chunk(sessionIdFinal, chunk));
                            emitter.send(SseEmitter.event().name("message").data(json));
                        } catch (Exception e) {
                            log.error("SSE 发送失败：{}", e.getMessage());
                        }
                    })
                    .doOnError(err -> {
                        try {
                            String json = objectMapper.writeValueAsString(
                                    ChatStreamResponse.error(sessionIdFinal, err.getMessage()));
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
                                    ChatStreamResponse.done(sessionIdFinal, reply, model,
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

    /**
     * 流式执行斜杠命令（SSE：chunk 逐步过程 + done 收尾；/reset 的 done 带 newSessionId）
     */
    @PostMapping(value = "/command/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<String> executeCommand(@RequestBody ChatRequest request) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        String sessionId = request.getSessionId();
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "命令需要会话上下文");
        }
        conversationService.ensureAccessible(sessionId, currentUserId, usageQuotaService.isCurrentUserAdmin());
        // 直接返回 Flux：Spring MVC 原生按 SSE data: 流式输出（SseEmitter 同步 subscribe 会在连接建立前丢事件）
        return commandService.execute(sessionId, request.getMessage(), currentUserId);
    }

    /**
     * 命令清单（前端 / 补全提示用）
     */
    @GetMapping("/commands")
    public R<List<com.gewu.module.chat.service.CommandService.CommandSpec>> listCommands() {
        return R.success(commandService.listCommands());
    }

    /**
     * V2.5.0 M1.1：上传对话图片附件（写 MinIO，返回预签名访问 URL）
     *
     * <p>白名单 jpg/jpeg/png/webp/gif、单文件≤10MB、MIME 前缀 image/ 双保险。
     * 返回的 URL 可直接回填到 /message 或 /message/stream 的 imageUrls 字段。</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> uploadAttachment(@RequestParam("file") MultipartFile file) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        if (currentUserId == null) {
            throw new BizException(401, "请先登录");
        }
        String url = attachmentService.uploadImage(file);
        Map<String, Object> result = new HashMap<>();
        result.put("url", url);
        result.put("maxSizeBytes", ChatAttachmentService.maxSizeBytes());
        return R.success("上传成功", result);
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

    /**
     * T3.4 自动压缩钩子：autoCompact 开且估算 token 超阈值时，先压缩再放行发送。
     * 压缩失败仅记日志不阻断（对话可用性优先）。
     */
    private void autoCompactIfNeeded(String sessionId, Long userId) {
        try {
            if (contextCompactService.shouldAutoCompact(sessionId, userId)) {
                java.util.Map<String, Object> conf = contextCompactService.loadUserSettings(userId);
                int keep = ((Number) conf.get("keepRecent")).intValue();
                log.info("[T3.4] 自动压缩触发：sessionId={}, keepRecent={}", sessionId, keep);
                contextCompactService.compact(sessionId, userId, keep);
            }
        } catch (Exception e) {
            log.warn("[T3.4] 自动压缩失败（不阻断对话）：sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

}
