package com.example.lc4j.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 消息列表响应 DTO（对齐 java-ai-native MessageListResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageListResponseDTO {

    private List<com.example.lc4j.module.chat.entity.ChatMessage> messages;

    private Integer count;

    public static MessageListResponseDTO of(List<com.example.lc4j.module.chat.entity.ChatMessage> messages) {
        return MessageListResponseDTO.builder()
                .messages(messages)
                .count(messages != null ? messages.size() : 0)
                .build();
    }
}
