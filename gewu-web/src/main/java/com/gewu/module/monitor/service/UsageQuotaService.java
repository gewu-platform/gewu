package com.gewu.module.monitor.service;

import com.gewu.module.monitor.mapper.LlmCallLogMapper;
import com.gewu.module.auth.entity.User;
import com.gewu.module.auth.mapper.UserMapper;
import com.gewu.infrastructure.security.AdminAccounts;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gewu.infrastructure.redis.RedisCacheService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * T6 用户级每日 token 配额（env/yml 配置，默认关闭；超限抛 BizException，不做预扣）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageQuotaService {

    private final LlmCallLogMapper llmCallLogMapper;
    private final UserMapper userMapper;
    private final RedisCacheService redisCacheService;

    /** U4：当日配额计数键（复用 RedisCacheService.KEY_QUOTA_PREFIX） */
    private static final String QUOTA_KEY_PREFIX = RedisCacheService.KEY_QUOTA_PREFIX;

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
        // U4：Redis 当日计数快路径（毫秒级拒绝）；未命中/不可用走 DB SUM
        String qkey = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now();
        String cachedUsed = redisCacheService.get(qkey);
        if (cachedUsed != null) {
            try {
                long cached = Long.parseLong(cachedUsed);
                if (cached >= dailyTokenLimit) {
                    log.warn("用户 token 配额已超限(Redis 快路径) userId={}, used={}, limit={}", userId, cached, dailyTokenLimit);
                    throw new com.gewu.common.BizException(429, "今日 token 配额已用完（" + cached + "/" + dailyTokenLimit + "），请明日再试或联系管理员调整");
                }
                return;
            } catch (NumberFormatException nfe) {
                redisCacheService.del(qkey);
            }
        }
        Long used = llmCallLogMapper.sumUserTokensOfDay(userId, LocalDate.now().atStartOfDay());
        long usedTokens = used != null ? used : 0;
        // U4：回填当日计数缓存（TTL 至当日 24 点）
        try {
            Duration ttl = Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redisCacheService.set(qkey, String.valueOf(usedTokens), ttl);
            }
        } catch (Exception ignored) {
        }
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

    /**
     * T1.3：当前登录账号是否管理员（AdminAccounts 名单制，与 ROLE_ADMIN 授予同源）
     */
    public boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Object principal = auth.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails ud) {
            username = ud.getUsername();
        } else if (principal instanceof String s && !"anonymousUser".equals(s)) {
            username = s;
        }
        return AdminAccounts.isAdmin(username);
    }
}

