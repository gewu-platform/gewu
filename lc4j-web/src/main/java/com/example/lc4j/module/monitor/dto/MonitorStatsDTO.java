package com.example.lc4j.module.monitor.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 监控统计 DTO（对齐 java-ai-native MonitorStatsDTO）
 */
@Data
public class MonitorStatsDTO {

    /**
     * 今日调用次数
     */
    private Long todayCalls;

    /**
     * 今日 token 消耗
     */
    private Long todayTokens;

    /**
     * 今日费用
     */
    private BigDecimal todayCost;

    /**
     * 平均延迟 ms
     */
    private Double avgLatency;

    /**
     * 错误率 %
     */
    private Double errorRate;

    /**
     * 模型使用排行
     */
    private List<ModelUsageDTO> topModels;

    /**
     * 24小时调用分布
     */
    private List<HourlyStatDTO> hourlyDistribution;
}
