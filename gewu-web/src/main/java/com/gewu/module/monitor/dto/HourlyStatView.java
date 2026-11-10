package com.gewu.module.monitor.dto;

import lombok.Data;

/**
 * 小时级统计 DTO
 */
@Data
public class HourlyStatView {

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
