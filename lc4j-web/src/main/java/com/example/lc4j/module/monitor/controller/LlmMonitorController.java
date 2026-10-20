package com.example.lc4j.module.monitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.lc4j.common.R;
import com.example.lc4j.module.monitor.dto.CallLogQuery;
import com.example.lc4j.module.monitor.dto.ModelUsageDTO;
import com.example.lc4j.module.monitor.dto.MonitorStatsDTO;
import com.example.lc4j.module.monitor.entity.LlmCallLog;
import com.example.lc4j.module.monitor.service.LlmMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * 获取监控统计（今日）
     */
    @GetMapping("/stats")
    public R<MonitorStatsDTO> getStats() {
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
    public R<List<ModelUsageDTO>> getModelRanking() {
        return R.success(llmMonitorService.getModelRanking());
    }
}
