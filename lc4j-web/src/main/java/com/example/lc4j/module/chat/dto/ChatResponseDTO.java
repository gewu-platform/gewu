package com.example.lc4j.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应 DTO（对齐 java-ai-native ChatResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {

    private String sessionId;

    private String response;

    private String model;

    private Boolean multimodal;

    public static ChatResponseDTO success(String sessionId, String response, String model, boolean multimodal) {
        return ChatResponseDTO.builder()
                .sessionId(sessionId)
                .response(response)
                .model(model)
                .multimodal(multimodal)
                .build();
    }
}
