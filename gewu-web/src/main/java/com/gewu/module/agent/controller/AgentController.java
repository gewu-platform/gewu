package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.definition.AgentConfig;
import com.gewu.module.agent.definition.AgentDefinition;
import com.gewu.module.agent.dto.AgentChatRequest;
import com.gewu.module.agent.dto.AgentChatResponse;
import com.gewu.module.agent.dto.AgentDefinitionRequest;
import com.gewu.module.agent.dto.AgentStreamResponse;
import com.gewu.module.agent.registry.AgentRegistry;
import com.gewu.module.agent.service.AgentService;
import com.gewu.module.agent.service.AgentService.AgentIteration;
import com.gewu.module.agent.service.AgentService.AgentResult;
import com.gewu.module.agent.tools.ToolRegistry;
import com.gewu.module.chat.service.ChatSessionService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent REST API（对齐 java-ai-native AgentController + AgentDefinitionController 合并形态）
 *
 * 对话：/api/agent/chat、/chat/simple、/chat/smart、/chat/stream
 * 工具：/api/agent/tools
 * 审批：/api/agent/approval/pending、/approve、/reject
 * 定义：/api/agent/definitions CRUD + /enabled + /reload + /available-tools
 *
 * 认证：SecurityConfig anyRequest().authenticated() 已覆盖。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final ChatSessionService chatSessionService;
    private final com.gewu.module.monitor.service.UsageQuotaService usageQuotaService;

    // ==================== 同步对话 ====================

    /**
     * Agent 对话（核心）：agentId 指定走定义，否则带全量工具
     */
    @PostMapping("/chat")
    public R<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return R.error(400, "消息内容不能为空");
        }
        // T6 配额闸门：超限 BizException(429)，不做预扣
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());

        AgentResult result;
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) {
            result = agentService.chatWithAgent(request.getAgentId(), request.getMessage());
        } else {
            result = agentService.chatSmart(request.getMessage(), request.getSystemPrompt(), request.getModelId());
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
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());
        AgentResult result = agentService.chatSimple(
                request.getMessage(), request.getSystemPrompt(), request.getModelId());
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
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());
        AgentResult result = agentService.chatSmart(
                request.getMessage(), request.getSystemPrompt(), request.getModelId());
        return R.success(toResponse(result));
    }

    // ==================== 流式对话 ====================

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
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());
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
                request.getSessionId(), request.getUserId(),
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

    // ==================== 会话式 Agent 流（P8a 方案 D）====================

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
        usageQuotaService.checkQuota(usageQuotaService.resolveCurrentUserId());
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("会话模式下 sessionId 不能为空");
        }
        log.info("Agent 会话流式请求：sessionId={}, agentId={}, modelId={}, messageLength={}",
                request.getSessionId(), request.getAgentId(), request.getModelId(), request.getMessage().length());

        // 会话存在性校验（对齐 ConversationService 行为）
        if (chatSessionService.getSession(request.getSessionId()) == null) {
            throw new IllegalArgumentException("会话不存在：" + request.getSessionId());
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        reactor.core.Disposable disposable = agentService.chatStream(
                request.getMessage(), request.getSystemPrompt(), request.getModelId(), request.getAgentId(),
                request.getSessionId(), request.getUserId(),
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

    // ==================== 工具 ====================

    @GetMapping("/tools")
    public R<List<Map<String, Object>>> getTools() {
        return R.success(agentService.getTools());
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> info = new HashMap<>();
        info.put("status", "UP");
        info.put("toolCount", agentService.getTools().size());
        info.put("tools", toolRegistry.getToolNames());
        info.put("engine", "langchain4j-native-function-calling");
        return R.success(info);
    }

    // ==================== 审批网关 ====================

    @GetMapping("/approval/pending")
    public R<Map<String, Object>> getPendingApprovals() {
        var pending = agentService.getPendingApprovals();
        Map<String, Object> result = new HashMap<>();
        result.put("count", pending.size());
        result.put("requests", pending);
        return R.success(result);
    }

    @PostMapping("/approval/approve")
    public R<String> approveTool(@RequestBody ApprovalRequest request) {
        if (request == null || request.getToolCallId() == null) {
            return R.error(400, "toolCallId 不能为空");
        }
        return R.success(agentService.approveAndExecute(request.getToolCallId()));
    }

    @PostMapping("/approval/reject")
    public R<String> rejectTool(@RequestBody ApprovalRequest request) {
        if (request == null || request.getToolCallId() == null) {
            return R.error(400, "toolCallId 不能为空");
        }
        return R.success(agentService.rejectAndNotify(request.getToolCallId(), request.getReason()));
    }

    // ==================== Agent 定义管理 ====================

    /**
     * 创建定义
     */
    @PostMapping("/definitions")
    public R<AgentDefinition> createDefinition(@RequestBody AgentDefinitionRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            return R.error(400, "Agent 名称不能为空");
        }
        AgentDefinition definition = AgentDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(req.getName())
                .description(req.getDescription())
                .avatar(req.getAvatar())
                .systemPrompt(req.getSystemPrompt())
                .greeting(req.getGreeting())
                .toolIds(req.getToolIds())
                .config(AgentConfig.builder()
                        .modelId(req.getModelId())
                        .maxIterations(req.getMaxIterations())
                        .temperature(req.getTemperature())
                        .maxTokens(req.getMaxTokens())
                        .requireApproval(req.getRequireApproval())
                        .knowledgeDocumentIds(req.getKnowledgeDocumentIds())
                        .ragTopK(req.getRagTopK())
                        .build())
                .enabled(req.getEnabled() != null ? req.getEnabled() : true)
                .build();
        return R.success(agentRegistry.register(definition));
    }

    /**
     * 更新定义
     */
    @PutMapping("/definitions")
    public R<AgentDefinition> updateDefinition(@RequestBody AgentDefinitionRequest req) {
        if (req.getId() == null || agentRegistry.get(req.getId()) == null) {
            return R.error(400, "Agent 不存在: " + req.getId());
        }
        AgentDefinition existing = agentRegistry.get(req.getId());
        AgentDefinition definition = AgentDefinition.builder()
                .id(req.getId())
                .name(req.getName() != null ? req.getName() : existing.getName())
                .description(req.getDescription())
                .avatar(req.getAvatar())
                .systemPrompt(req.getSystemPrompt())
                .greeting(req.getGreeting())
                .toolIds(req.getToolIds())
                .config(AgentConfig.builder()
                        .modelId(req.getModelId())
                        .maxIterations(req.getMaxIterations())
                        .temperature(req.getTemperature())
                        .maxTokens(req.getMaxTokens())
                        // P8b 三态语义：前端表单始终显式传 requireApproval，null = 清除回「继承默认」
                        .requireApproval(req.getRequireApproval())
                        .knowledgeDocumentIds(req.getKnowledgeDocumentIds())
                        .ragTopK(req.getRagTopK())
                        .build())
                .enabled(req.getEnabled() != null ? req.getEnabled() : existing.getEnabled())
                .creatorId(existing.getCreatorId())
                .build();
        return R.success(agentRegistry.update(definition));
    }

    /**
     * 删除定义
     */
    @DeleteMapping("/definitions/{id}")
    public R<Void> deleteDefinition(@PathVariable String id) {
        if (agentRegistry.get(id) == null) {
            return R.error(400, "Agent 不存在: " + id);
        }
        agentRegistry.delete(id);
        return R.success();
    }

    /**
     * 定义列表（含禁用）
     */
    @GetMapping("/definitions")
    public R<List<AgentDefinition>> listDefinitions() {
        return R.success(agentRegistry.listAll());
    }

    /**
     * 已启用定义列表（对话选择用）
     */
    @GetMapping("/definitions/enabled-list")
    public R<List<AgentDefinition>> listEnabledDefinitions() {
        return R.success(agentRegistry.listEnabled());
    }

    /**
     * 启用/禁用
     */
    @PutMapping("/definitions/{id}/enabled")
    public R<Void> setEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        if (agentRegistry.get(id) == null) {
            return R.error(400, "Agent 不存在: " + id);
        }
        agentRegistry.setEnabled(id, enabled);
        return R.success();
    }

    /**
     * 重载缓存
     */
    @PostMapping("/definitions/reload")
    public R<Map<String, Object>> reload() {
        agentRegistry.reload();
        Map<String, Object> result = new HashMap<>();
        result.put("loaded", agentRegistry.listEnabled().size());
        return R.success(result);
    }

    /**
     * 可用工具清单（定义绑定用）
     */
    @GetMapping("/available-tools")
    public R<List<Map<String, Object>>> availableTools() {
        return R.success(agentService.getTools());
    }

    // ==================== 私有 ====================

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

    @lombok.Data
    public static class ApprovalRequest {
        private String toolCallId;
        private String reason;
    }
}
