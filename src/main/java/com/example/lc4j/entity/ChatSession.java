package com.example.lc4j.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 聊天会话实体
 * 
 * 对应表：chat_session
 * 用途：管理 LangChain4j 多轮对话的会话信息
 *
 * @author Matilda
 * @since 2026-05-25
 */
@Data
@TableName("chat_session")
public class ChatSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID - UUID 格式
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 用户 ID（预留字段，多用户场景使用）
     */
    private String userId;

    /**
     * 会话标题 - 如"第一个问题"
     */
    private String title;

    /**
     * 模式 - text:文本模式 / multimodal:多模态模式
     */
    private String mode;

    /**
     * 使用的模型 - 如 qwen-plus, qwen-max
     */
    private String model;

    /**
     * 系统提示词 - 角色设定和对话规则
     */
    private String systemPrompt;

    /**
     * 消息数量 - 会话中的消息总数
     */
    private Integer messageCount;

    /**
     * 输入 token 总数 - 累计输入 token 数
     */
    private Long inputTokens;

    /**
     * 输出 token 总数 - 累计输出 token 数
     */
    private Long outputTokens;

    /**
     * 创建时间戳 - 毫秒级时间戳
     */
    private Long createdAt;

    /**
     * 最后更新时间戳 - 毫秒级时间戳
     */
    private Long updatedAt;

    /**
     * 是否删除 - 0:未删除 / 1:已删除（软删除）
     */
    @TableLogic
    private Integer isDeleted;

    /**
     * 备注
     */
    private String remark;
}
