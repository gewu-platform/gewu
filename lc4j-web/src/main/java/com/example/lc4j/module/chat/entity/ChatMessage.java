package com.example.lc4j.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息实体（对齐 java-ai-native ChatMessage）
 *
 * 对应表：chat_message（PostgreSQL）
 */
@Data
@TableName("chat_message")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID - 自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID - 关联 chat_session 表
     */
    private String sessionId;

    /**
     * 用户 ID（关联 sys_user）
     */
    private Long userId;

    /**
     * 角色 - system / user / assistant / tool
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 内容类型 - text / multimodal
     */
    private String contentType;

    /**
     * 图片 URL 列表（多模态消息，JSONB 存储）- 对齐 native
     */
    private String imageUrls;

    /**
     * 使用的模型 - 响应时使用的 AI 模型
     */
    private String model;

    /**
     * 本次输入 token 数
     */
    private Integer inputTokens;

    /**
     * 本次输出 token 数
     */
    private Integer outputTokens;

    /**
     * 结束原因
     */
    private String finishReason;

    /**
     * API 请求 ID
     */
    private String requestId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
