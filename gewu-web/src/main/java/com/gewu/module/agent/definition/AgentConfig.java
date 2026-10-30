package com.gewu.module.agent.definition;

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

    /**
     * 工具调用审批策略（P8b 审批管控，页面可配置）：
     * null = 继承工具级默认（search 等需审批，只读工具放行）
     * true = 该 Agent 所有工具调用都需审批
     * false = 该 Agent 免审批（仅限可信 Agent，页面标注风险提示）
     */
    @Builder.Default
    private Boolean requireApproval = null;

    /** T5 知识库绑定：定义级默认检索文档 ID 列表（chat 请求未指定时回退使用） */
    private java.util.List<Long> knowledgeDocumentIds;

    /** T5 定义级默认检索条数（1-10，空 = 编排层默认 4） */
    private Integer ragTopK;

    // ==================== V2.0.0 R1 多 Agent 主从编排 ====================

    /** 角色：未设置=普通 Agent；master=可派发子 Agent */
    private String role;

    /** master 可调度的子 Agent id 清单（null/空=未配置派发目标） */
    private java.util.List<String> allowedWorkers;

    // ==================== V2.0.0 R2 公共 Skill 库 ====================

    /**
     * 启用的公共 Skill id 清单（来自默认目录 .agents/skills 的目录名）。
     * 对话构建 system prompt 时以「提示词包」形态注入指令段；
     * 内容仅作提示词注入，不执行其中任何指令。
     */
    private java.util.List<String> skillIds;
}
