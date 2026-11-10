package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 消息列表响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageListResponse {

    private List<com.gewu.module.chat.entity.ChatMessage> messages;

    private Integer count;

    public static MessageListResponse of(List<com.gewu.module.chat.entity.ChatMessage> messages) {
        return MessageListResponse.builder()
                .messages(messages)
                .count(messages != null ? messages.size() : 0)
                .build();
    }
}
