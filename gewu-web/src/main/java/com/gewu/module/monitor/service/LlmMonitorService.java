package com.gewu.module.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.client.LangChain4jConfig;
import com.gewu.module.monitor.dto.CallLogQuery;
import com.gewu.module.monitor.dto.HourlyStatDTO;
import com.gewu.module.monitor.dto.ModelUsageDTO;
import com.gewu.module.monitor.dto.MonitorStatsDTO;
import com.gewu.module.monitor.entity.LlmCallLog;
import com.gewu.module.monitor.mapper.LlmCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用监控服务（对齐 java-ai-native LlmMonitorService）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmMonitorService {

    private final LlmCallLogMapper llmCallLogMapper;
    private final LangChain4jConfig langChain4jConfig;

    /**
     * 记录一次 LLM 调用（同步）
     */
    public void logCall(LlmCallLog logEntry) {
        llmCallLogMapper.insert(logEntry);
        log.debug("记录 LLM 调用：model={}, status={}, tokens={}, latency={}ms",
                logEntry.getModelName(), logEntry.getStatus(),
                logEntry.getTotalTokens(), logEntry.getLatency());
    }

    /**
     * 异步记录调用（不阻塞主流程；monitorExecutor 见 AsyncConfig）
     */
    @Async("monitorExecutor")
    public void asyncRecordCall(String sessionId, String modelName, String templateName,
                                int promptTokens, int completionTokens, long latency,
                                String status, String errorMessage,
                                String requestSummary, String responseSummary, Long userId) {
        try {
            LlmCallLog entry = new LlmCallLog();
            entry.setSessionId(sessionId);
            entry.setModelName(modelName);
            entry.setTemplateName(templateName);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setTotalTokens(promptTokens + completionTokens);
            entry.setLatency(latency);
            entry.setStatus(status);
            entry.setErrorMessage(errorMessage);

            // 截断摘要
            entry.setRequestSummary(truncate(requestSummary, 200));
            entry.setResponseSummary(truncate(responseSummary, 500));
            entry.setUserId(userId);

            // 估算费用
            entry.setCost(estimateCost(modelName, entry.getTotalTokens()));

            llmCallLogMapper.insert(entry);
            log.debug("异步记录 LLM 调用：model={}, tokens={}, latency={}ms",
                    modelName, entry.getTotalTokens(), latency);
        } catch (Exception e) {
            log.error("异步记录 LLM 调用失败：model={}, error={}", modelName, e.getMessage(), e);
        }
    }

    /**
     * 获取监控统计（今日）
     */
    public MonitorStatsDTO getStats() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        MonitorStatsDTO stats = new MonitorStatsDTO();
        stats.setTodayCalls(llmCallLogMapper.countTodayCalls(startOfDay));
        stats.setTodayTokens(llmCallLogMapper.sumTodayTokens(startOfDay));
        stats.setTodayCost(llmCallLogMapper.sumTodayCost(startOfDay));
        stats.setAvgLatency(llmCallLogMapper.avgTodayLatency(startOfDay));
        stats.setErrorRate(llmCallLogMapper.todayErrorRate(startOfDay));
        stats.setTopModels(llmCallLogMapper.modelUsageToday(startOfDay));
        stats.setHourlyDistribution(fillMissingHours(
                llmCallLogMapper.hourlyDistributionToday(startOfDay)));

        return stats;
    }

    /**
     * 分页查询调用日志
     */
    public Page<LlmCallLog> queryLogs(CallLogQuery query) {
        LambdaQueryWrapper<LlmCallLog> wrapper = new LambdaQueryWrapper<>();

        if (query.getStartDate() != null) {
            wrapper.ge(LlmCallLog::getCreateTime, query.getStartDate().atStartOfDay());
        }
        if (query.getEndDate() != null) {
            wrapper.le(LlmCallLog::getCreateTime, query.getEndDate().atTime(LocalTime.MAX));
        }
        if (query.getModelName() != null && !query.getModelName().isBlank()) {
            wrapper.eq(LlmCallLog::getModelName, query.getModelName());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(LlmCallLog::getStatus, query.getStatus());
        }

        wrapper.orderByDesc(LlmCallLog::getCreateTime);

        int page = query.getPage() != null && query.getPage() > 0 ? query.getPage() : 1;
        int size = query.getSize() != null && query.getSize() > 0 ? query.getSize() : 20;
        Page<LlmCallLog> p = new Page<>(page, size);
        return llmCallLogMapper.selectPage(p, wrapper);
    }

    /**
     * 模型使用排行（今日）
     */
    public List<ModelUsageDTO> getModelRanking() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return llmCallLogMapper.modelUsageToday(startOfDay);
    }

    /**
     * 估算费用（元）：模型配置里无价格字段时用默认均价
     * 本项目 ModelConfig 无 inputPrice/outputPrice（对齐学习项目简化），默认 0.008 元/千token
     */
    public BigDecimal estimateCost(String model, int totalTokens) {
        if (model == null || totalTokens <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal avgPrice = new BigDecimal("0.008");

        return BigDecimal.valueOf(totalTokens)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(avgPrice);
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /**
     * 填充缺失的小时（0-23），确保返回完整的 24 小时数据
     */
    private List<HourlyStatDTO> fillMissingHours(List<HourlyStatDTO> existing) {
        List<HourlyStatDTO> result = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            HourlyStatDTO dto = new HourlyStatDTO();
            dto.setHour(h);
            dto.setCallCount(0L);
            dto.setAvgLatency(0.0);
            result.add(dto);
        }

        if (existing != null) {
            for (HourlyStatDTO s : existing) {
                if (s.getHour() != null && s.getHour() >= 0 && s.getHour() < 24) {
                    HourlyStatDTO target = result.get(s.getHour());
                    target.setCallCount(s.getCallCount());
                    target.setAvgLatency(s.getAvgLatency());
                }
            }
        }

        return result;
    }
}
