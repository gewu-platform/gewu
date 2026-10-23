package com.gewu.infrastructure.security;

import lombok.RequiredArgsConstructor;
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
        Deque<Long> attempts = attemptMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        attempts.addLast(now);
        evictExpired(attempts, now);
        log.warn("登录失败已记录: key={}, 窗口内失败次数={}", key, attempts.size());
    }

    /**
     * 登录成功后重置计数
     */
    public void reset(String key) {
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
