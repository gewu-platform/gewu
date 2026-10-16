package com.example.lc4j.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统提示词配置实体（对齐 java-ai-native SystemPromptConfig）
 *
 * 对应表：system_prompt_config（PostgreSQL）
 */
@Data
@TableName("system_prompt_config")
public class SystemPromptConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 配置 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置名称（角色名）
     */
    private String name;

    /**
     * 角色描述（系统提示词模板）
     */
    private String roleDescription;

    /**
     * 绑定模型
     */
    private String model;

    /**
     * 是否默认：0-否，1-是
     */
    private Integer isDefault;

    /**
     * 是否删除：0-未删除，1-已删除（软删除）
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
