package com.gewu.module.monitor.service;

import com.gewu.module.monitor.mapper.LlmCallLogMapper;
import com.gewu.module.auth.entity.User;
import com.gewu.module.auth.mapper.UserMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * T6 用户级每日 token 配额（env/yml 配置，默认关闭；超限抛 BizException，不做预扣）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageQuotaService {

    private final LlmCallLogMapper llmCallLogMapper;
    private final UserMapper userMapper;

    /** 是否启用配额：GEWU_QUOTA_ENABLED / gewu.quota.enabled，默认 false */
    @Value("${gewu.quota.enabled:false}")
    private boolean enabled;

    /** 每用户每日 token 上限：GEWU_QUOTA_DAILY_TOKEN_LIMIT / gewu.quota.daily-token-limit，默认 200000 */
    @Value("${gewu.quota.daily-token-limit:200000}")
    private long dailyTokenLimit;

    /**
     * 对话入口配额检查：超限抛 BizException（exceeded=true 供前端区分提示）
     */
    public void checkQuota(Long userId) {
        if (!enabled || userId == null) {
            return;
        }
        Long used = llmCallLogMapper.sumUserTokensOfDay(userId, LocalDate.now().atStartOfDay());
        long usedTokens = used != null ? used : 0;
        if (usedTokens >= dailyTokenLimit) {
            log.warn("用户 token 配额已超限: userId={}, used={}, limit={}", userId, usedTokens, dailyTokenLimit);
            throw new com.gewu.common.BizException(429, "今日 token 配额已用完（" + usedTokens + "/" + dailyTokenLimit + "），请明日再试或联系管理员调整");
        }
        if (log.isDebugEnabled()) {
            log.debug("配额检查通过: userId={}, used={}, limit={}", userId, usedTokens, dailyTokenLimit);
        }
    }

    /**
     * 从 SecurityContext 解析当前登录用户 id（principal 只带 username，需反查；
     * 未认证/匿名返回 null —— 调用方 checkQuota 对 null 直接放行）
     */
    public Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails ud) {
            username = ud.getUsername();
        } else if (principal instanceof String s && !"anonymousUser".equals(s)) {
            username = s;
        }
        if (username == null || username.isBlank()) {
            return null;
        }
        User user = userMapper.findByUsername(username);
        return user != null ? user.getId() : null;
    }
}

