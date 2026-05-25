package com.example.lc4j.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 聊天消息实体
 * 
 * 对应表：chat_message
 * 用途：存储 LangChain4j 多轮对话的消息记录
 *
 * @author Matilda
 * @since 2026-05-25
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
     * 创建时间戳 - 毫秒级时间戳
     */
    private Long createdAt;
}
