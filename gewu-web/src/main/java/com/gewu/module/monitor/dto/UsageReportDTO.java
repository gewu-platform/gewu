package com.gewu.module.monitor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * T6 用量报表聚合行（view=day|user|agent 三视图共用）
 * day 视图：groupKey=日期(yyyy-MM-dd)，label=null
 * user 视图：groupKey=userId，label=用户名（null 显示 "-"）
 * agent 视图：groupKey=templateName，label=null
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageReportDTO {

    /** 分组键（日期 / userId / templateName） */
    private String groupKey;

    /** 展示名（用户名等；可空） */
    private String label;

    /** 调用次数 */
    private Long callCount;

    /** 输入 tokens */
    private Long inputTokens;

    /** 输出 tokens */
    private Long outputTokens;

    /** 总 tokens */
    private Long totalTokens;

    /** 费用（元） */
    private BigDecimal totalCost;
}
