package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Token 统计响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenStatsResponse {

    private String sessionId;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    private BigDecimal cost;

    public static TokenStatsResponse of(String sessionId, int inputTokens, int outputTokens,
                                           int totalTokens, BigDecimal cost) {
        return TokenStatsResponse.builder()
                .sessionId(sessionId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .cost(cost)
                .build();
    }
}
