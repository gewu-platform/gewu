package com.gewu.module.monitor.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 调用日志查询 DTO（对齐 java-ai-native CallLogQuery）
 */
@Data
public class CallLogQuery {

    /**
     * 页码（默认 1）
     */
    private Integer page = 1;

    /**
     * 每页大小（默认 20）
     */
    private Integer size = 20;

    /**
     * 开始日期
     */
    private LocalDate startDate;

    /**
     * 结束日期
     */
    private LocalDate endDate;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 状态：success/error/timeout
     */
    private String status;
}
