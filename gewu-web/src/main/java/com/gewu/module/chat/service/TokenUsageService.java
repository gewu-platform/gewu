package com.gewu.module.chat.service;

import com.gewu.client.LangChain4jConfig;
import com.gewu.module.chat.entity.TokenUsage;
import com.gewu.module.chat.mapper.TokenUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Token 使用统计服务（对齐 java-ai-native TokenUsageService）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageMapper tokenUsageMapper;
    private final LangChain4jConfig langChain4jConfig;

    /**
     * 记录 Token 使用
     */
    @Transactional
    public void recordUsage(String sessionId, Long messageId, String model,
                            int inputTokens, int outputTokens, Integer cachedTokens) {
        TokenUsage usage = new TokenUsage();
        usage.setSessionId(sessionId);
        usage.setMessageId(messageId);
        usage.setModel(model);
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        usage.setTotalTokens(inputTokens + outputTokens);
        usage.setCachedTokens(cachedTokens != null ? cachedTokens : 0);
        usage.setCost(calculateCost(model, inputTokens, outputTokens));

        tokenUsageMapper.insert(usage);
        log.debug("记录 Token 使用：sessionId={}, model={}, total={}",
                sessionId, model, usage.getTotalTokens());
    }

    /**
     * 计算成本（从 LangChain4jConfig 获取模型价格配置，暂无价格配置时用默认值）
     * 默认单价对齐 native：输入 0.004 元/千 token，输出 0.012 元/千 token
     */
    private BigDecimal calculateCost(String model, int inputTokens, int outputTokens) {
        // LangChain4jConfig.ModelConfig 暂无价格字段，使用默认单价（与 native 兜底一致）
        BigDecimal inputPrice = new BigDecimal("0.004");
        BigDecimal outputPrice = new BigDecimal("0.012");

        BigDecimal inputCost = new BigDecimal(inputTokens)
                .divide(new BigDecimal(1000), 6, RoundingMode.HALF_UP)
                .multiply(inputPrice);

        BigDecimal outputCost = new BigDecimal(outputTokens)
                .divide(new BigDecimal(1000), 6, RoundingMode.HALF_UP)
                .multiply(outputPrice);

        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 获取会话的 Token 使用统计
     */
    public TokenUsage getSessionStats(String sessionId) {
        return tokenUsageMapper.statsBySessionId(sessionId);
    }
}
