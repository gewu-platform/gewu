package com.example.lc4j.module.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 定义持久化实体（对齐 java-ai-native AgentDefinitionEntity）
 *
 * tool_ids/config/metadata 用 TEXT 存 JSON 字符串（native 用 JSONB + String 直映，等价简化）。
 * enabled 用 SMALLINT（PG Boolean→smallint 走 init.sql 的 IMPLICIT CAST）。
 */
@Data
@TableName("agent_definition")
public class AgentDefinitionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Agent 唯一标识（业务 ID，非自增） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** Agent 名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 头像 URL */
    private String avatar;

    /** 版本 */
    private String version;

    /** 类型：react */
    private String type;

    /** 系统提示词 */
    private String systemPrompt;

    /** 开场白 */
    private String greeting;

    /** 绑定的工具 ID 列表（JSON 数组字符串） */
    private String toolIds;

    /** Agent 配置（JSON 字符串） */
    private String config;

    /** 是否启用（SMALLINT 1/0） */
    private Integer enabled;

    /** 创建者 ID */
    private Long creatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
