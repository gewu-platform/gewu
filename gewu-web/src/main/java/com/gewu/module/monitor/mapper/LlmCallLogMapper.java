package com.gewu.module.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.monitor.dto.HourlyStatDTO;
import com.gewu.module.monitor.dto.UsageReportDTO;
import com.gewu.module.monitor.dto.ModelUsageDTO;
import com.gewu.module.monitor.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 调用日志 Mapper（对齐 java-ai-native LlmCallLogMapper）
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    /**
     * 今日调用次数
     */
    @Select("SELECT COUNT(*) FROM llm_call_log WHERE create_time >= #{startOfDay}")
    Long countTodayCalls(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 今日 token 总消耗
     */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM llm_call_log WHERE create_time >= #{startOfDay}")
    Long sumTodayTokens(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 今日费用
     */
    @Select("SELECT COALESCE(SUM(cost), 0) FROM llm_call_log WHERE create_time >= #{startOfDay}")
    BigDecimal sumTodayCost(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 今日平均延迟
     */
    @Select("SELECT COALESCE(AVG(latency), 0) FROM llm_call_log WHERE create_time >= #{startOfDay}")
    Double avgTodayLatency(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 今日错误率
     */
    @Select("SELECT COALESCE(SUM(CASE WHEN status = 'error' OR status = 'timeout' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 0) "
            + "FROM llm_call_log WHERE create_time >= #{startOfDay}")
    Double todayErrorRate(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 模型使用排行（今日）
     */
    @Select("SELECT model_name AS modelName, COUNT(*) AS callCount, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(cost), 0) AS totalCost "
            + "FROM llm_call_log WHERE create_time >= #{startOfDay} "
            + "GROUP BY model_name ORDER BY callCount DESC")
    List<ModelUsageDTO> modelUsageToday(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * 24小时调用分布（今日）
     */
    @Select("SELECT CAST(EXTRACT(HOUR FROM create_time) AS INTEGER) AS \"hour\", COUNT(*) AS callCount, "
            + "COALESCE(AVG(latency), 0) AS avgLatency "
            + "FROM llm_call_log WHERE create_time >= #{startOfDay} "
            + "GROUP BY EXTRACT(HOUR FROM create_time) ORDER BY \"hour\"")
    List<HourlyStatDTO> hourlyDistributionToday(@Param("startOfDay") LocalDateTime startOfDay);

    /**
     * T6 用量报表：按天聚合（近 N 天）
     */
    @Select("SELECT TO_CHAR(create_time, 'YYYY-MM-DD') AS groupKey, COUNT(*) AS callCount, "
            + "COALESCE(SUM(prompt_tokens), 0) AS inputTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS outputTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(cost), 0) AS totalCost "
            + "FROM llm_call_log WHERE create_time >= #{start} AND create_time < #{end} "
            + "GROUP BY TO_CHAR(create_time, 'YYYY-MM-DD') ORDER BY groupKey")
    List<UsageReportDTO> usageByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * T6 用量报表：按用户聚合（llm_call_log.user_id，null 归为未知）
     */
    @Select("SELECT COALESCE(CAST(user_id AS VARCHAR), 'unknown') AS groupKey, "
            + "MAX(u.username) AS label, COUNT(*) AS callCount, "
            + "COALESCE(SUM(l.prompt_tokens), 0) AS inputTokens, "
            + "COALESCE(SUM(l.completion_tokens), 0) AS outputTokens, "
            + "COALESCE(SUM(l.total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(l.cost), 0) AS totalCost "
            + "FROM llm_call_log l LEFT JOIN sys_user u ON u.id = l.user_id "
            + "WHERE l.create_time >= #{start} AND l.create_time < #{end} "
            + "GROUP BY COALESCE(CAST(user_id AS VARCHAR), 'unknown') ORDER BY totalTokens DESC")
    List<UsageReportDTO> usageByUser(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * T6 用量报表：按 Agent 聚合（template_name：agent:{agentId} / agent-smart / 其他为普通 chat 模板）
     */
    @Select("SELECT template_name AS groupKey, NULL AS label, COUNT(*) AS callCount, "
            + "COALESCE(SUM(prompt_tokens), 0) AS inputTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS outputTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(cost), 0) AS totalCost "
            + "FROM llm_call_log WHERE create_time >= #{start} AND create_time < #{end} "
            + "GROUP BY template_name ORDER BY totalTokens DESC")
    List<UsageReportDTO> usageByAgent(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * T6 配额：用户当日已用 tokens
     */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM llm_call_log "
            + "WHERE user_id = #{userId} AND create_time >= #{startOfDay}")
    Long sumUserTokensOfDay(@Param("userId") Long userId, @Param("startOfDay") LocalDateTime startOfDay);
}
