package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO（对齐 java-ai-native ChatRequestDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDTO {

    /**
     * 会话 ID（为空则自动创建新会话）
     */
    private String sessionId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 是否使用多模态模式（预留，P3 先落 API 契约）
     */
    private Boolean useMultimodal;

    /**
     * 图片 URL 列表（多模态预留）
     */
    private java.util.List<String> imageUrls;

    /**
     * 系统提示词（可选）
     */
    private String systemPrompt;

    /**
     * 指定模型 ID（可选，为空用当前激活模型）
     */
    private String model;
}
