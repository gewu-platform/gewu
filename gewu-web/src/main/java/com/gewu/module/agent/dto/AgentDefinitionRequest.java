package com.gewu.module.agent.dto;

import com.gewu.module.agent.definition.AgentDefinition;
import lombok.Data;

import java.util.List;

/**
 * Agent 定义管理请求 DTO（对齐 java-ai-native AgentDefinitionRequest）
 */
@Data
public class AgentDefinitionRequest {

    /** Agent ID（更新时必传） */
    private String id;

    /** 名称（必填） */
    private String name;

    /** 描述 */
    private String description;

    /** 头像 URL */
    private String avatar;

    /** 系统提示词 */
    private String systemPrompt;

    /** 开场白 */
    private String greeting;

    /** 绑定工具 ID 列表 */
    private List<String> toolIds;

    /** 配置（modelId/maxIterations/temperature/maxTokens） */
    private AgentDefinition requestConfigHolder;

    /** modelId（平铺，简化前端） */
    private String modelId;

    /** 最大迭代次数 */
    private Integer maxIterations;

    /** 温度 */
    private Double temperature;

    /** 最大 token */
    private Integer maxTokens;

    /** 是否启用 */
    private Boolean enabled;

    /** P8b 审批管控：null=继承工具级默认 / true=全部工具需审批 / false=免审批 */
    private Boolean requireApproval;

    /** T5 知识库绑定：默认检索文档 ID 列表 */
    private java.util.List<Long> knowledgeDocumentIds;

    /** T5 默认检索条数 */
    private Integer ragTopK;

    // ==================== V2.0.0 R1 多 Agent 主从编排 ====================

    /** 角色：未设置=普通 Agent；master=可派发子 Agent */
    private String role;

    /** master 允许派发的子 Agent id 清单 */
    private java.util.List<String> allowedWorkers;

    // ==================== V2.0.0 R2 公共 Skill 库 ====================

    /** 启用的公共 Skill id 清单（提示词包注入） */
    private java.util.List<String> skillIds;
}
