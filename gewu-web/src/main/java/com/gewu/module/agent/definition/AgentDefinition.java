package com.gewu.module.agent.definition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 定义（对齐 java-ai-native AgentDefinition）
 *
 * 内存态定义：AgentRegistry 缓存，DB 由 AgentDefinitionEntity 持久化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    /** Agent 唯一标识 */
    private String id;

    /** Agent 名称 */
    private String name;

    /** Agent 描述 */
    private String description;

    /** 头像 URL */
    private String avatar;

    /** 版本 */
    private String version;

    /** 类型：react（本项目只实现 react） */
    private String type;

    /** 系统提示词 */
    private String systemPrompt;

    /** 开场白 */
    private String greeting;

    /** 绑定的工具 ID 列表 */
    private List<String> toolIds;

    /** Agent 配置 */
    private AgentConfig config;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建者 ID */
    private Long creatorId;

    /** 创建时间（epoch ms，展示用） */
    private Long createdAt;

    /** 更新时间（epoch ms，展示用） */
    private Long updatedAt;
}
