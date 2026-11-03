package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.dto.AgentChatRequest;
import com.gewu.module.agent.dto.AgentChatResponse;
import com.gewu.module.agent.dto.AgentStreamResponse;
import com.gewu.module.agent.service.AgentService.AgentIteration;
import com.gewu.module.agent.service.AgentService.AgentResult;
import com.gewu.module.agent.service.AgentService;
import com.gewu.module.chat.service.ChatSessionService;
import com.gewu.module.monitor.service.UsageQuotaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 对话入口 REST API（/api/agent）。
 *
 * <p>承载 5 个对话入口：同步（chat / simple / smart）与流式（stream / stream-session）。
 * 全部受 T6 配额闸门保护，流式端点使用 SseEmitter 直写事件流。</p>
 *
 * <p>V2.5.0 重构：原 AgentController（420 行 / 18 端点）按资源边界拆为三个：
 * {@link AgentChatController}（对话）、{@link AgentDefinitionController}（定义 CRUD）、
 * {@link AgentToolController}（工具/健康/审批）。共享 /api/agent 前缀，
 * (verb, path) 组合互不重叠，对外契约不变。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentService agentService;
    private final ChatSessionService chatSessionService;
    private final UsageQuotaService usageQuotaService;

    /**
     * Agent 对话（核心）：agentId 指定走定义，否则带全量工具
     */
    @PostMapping("/chat")
    public R<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return R.error(400, "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);

        AgentResult result;
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            result = agentService.chatWithAgent(request.getAgentId(), request.getMessage(), currentUserId);
        } else {
            result = agentService.chatSmart(request.getMessage(), request.getSystemPrompt(), request.getModelId(), currentUserId);
        }
        return R.success(toResponse(result));
    }

    /**
     * 简易对话（直答，不带工具）
     */
    @PostMapping("/chat/simple")
    public R<AgentChatResponse> chatSimple(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return R.error(400, "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);
        AgentResult result = agentService.chatSimple(
                request.getMessage(), request.getSystemPrompt(), request.getModelId(), currentUserId);
        return R.success(toResponse(result));
    }

    /**
     * 智能对话（全量工具）
     */
    @PostMapping("/chat/smart")
    public R<AgentChatResponse> smartChat(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return R.error(400, "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);
        AgentResult result = agentService.chatSmart(
                request.getMessage(), request.getSystemPrompt(), request.getModelId(), currentUserId);
        return R.success(toResponse(result));
    }

    /**
     * 流式对话（SSE）：事件链 iteration → thought → action → tool_result
     * （→ approval_required）→ done
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);
        log.info("Agent 流式请求：agentId={}, modelId={}, messageLength={}",
                request.getAgentId(), request.getModelId(), request.getMessage().length());

        // SseEmitter 直写（对齐 chat 模块管道）：Flux 返回会被 MVC 整体缓冲，
        // 事件挤到 LLM 轮结束一次性冲出（实测 spread 55ms/171 事件），无流式体验
        SseEmitter emitter = new SseEmitter(300_000L);
        // subscribeOn：把整个 function-calling 循环挪到后台线程 —— 若在请求线程上直接 subscribe，
        // 所有 emitter.send 都发生在控制器 return 之前，会被 Spring 缓存到异步启动后一次性 flush
        // （实测 spread 103ms/161 事件）——挪后台后事件才逐个推送
        reactor.core.Disposable disposable = agentService.chatStream(
                request.getMessage(), request.getSystemPrompt(), request.getModelId(), request.getAgentId(),
                request.getSessionId(), currentUserId,
                request.getKnowledgeDocumentIds(), request.getRagTopK())
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (Exception e) {
                        log.warn("SSE 发送失败（客户端断开）：{}", e.getMessage());
                    }
                },
                err -> emitter.completeWithError(err),
                emitter::complete
            );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
        return emitter;
    }

    /**
     * 智能模式流式对话：会话记忆 + 工具循环 + 轨迹落库（编排层统一入口）
     * 与 /chat/stream 区别：sessionId 必填，历史注入 + 回复/工具轨迹落 chat_message
     */
    @PostMapping(value = "/chat/stream/session", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamSession(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        usageQuotaService.checkQuota(currentUserId);
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("会话模式下 sessionId 不能为空");
        }
        log.info("Agent 会话流式请求：sessionId={}, agentId={}, modelId={}, messageLength={}",
                request.getSessionId(), request.getAgentId(), request.getModelId(), request.getMessage().length());

        // T1.3 会话存在性 + 归属闸门（owner 或 admin）
        chatSessionService.ensureAccessible(request.getSessionId(),
                currentUserId, usageQuotaService.isCurrentUserAdmin());

        SseEmitter emitter = new SseEmitter(300_000L);
        reactor.core.Disposable disposable = agentService.chatStream(
                request.getMessage(), request.getSystemPrompt(), request.getModelId(), request.getAgentId(),
                request.getSessionId(), currentUserId,
                request.getKnowledgeDocumentIds(), request.getRagTopK())
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (Exception e) {
                        log.warn("SSE 发送失败（客户端断开）：{}", e.getMessage());
                    }
                },
                err -> emitter.completeWithError(err),
                emitter::complete
            );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
        return emitter;
    }

    private AgentChatResponse toResponse(AgentResult result) {
        List<AgentChatResponse.IterationDetail> iterations = result.getIterations().stream()
                .map(it -> AgentChatResponse.IterationDetail.builder()
                        .iteration(it.getIteration())
                        .thought(it.getThought())
                        .toolName(it.getToolName())
                        .toolInput(it.getToolInput())
                        .observation(it.getObservation())
                        .build())
                .collect(Collectors.toList());

        return AgentChatResponse.builder()
                .finalAnswer(result.getFinalAnswer())
                .iterationCount(result.getIterationCount())
                .toolsUsed(result.getToolsUsed())
                .iterations(iterations)
                .modelId(result.getModelId())
                .build();
    }

}
