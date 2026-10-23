package com.gewu.module.monitor.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型使用统计 DTO（对齐 java-ai-native ModelUsageDTO）
 */
@Data
public class ModelUsageDTO {

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 调用次数
     */
    private Long callCount;

    /**
     * 总 token 数
     */
    private Long totalTokens;

    /**
     * 总费用
     */
    private BigDecimal totalCost;
}
