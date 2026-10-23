package com.gewu.module.prompt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Prompt 模板实体（对齐 java-ai-native PromptTemplate）
 *
 * 单表多版本：同 name 多 version，uk_name_version 唯一约束；
 * 回滚 = 目标版本 is_active=1、其余版本 is_active=0。
 */
@Data
@TableName("prompt_template")
public class PromptTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 模板名称（如 code-review）
     */
    private String name;

    /**
     * 版本号（同 name 自增，创建时自动 +1）
     */
    private Integer version;

    /**
     * System Prompt（可空，空则不带系统提示词调用）
     */
    private String systemPrompt;

    /**
     * User Prompt 模板，{variable} 占位符
     */
    private String userPromptTemplate;

    /**
     * 指定模型 ID（空则用当前激活模型）
     */
    private String model;

    /**
     * 温度（默认 0.7）
     */
    private Double temperature;

    /**
     * 最大生成 token（默认 2048）
     */
    private Integer maxTokens;

    /**
     * 模板描述
     */
    private String description;

    /**
     * 是否启用：true 启用 / false 停用
     */
    private Boolean isActive;

    /**
     * 逻辑删除标记
     */
    @TableLogic
    private Integer isDeleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
