package com.example.lc4j.module.agent.service;

import com.example.lc4j.client.LangChain4jConfig;
import com.example.lc4j.module.agent.definition.AgentDefinition;
import com.example.lc4j.module.agent.dto.AgentStreamResponse;
import com.example.lc4j.module.agent.gateway.ToolApprovalGateway;
import com.example.lc4j.module.agent.registry.AgentRegistry;
import com.example.lc4j.module.agent.tools.ToolRegistry;
import com.example.lc4j.module.monitor.service.LlmMonitorService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 服务（lc4j 原生 Function Calling 实现）
 *
 * 与 native（Spring AI 文本提示 ReAct）的本质差异：
 * - native 用纯文本 Prompt 描述工具 + 正则解析 Action/Action Input
 * - lc4j 用原生 ToolSpecification 传入模型，模型返回结构化 ToolExecutionRequest
 *   （OpenAI 兼容 function calling），无解析失败风险
 *
 * 循环：LLM(带 tools) → AiMessage(toolRequests) → 审批网关 → 执行工具
 *      → ToolExecutionResultMessage 回填 → LLM → ... → AiMessage(纯文本)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final LangChain4jConfig langChain4jConfig;
    private final LlmMonitorService llmMonitorService;
    private final ToolApprovalGateway approvalGateway = new ToolApprovalGateway();

    /** 挂起中的审批决策凭证：toolCallId → future（true=批准，false=拒绝／超时） */
    private final Map<String, CompletableFuture<Boolean>> approvalDecisions = new ConcurrentHashMap<>();

    /** 审批等待：3 分钟超时兜底 */
    private static final long APPROVAL_TIMEOUT_MS = 3 * 60 * 1000;

    /** 审批等待期心跳间隔 15s（防代理断连） */
    private static final long APPROVAL_PING_INTERVAL_MS = 15 * 1000;

    /** 最大迭代（安全兜底，Agent 定义可覆盖） */
    private static final int MAX_ITERATIONS = 10;

    /** 单条工具结果最大字符（micro-compaction） */
    private static final int MAX_OBSERVATION_CHARS = 2000;

    /**
     * 初始化：注册内置工具
     */
    @PostConstruct
    public void init() {
        log.info("初始化 Agent 工具注册中心...");

        toolRegistry.register(new com.example.lc4j.module.agent.tools.CalculatorTool());
        toolRegistry.register(new com.example.lc4j.module.agent.tools.DateTimeTool());
        toolRegistry.register(new com.example.lc4j.module.agent.tools.TimeZoneTool());
        toolRegistry.register(new com.example.lc4j.module.agent.tools.SearchTool());

        log.info("Agent 工具注册完成，共 {} 个工具: {}",
                toolRegistry.getToolNames().size(), toolRegistry.getToolNames());
    }

    // ==================== 同步对话 ====================

    /**
     * Agent 对话（同步）：按 agentId 取定义，绑定其工具集跑 function calling 循环
     */
    public AgentResult chatWithAgent(String agentId, String userMessage) {
        AgentDefinition definition = agentRegistry.get(agentId);
        if (definition == null) {
            throw new IllegalArgumentException("Agent 不存在: " + agentId);
        }
        if (!Boolean.TRUE.equals(definition.getEnabled())) {
            throw new IllegalStateException("Agent 未启用: " + agentId);
        }

        String modelId = definition.getConfig() != null && definition.getConfig().getModelId() != null
                ? definition.getConfig().getModelId()
                : langChain4jConfig.getActiveModel();
        int maxIterations = definition.getConfig() != null && definition.getConfig().getMaxIterations() != null
                ? definition.getConfig().getMaxIterations() : MAX_ITERATIONS;

        List<ToolSpecification> specs = agentRegistry.getToolSpecifications(agentId);
        return executeLoop(modelId, definition.getSystemPrompt(), userMessage, specs,
                maxIterations, agentId, null);
    }

    /**
     * 简易对话（直答，不带工具）——/chat/simple
     */
    public AgentResult chatSimple(String userMessage, String systemPrompt, String modelId) {
        String targetModel = resolveModel(modelId);
        return executeLoop(targetModel, systemPrompt, userMessage, List.of(), 1, null, null);
    }

    /**
     * 智能对话（同步）：全量工具 + agent 调度能力（演示用，流式是主路径）
     */
    public AgentResult chatSmart(String userMessage, String systemPrompt, String modelId) {
        String targetModel = resolveModel(modelId);
        return executeLoop(targetModel, systemPrompt, userMessage,
                toolRegistry.getAllSpecifications(), MAX_ITERATIONS, null, null);
    }

    // ==================== 流式对话 ====================

    /**
     * 流式智能对话：事件链 iteration → thought → action → tool_result → done
     * 工具需审批时推送 approval_required 事件（含 toolCallId），审批通过后可重试
     */
    public Flux<AgentStreamResponse> chatStream(String userMessage, String systemPrompt,
                                                String modelId, String agentId) {
        String targetModel = resolveModel(modelId);

        List<ToolSpecification> specs;
        String systemPromptFinal = systemPrompt;
        String effectiveAgentId = agentId;

        if (agentId != null && !agentId.isBlank()) {
            AgentDefinition definition = agentRegistry.get(agentId);
            if (definition == null) {
                return Flux.error(new IllegalArgumentException("Agent 不存在: " + agentId));
            }
            specs = agentRegistry.getToolSpecifications(agentId);
            if (systemPromptFinal == null || systemPromptFinal.isBlank()) {
                systemPromptFinal = definition.getSystemPrompt();
            }
        } else {
            specs = toolRegistry.getAllSpecifications();
            effectiveAgentId = null;
        }

        List<ToolSpecification> finalSpecs = specs;
        String finalSystemPrompt = systemPromptFinal;
        String finalAgentId = effectiveAgentId;

        return Flux.create(sink -> {
            List<ChatMessage> messages = new ArrayList<>();
            if (finalSystemPrompt != null && !finalSystemPrompt.isBlank()) {
                messages.add(SystemMessage.from(finalSystemPrompt));
            }
            messages.add(UserMessage.from(userMessage));

            AtomicInteger iteration = new AtomicInteger(0);
            List<String> toolsUsed = new ArrayList<>();

            try {
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    if (sink.isCancelled()) {
                        return;
                    }
                    int iterNum = i + 1;
                    iteration.set(iterNum);
                    sink.next(AgentStreamResponse.iteration(iterNum));

                    long startTime = System.currentTimeMillis();
                    ChatRequest request = ChatRequest.builder()
                            .messages(messages)
                            .toolSpecifications(finalSpecs.isEmpty() ? null : finalSpecs)
                            .build();

                    // 流式调用：delta 逐 token 推送；onCompleteResponse 拿完整 AiMessage（含工具请求）继续循环
                    StringBuilder partialBuf = new StringBuilder();
                    CompletableFuture<ChatResponse> future = new CompletableFuture<>();
                    StreamingChatModel streamingModel = langChain4jConfig.getStreamingChatModel(targetModel);
                    streamingModel.chat(request, new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                partialBuf.append(partialResponse);
                                sink.next(AgentStreamResponse.delta(partialResponse, iterNum));
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            future.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            future.completeExceptionally(error);
                        }
                    });
                    ChatResponse response = future.join();
                    long latency = System.currentTimeMillis() - startTime;

                    AiMessage aiMessage = response.aiMessage();
                    int inputTokens = estimateTokens(messages);
                    int outputTokens = aiMessage.text() != null ? aiMessage.text().length() / 4 : 0;

                    llmMonitorService.asyncRecordCall(
                            null, targetModel, finalAgentId != null ? "agent:" + finalAgentId : "agent-smart",
                            inputTokens, outputTokens, latency,
                            "success", null,
                            truncate(userMessage, 200),
                            truncate(aiMessage.text(), 500), null);

                    // 无工具请求 → 最终答案（正文已由 delta 流式推送，不再重复发 thought）
                    if (!aiMessage.hasToolExecutionRequests()) {
                        String text = aiMessage.text() != null ? aiMessage.text() : "";
                        sink.next(AgentStreamResponse.done(text, iterNum, toolsUsed, targetModel));
                        sink.complete();
                        return;
                    }

                    // 思考文本：优先用模型随工具请求返回的文本；delta 已推送过则用缓冲对齐
                    String buffered = partialBuf.toString();
                    if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                        // 若 delta 流出的内容与最终文本一致，无需重复发 thought；否则补发 thought 事件
                        if (!buffered.equals(aiMessage.text())) {
                            sink.next(AgentStreamResponse.thought(aiMessage.text(), iterNum));
                        }
                    }

                    messages.add(aiMessage);

                    // 逐个执行工具请求
                    for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                        sink.next(AgentStreamResponse.action(req.name(), req.arguments(), iterNum));

                        String toolCallId = req.id() != null ? req.id() : UUID.randomUUID().toString();
                        var approval = approvalGateway.check(
                                toolCallId, req.name(),
                                toolRegistry.getDescription(req.name()),
                                parseArgs(req.arguments()), finalAgentId, null);

                        String result;
                        switch (approval.getStatus()) {
                            case AUTO_APPROVED, APPROVED -> {
                                result = toolRegistry.execute(req);
                                if (!toolsUsed.contains(req.name())) {
                                    toolsUsed.add(req.name());
                                }
                            }
                            case APPROVAL_REQUIRED -> {
                                sink.next(AgentStreamResponse.approvalRequired(
                                        toolCallId, req.name(), req.arguments()));
                                boolean approved = waitApprovalDecision(sink, toolCallId);
                                if (approved) {
                                    result = toolRegistry.execute(req);
                                    if (!toolsUsed.contains(req.name())) {
                                        toolsUsed.add(req.name());
                                    }
                                } else {
                                    result = String.format(
                                            "[审批未通过] 工具 '%s' 未获用户批准（拒绝或超时），未执行。",
                                            req.name());
                                }
                            }
                            default -> result = String.format("[审批拒绝] 工具 '%s' 被用户拒绝执行。", req.name());
                        }

                        result = microCompact(result);
                        sink.next(AgentStreamResponse.toolResult(req.name(), result, iterNum));
                        messages.add(ToolExecutionResultMessage.from(req, result));
                    }

                }

                // 达到最大迭代
                sink.next(AgentStreamResponse.done(
                        "已达最大迭代次数（" + MAX_ITERATIONS + "），工具调用中止。", iteration.get(), toolsUsed, targetModel));
                sink.complete();
            } catch (Exception e) {
                log.error("Agent 流式执行异常", e);
                sink.next(AgentStreamResponse.error("执行异常：" + e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 流式循环内挂起等待审批决策（同一 SSE 流续跑）
     * - 注册 future → 轮询等待（15s 心跳 ping 防代理断连，3 分钟超时兜底）
     * - true=批准（原地执行工具），false=拒绝/超时（回填未通过观察结果）
     */
    private boolean waitApprovalDecision(FluxSink<AgentStreamResponse> sink, String toolCallId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        approvalDecisions.put(toolCallId, future);
        try {
            long deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS;
            long nextPing = System.currentTimeMillis() + APPROVAL_PING_INTERVAL_MS;
            while (!future.isDone()) {
                if (sink.isCancelled()) {
                    future.cancel(true);
                    return false;
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.warn("[ApprovalGateway] 审批等待超时：toolCallId={}", toolCallId);
                    future.complete(false);
                    break;
                }
                if (System.currentTimeMillis() >= nextPing) {
                    sink.next(AgentStreamResponse.ping());
                    nextPing = System.currentTimeMillis() + APPROVAL_PING_INTERVAL_MS;
                }
                Thread.sleep(200);
            }
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("审批等待异常：toolCallId={}", toolCallId, e);
            return false;
        } finally {
            approvalDecisions.remove(toolCallId);
        }
    }

    /**
     * 审批决策入口（由 Controller approve/reject 调用）：完成决策凭证唤醒挂起线程
     */
    public boolean completeApprovalDecision(String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = approvalDecisions.get(toolCallId);
        if (future == null) {
            return false;
        }
        return future.complete(approved);
    }

    // ==================== 审批 ====================

    /**
     * 审批通过并执行工具
     */
    public String approveAndExecute(String toolCallId) {
        var result = approvalGateway.approve(toolCallId);
        if (result.getStatus() != ToolApprovalGateway.ApprovalStatus.APPROVED) {
            return "[审批失败] " + result.getMessage();
        }
        // 唤醒挂起的流式循环，由循环用原始请求原地执行（避免双重执行）
        boolean woke = completeApprovalDecision(toolCallId, true);
        return woke ? "已批准，Agent 继续执行" : "已批准（当前无挂起任务，重试时放行）";
    }

    public String rejectAndNotify(String toolCallId, String reason) {
        var result = approvalGateway.reject(toolCallId, reason);
        completeApprovalDecision(toolCallId, false);
        return "[审批拒绝] " + result.getMessage();
    }

    public Map<String, ToolApprovalGateway.ApprovalRequest> getPendingApprovals() {
        return approvalGateway.getPendingApprovals();
    }

    // ==================== 工具信息 ====================

    public List<Map<String, Object>> getTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolSpecification spec : toolRegistry.getAllSpecifications()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", spec.name());
            m.put("description", spec.description());
            m.put("requiresApproval", isApprovalRequired(spec.name()));
            result.add(m);
        }
        return result;
    }

    public boolean isApprovalRequired(String toolName) {
        return toolName.equals("search") || !toolRegistry.has(toolName);
    }

    // ==================== 内部：function calling 循环（同步） ====================

    private AgentResult executeLoop(String modelId, String systemPrompt, String userMessage,
                                    List<ToolSpecification> specs, int maxIterations,
                                    String agentId, String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        ChatModel model = langChain4jConfig.getChatModel(modelId);
        List<AgentIteration> iterations = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        AtomicInteger toolCallCounter = new AtomicInteger(0);

        for (int i = 0; i < Math.max(1, maxIterations); i++) {
            long startTime = System.currentTimeMillis();
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs.isEmpty() ? null : specs)
                    .build();
            ChatResponse response = model.chat(request);
            long latency = System.currentTimeMillis() - startTime;

            AiMessage aiMessage = response.aiMessage();
            llmMonitorService.asyncRecordCall(
                    sessionId, modelId, agentId != null ? "agent:" + agentId : "agent-smart",
                    estimateTokens(messages),
                    aiMessage.text() != null ? aiMessage.text().length() / 4 : 0,
                    latency, "success", null,
                    truncate(userMessage, 200), truncate(aiMessage.text(), 500), null);

            if (!aiMessage.hasToolExecutionRequests()) {
                return AgentResult.builder()
                        .finalAnswer(aiMessage.text() != null ? aiMessage.text() : "")
                        .iterations(iterations)
                        .iterationCount(i + 1)
                        .toolsUsed(toolsUsed)
                        .modelId(modelId)
                        .build();
            }

            if (aiMessage.text() != null) {
                AgentIteration it = new AgentIteration();
                it.setIteration(i + 1);
                it.setThought(aiMessage.text());
                iterations.add(it);
            }

            messages.add(aiMessage);

            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String toolCallId = req.id() != null ? req.id()
                        : "tc-" + toolCallCounter.incrementAndGet();
                var approval = approvalGateway.check(toolCallId, req.name(),
                        toolRegistry.getDescription(req.name()),
                        parseArgs(req.arguments()), agentId, sessionId);

                String result;
                switch (approval.getStatus()) {
                    case AUTO_APPROVED, APPROVED -> {
                        result = toolRegistry.execute(req);
                        if (!toolsUsed.contains(req.name())) {
                            toolsUsed.add(req.name());
                        }
                    }
                    case APPROVAL_REQUIRED -> result = String.format(
                            "[需要审批] 工具 '%s' 等待用户确认。toolCallId: %s", req.name(), toolCallId);
                    default -> result = String.format("[审批拒绝] 工具 '%s' 被用户拒绝执行。", req.name());
                }

                result = microCompact(result);
                AgentIteration it = new AgentIteration();
                it.setIteration(i + 1);
                it.setToolName(req.name());
                it.setToolInput(req.arguments());
                it.setObservation(result);
                iterations.add(it);

                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        return AgentResult.builder()
                .finalAnswer("已达最大迭代次数（" + maxIterations + "），工具调用中止。")
                .iterations(iterations)
                .iterationCount(maxIterations)
                .toolsUsed(toolsUsed)
                .modelId(modelId)
                .build();
    }

    // ==================== 辅助 ====================

    private String resolveModel(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            if (!langChain4jConfig.getModels().containsKey(modelId)) {
                throw new IllegalArgumentException("未知模型 ID: " + modelId);
            }
            return modelId;
        }
        return langChain4jConfig.getActiveModel();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        Map<String, Object> params = new HashMap<>();
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                params = new com.fasterxml.jackson.databind.ObjectMapper().readValue(argsJson, Map.class);
            } catch (Exception ignored) {
            }
        }
        return params;
    }

    private String toJsonArgs(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) {
                chars += sm.text().length();
            } else if (m instanceof UserMessage um) {
                chars += um.singleText().length();
            } else if (m instanceof AiMessage am && am.text() != null) {
                chars += am.text().length();
            } else if (m instanceof ToolExecutionResultMessage tm) {
                chars += tm.text().length();
            }
        }
        return chars / 4;
    }

    private String microCompact(String text) {
        if (text == null || text.length() <= MAX_OBSERVATION_CHARS) {
            return text;
        }
        int half = MAX_OBSERVATION_CHARS / 2;
        return text.substring(0, half) + "\n\n[... 中间内容已截断 ...]\n\n"
                + text.substring(text.length() - half);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ==================== 结果 DTO ====================

    @lombok.Builder
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentResult {
        private String finalAnswer;
        private List<AgentIteration> iterations;
        private int iterationCount;
        private List<String> toolsUsed;
        private String modelId;
    }

    @lombok.Data
    public static class AgentIteration {
        private int iteration;
        private String thought;
        private String toolName;
        private String toolInput;
        private String observation;
    }
}
