package com.example.lc4j.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话列表响应 DTO（对齐 java-ai-native SessionListResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListResponseDTO {

    private List<com.example.lc4j.module.chat.entity.ChatSession> sessions;

    private Integer total;

    private Integer page;

    private Integer size;

    public static SessionListResponseDTO of(List<com.example.lc4j.module.chat.entity.ChatSession> sessions,
                                            int total, int page, int size) {
        return SessionListResponseDTO.builder()
                .sessions(sessions).total(total).page(page).size(size).build();
    }
}
