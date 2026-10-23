package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Token 统计响应 DTO（对齐 java-ai-native TokenStatsResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenStatsResponseDTO {

    private String sessionId;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    private BigDecimal cost;

    public static TokenStatsResponseDTO of(String sessionId, int inputTokens, int outputTokens,
                                           int totalTokens, BigDecimal cost) {
        return TokenStatsResponseDTO.builder()
                .sessionId(sessionId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .cost(cost)
                .build();
    }
}
