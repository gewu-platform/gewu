package com.example.lc4j.module.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 事件流 DTO（对齐 java-ai-native AgentStreamResponse）
 *
 * 事件类型：iteration/thought/action/tool_result/approval_required/done/error
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamResponse {

    /** 事件类型 */
    private String eventType;

    /** 文本内容（thought/done/error） */
    private String content;

    /** 迭代轮次（1 起） */
    private Integer iteration;

    /** 工具名（action/tool_result/approval_required） */
    private String toolName;

    /** 工具参数 JSON（action/approval_required） */
    private String toolInput;

    /** 审批调用 ID（approval_required） */
    private String toolCallId;

    /** 模型 ID（done） */
    private String modelId;

    /** 已用工具列表（done） */
    private List<String> toolsUsed;

    /** RAG 检索命中数（rag：>0 命中 / 0 无命中 / -1 检索失败，P8b） */
    private Integer ragHits;

    /** 累计输入 token（done，P8a 多轮合计） */
    private Integer inputTokens;

    /** 累计输出 token（done，P8a 多轮合计） */
    private Integer outputTokens;

    public static AgentStreamResponse iteration(int iteration) {
        return AgentStreamResponse.builder().eventType("iteration").iteration(iteration).build();
    }

    public static AgentStreamResponse thought(String thought, int iteration) {
        return AgentStreamResponse.builder().eventType("thought").content(thought).iteration(iteration).build();
    }

    public static AgentStreamResponse action(String toolName, String toolInput, int iteration) {
        return AgentStreamResponse.builder().eventType("action")
                .toolName(toolName).toolInput(toolInput).iteration(iteration).build();
    }

    public static AgentStreamResponse toolResult(String toolName, String observation, int iteration) {
        return AgentStreamResponse.builder().eventType("tool_result")
                .toolName(toolName).content(observation).iteration(iteration).build();
    }

    /** 增量文本（逐 token 流式推送，前端累计拼接） */
    public static AgentStreamResponse delta(String content, int iteration) {
        return AgentStreamResponse.builder().eventType("delta")
                .content(content).iteration(iteration).build();
    }

    public static AgentStreamResponse approvalRequired(String toolCallId, String toolName, String toolInput) {
        return AgentStreamResponse.builder().eventType("approval_required")
                .toolCallId(toolCallId).toolName(toolName).toolInput(toolInput).build();
    }

    public static AgentStreamResponse done(String finalAnswer, int iterationCount,
                                           List<String> toolsUsed, String modelId) {
        return AgentStreamResponse.builder().eventType("done")
                .content(finalAnswer).iteration(iterationCount)
                .toolsUsed(toolsUsed).modelId(modelId).build();
    }

    /** RAG 注入结果事件（P8b） */
    public static AgentStreamResponse rag(int hits) {
        return AgentStreamResponse.builder().eventType("rag").ragHits(hits).build();
    }

    /** done 带 token 统计（P8a 会话模式多轮合计） */
    public static AgentStreamResponse done(String finalAnswer, int iterationCount,
                                           List<String> toolsUsed, String modelId,
                                           int inputTokens, int outputTokens) {
        return AgentStreamResponse.builder().eventType("done")
                .content(finalAnswer).iteration(iterationCount)
                .toolsUsed(toolsUsed).modelId(modelId)
                .inputTokens(inputTokens).outputTokens(outputTokens).build();
    }

    /** 心跳事件（审批等待期保活连接） */
    public static AgentStreamResponse ping() {
        return AgentStreamResponse.builder().eventType("ping").build();
    }

    public static AgentStreamResponse error(String message) {
        return AgentStreamResponse.builder().eventType("error").content(message).build();
    }
}
