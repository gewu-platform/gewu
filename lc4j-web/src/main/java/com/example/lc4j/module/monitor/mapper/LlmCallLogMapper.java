package com.example.lc4j.module.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.lc4j.module.monitor.dto.HourlyStatDTO;
import com.example.lc4j.module.monitor.dto.ModelUsageDTO;
import com.example.lc4j.module.monitor.entity.LlmCallLog;
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
}
