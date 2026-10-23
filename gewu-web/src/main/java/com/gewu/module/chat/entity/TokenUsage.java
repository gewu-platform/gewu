package com.gewu.module.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Token 使用统计实体（对齐 java-ai-native TokenUsage）
 *
 * 对应表：token_usage（PostgreSQL）
 */
@Data
@TableName("token_usage")
public class TokenUsage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 消息 ID
     */
    private Long messageId;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 输入 token 数
     */
    private Integer inputTokens;

    /**
     * 输出 token 数
     */
    private Integer outputTokens;

    /**
     * 总 token 数
     */
    private Integer totalTokens;

    /**
     * 成本（元）
     */
    private BigDecimal cost;

    /**
     * 缓存 token 数
     */
    private Integer cachedTokens;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
