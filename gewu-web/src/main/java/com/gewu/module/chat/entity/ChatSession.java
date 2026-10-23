package com.gewu.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天会话实体（对齐 java-ai-native ChatSession）
 *
 * 对应表：chat_session（PostgreSQL）
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
     * 用户 ID（关联 sys_user，多用户场景使用）
     */
    private Long userId;

    /**
     * 模式 - text:文本模式 / multimodal:多模态模式
     */
    private String mode;

    /**
     * 使用的模型 - 如 volc-lite-endpoint
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
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;

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
