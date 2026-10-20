package com.example.lc4j.module.monitor.dto;

import lombok.Data;

/**
 * 小时级统计 DTO（对齐 java-ai-native HourlyStatDTO）
 */
@Data
public class HourlyStatDTO {

    /**
     * 小时 (0-23)
     */
    private Integer hour;

    /**
     * 调用次数
     */
    private Long callCount;

    /**
     * 平均延迟 ms
     */
    private Double avgLatency;
}
