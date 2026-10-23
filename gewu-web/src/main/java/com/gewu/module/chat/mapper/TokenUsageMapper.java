package com.gewu.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.chat.entity.TokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Token 使用统计 Mapper（对齐 java-ai-native TokenUsageMapper）
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsage> {

    /**
     * 会话级统计（input/output/total 求和 + 成本求和）
     */
    @Select("SELECT COALESCE(SUM(input_tokens),0) AS input_tokens, " +
            "COALESCE(SUM(output_tokens),0) AS output_tokens, " +
            "COALESCE(SUM(total_tokens),0) AS total_tokens, " +
            "COALESCE(SUM(cost),0) AS cost " +
            "FROM token_usage WHERE session_id = #{sessionId}")
    TokenUsage statsBySessionId(@Param("sessionId") String sessionId);
}
