package com.gewu.module.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 对话响应 DTO（对齐 java-ai-native AgentChatResponse）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatResponse {

    /** 最终答案 */
    private String finalAnswer;

    /** 迭代轮数 */
    private Integer iterationCount;

    /** 用到的工具（去重） */
    private List<String> toolsUsed;

    /** 迭代明细 */
    private List<IterationDetail> iterations;

    /** 模型 ID */
    private String modelId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IterationDetail {
        private Integer iteration;
        private String thought;
        private String toolName;
        private String toolInput;
        private String observation;
    }
}
