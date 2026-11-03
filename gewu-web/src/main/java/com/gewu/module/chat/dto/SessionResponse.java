package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话响应 DTO（对齐 java-ai-native SessionResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private String sessionId;

    private String mode;

    private String model;

    /** 会话是否为复用已有会话 */
    private Boolean reused;

    public static SessionResponse of(String sessionId, String mode) {
        return SessionResponse.builder().sessionId(sessionId).mode(mode).build();
    }

    public static SessionResponse of(String sessionId, String mode, String model, boolean reused) {
        return SessionResponse.builder()
                .sessionId(sessionId).mode(mode).model(model).reused(reused).build();
    }
}
