package com.gewu.infrastructure.security;

import com.gewu.infrastructure.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 登录限流服务（对齐 java-ai-native 登录防暴力破解）
 *
 * <p>滑动窗口计数：按 用户名|IP 维度限制登录尝试次数，
 * 窗口内失败次数达到上限后拒绝后续尝试。
 * 内存实现（单实例部署，与 native 无 Redis 的形态一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthThrottleService {

    private final AuthThrottleProperties properties;
    private final RedisCacheService redisCacheService;

    /** U4：Redis 限流键前缀 */
    private static final String THROTTLE_KEY_PREFIX = "gewu:throttle:";

    /**
     * key = username|ip，value = 窗口内失败时间戳队列
     */
    private final ConcurrentHashMap<String, Deque<Long>> attemptMap = new ConcurrentHashMap<>();

    /**
     * 是否允许登录尝试
     */
    public boolean isAllowed(String key) {
        if (!properties.isEnabled()) {
            return true;
        }
        // U4：Redis 滑动窗口优先（多实例共享）；不可用回落本机内存
        String redisKey = THROTTLE_KEY_PREFIX + key;
        long nowMs = System.currentTimeMillis();
        long windowStartMs = nowMs - properties.getWindowSeconds() * 1000;
        long evicted = redisCacheService.zsetRemoveRangeByScore(redisKey, 0, windowStartMs);
        if (evicted >= 0) {
            long count = redisCacheService.zsetCard(redisKey);
            if (count >= 0) {
                return count < properties.getMaxAttempts();
            }
        }
        Deque<Long> attempts = attemptMap.get(key);
        if (attempts == null) {
            return true;
        }
        long windowStart = System.currentTimeMillis() - properties.getWindowSeconds() * 1000;
        int recentCount = 0;
        for (Long ts : attempts) {
            if (ts > windowStart) {
                recentCount++;
            }
        }
        return recentCount < properties.getMaxAttempts();
    }

    /**
     * 记录一次登录失败
     */
    public void recordFailure(String key) {
        if (!properties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        // U4：Redis 记录失败（TTL=窗口长度自动过期）；不可用回落本机内存
        boolean redisOk = redisCacheService.zsetAdd(THROTTLE_KEY_PREFIX + key, String.valueOf(now), now,
                Duration.ofSeconds(properties.getWindowSeconds()));
        if (redisOk) {
            return;
        }
        Deque<Long> attempts = attemptMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        attempts.addLast(now);
        evictExpired(attempts, now);
        log.warn("登录失败已记录: key={}, 窗口内失败次数={}", key, attempts.size());
    }

    /**
     * 登录成功后重置计数
     */
    public void reset(String key) {
        // U4：Redis 侧同步清理
        redisCacheService.del(THROTTLE_KEY_PREFIX + key);
        attemptMap.remove(key);
    }

    /**
     * 清理窗口外的过期记录
     */
    private void evictExpired(Deque<Long> attempts, long now) {
        long windowStart = now - properties.getWindowSeconds() * 1000;
        while (!attempts.isEmpty() && attempts.peekFirst() <= windowStart) {
            attempts.pollFirst();
        }
    }
}
