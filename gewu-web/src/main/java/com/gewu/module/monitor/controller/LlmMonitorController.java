package com.gewu.module.monitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.common.R;
import com.gewu.module.monitor.dto.CallLogQuery;
import com.gewu.module.monitor.dto.ModelUsageView;
import com.gewu.module.monitor.dto.MonitorStatsView;
import com.gewu.module.monitor.dto.UsageReportView;
import com.gewu.module.monitor.entity.LlmCallLog;
import com.gewu.module.monitor.service.LlmMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用监控面板 REST API（对齐 java-ai-native LlmMonitorController）
 *
 * 端点：/api/monitor/stats、/api/monitor/logs、/api/monitor/models
 * 认证：SecurityConfig anyRequest().authenticated() 已覆盖，无需单独放行。
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class LlmMonitorController {

    private final LlmMonitorService llmMonitorService;
    private final com.gewu.module.monitor.service.UsageReportService usageReportService;

    /**
     * 获取监控统计（今日）
     */
    @GetMapping("/stats")
    public R<MonitorStatsView> getStats() {
        return R.success(llmMonitorService.getStats());
    }

    /**
     * 分页查询调用日志
     */
    @PostMapping("/logs")
    public R<Map<String, Object>> queryLogs(@RequestBody CallLogQuery query) {
        Page<LlmCallLog> page = llmMonitorService.queryLogs(query);
        Map<String, Object> result = new HashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("page", page.getCurrent());
        result.put("size", page.getSize());
        return R.success(result);
    }

    /**
     * 模型使用排行（今日）
     */
    @GetMapping("/models")
    public R<List<ModelUsageView>> getModelRanking() {
        return R.success(llmMonitorService.getModelRanking());
    }

    /**
     * T6 用量报表：view=day|user|agent，days 近 N 天（默认 7，上限 90）
     */
    @GetMapping("/usage/report")
    public R<java.util.List<UsageReportView>> usageReport(
            @RequestParam(defaultValue = "day") String view,
            @RequestParam(required = false) Integer days) {
        return R.success(usageReportService.report(view, days));
    }
}
