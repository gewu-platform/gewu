package com.example.lc4j.module.agent.definition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 配置（对齐 java-ai-native AgentConfig，精简）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    /** 使用的模型 ID（空 = 当前激活模型） */
    private String modelId;

    /** 最大迭代次数 */
    @Builder.Default
    private Integer maxIterations = 10;

    /** 温度参数 */
    @Builder.Default
    private Double temperature = 0.7;

    /** 最大 token 数 */
    @Builder.Default
    private Integer maxTokens = 4096;
}
