package com.gewu.module.monitor.service;

import com.gewu.module.monitor.dto.UsageReportDTO;
import com.gewu.module.monitor.mapper.LlmCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * T6 用量报表服务：day|user|agent 三视图（数据源 llm_call_log 单表聚合）
 */
@Service
@RequiredArgsConstructor
public class UsageReportService {

    private final LlmCallLogMapper llmCallLogMapper;

    /**
     * @param view  day|user|agent
     * @param days  近 N 天（默认 7，上限 90）
     */
    public List<UsageReportDTO> report(String view, Integer days) {
        int n = (days == null || days <= 0) ? 7 : Math.min(days, 90);
        LocalDate start = LocalDate.now().minusDays(n - 1);
        var startTs = start.atStartOfDay();
        var endTs = LocalDate.now().plusDays(1).atStartOfDay();

        return switch (view == null ? "day" : view) {
            case "user" -> llmCallLogMapper.usageByUser(startTs, endTs);
            case "agent" -> llmCallLogMapper.usageByAgent(startTs, endTs);
            default -> llmCallLogMapper.usageByDay(startTs, endTs);
        };
    }
}
