package com.gewu.infrastructure.ratelimit;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.2.0 A3/T3.1：令牌桶限流（进程内实现，无外部依赖）
 *
 * <p>与 {@code UsageQuotaService} 分层：配额管「每日总量」（成本上限），
 * 本服务管「速率」（突发刷接口）。调用顺序上先速率后总量——
 * 被限流拒绝的请求不会消耗配额。</p>
 *
 * <p>为何用进程内令牌桶而非 Redis：本项目 Redis 是可选加速层（不可达自动降级），
 * 限流若强依赖 Redis 则 Redis 一挂就失去保护。代价是多实例部署时各实例独立计数，
 * 全局精确限流需换 Redis 实现（已记入 ROADMAP V2.5.0）。</p>
 */
@Slf4j
@Setter
@Component
public class RateLimitService {

    /** 单个令牌桶状态（按 key 独立） */
    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** 总开关：GEWU_RATELIMIT_ENABLED / gewu.ratelimit.enabled，默认 true */
    @Value("${gewu.ratelimit.enabled:true}")
    private boolean enabled;

    /** 对话类接口：桶容量（突发上限） */
    @Value("${gewu.ratelimit.chat.capacity:10}")
    private int chatCapacity;

    /** 对话类接口：每秒补充令牌数（0.5 ≈ 每分钟 30 次） */
    @Value("${gewu.ratelimit.chat.refill-per-second:0.5}")
    private double chatRefill;

    /** 其余受限接口：桶容量 */
    @Value("${gewu.ratelimit.default.capacity:60}")
    private int defaultCapacity;

    /** 其余受限接口：每秒补充 */
    @Value("${gewu.ratelimit.default.refill-per-second:5}")
    private double defaultRefill;

    /** 空闲桶回收阈值（秒），防止 key 无限增长 */
    @Value("${gewu.ratelimit.idle-evict-seconds:600}")
    private long idleEvictSeconds;

    /** 限流决策结果 */
    public record Decision(boolean allowed, long retryAfterSeconds, long remaining) {
    }

    /**
     * 尝试取一个令牌。
     *
     * @param key       限流键（建议 userId 或 IP，含作用域前缀）
     * @param chatScope true=对话类接口（更严阈值）
     */
    public Decision tryAcquire(String key, boolean chatScope) {
        if (!enabled) {
            return new Decision(true, 0, -1);
        }
        int cap = chatScope ? chatCapacity : defaultCapacity;
        double refill = chatScope ? chatRefill : defaultRefill;
        if (cap <= 0) {
            // 容量 <= 0 视为关闭该档（便于临时放行，无需重启改开关）
            return new Decision(true, 0, -1);
        }

        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(cap, now));

        synchronized (b) {
            double elapsedSec = (now - b.lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSec > 0) {
                b.tokens = Math.min(cap, b.tokens + elapsedSec * refill);
                b.lastRefillNanos = now;
            }
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return new Decision(true, 0, (long) Math.floor(b.tokens));
            }
            double need = 1.0 - b.tokens;
            long waitSec = refill > 0 ? (long) Math.ceil(need / refill) : 60L;
            return new Decision(false, Math.max(1, waitSec), 0);
        }
    }

    /** 回收空闲桶 */
    public int evictIdle() {
        long now = System.nanoTime();
        long threshold = idleEvictSeconds * 1_000_000_000L;
        int removed = 0;
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            Bucket b = e.getValue();
            synchronized (b) {
                if (now - b.lastRefillNanos > threshold) {
                    buckets.remove(e.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 定期回收（5 分钟一轮） */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void scheduledEvict() {
        int n = evictIdle();
        if (n > 0) {
            log.info("[RateLimit] 空闲桶回收 {} 个，当前 {} 个", n, buckets.size());
        }
    }

    public int bucketCount() {
        return buckets.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 当前阈值快照（排障用） */
    public String thresholds() {
        return String.format("chat=%d/%.2f per sec, default=%d/%.2f per sec, enabled=%s",
                chatCapacity, chatRefill, defaultCapacity, defaultRefill, enabled);
    }
}
