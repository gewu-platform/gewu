package com.gewu.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * U4 Redis 薄封装（缓存/限流/计数统一入口）
 *
 * <p>设计原则：Redis 挂 ≠ 应用挂。</p>
 * <ul>
 *   <li>所有操作 try-catch，异常时打 WARN 并进入 60s 失败冷却（冷却期内直接走降级路径）</li>
 *   <li>读路径失败返回 null/-1，由调用方回落 DB/内存原逻辑</li>
 *   <li>写路径 DB 先行双写，Redis 只做加速与快速拒绝</li>
 *   <li>{@code gewu.redis.enabled=false} 时全链路直通原 DB/内存路径</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    /** U4：每日配额计数键前缀（UsageQuotaService 与 LlmMonitorService 共用） */
    public static final String KEY_QUOTA_PREFIX = "gewu:quota:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** U4：Redis 总开关（gewu.redis.enabled，默认 true；false 全走原路径） */
    @Value("${gewu.redis.enabled:true}")
    private boolean enabled;

    /** 连续失败冷却：失败后 60s 内不再尝试 Redis，直接降级 */
    private static final long COOLDOWN_MS = 60_000L;
    private volatile long downUntil = 0L;

    private boolean available() {
        return enabled && System.currentTimeMillis() >= downUntil;
    }

    private <T> T degrade(String op, String key, Exception e, T fallback) {
        downUntil = System.currentTimeMillis() + COOLDOWN_MS;
        log.warn("[U4] Redis {} 失败（key={}），{}s 内降级直连 DB/内存：{}", op, key, COOLDOWN_MS / 1000, e.getMessage());
        return fallback;
    }

    // ==================== 字符串 KV ====================

    /** GET；null = 未命中或 Redis 不可用（调用方回落 DB） */
    public String get(String key) {
        if (!available()) return null;
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return degrade("get", key, e, null);
        }
    }

    /** SET 带 TTL；失败静默（DB 已先行） */
    public void set(String key, String value, Duration ttl) {
        if (!available()) return;
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            degrade("set", key, e, null);
        }
    }

    /** DEL；失败静默（TTL 兜底过期） */
    public void del(String key) {
        if (!available()) return;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            degrade("del", key, e, null);
        }
    }

    // ==================== ZSET 滑动窗口（登录限流） ====================

    /** ZADD 并刷新 key TTL；true=写入成功，false=Redis 不可用（调用方走内存路径） */
    public boolean zsetAdd(String key, String member, double score, Duration ttl) {
        if (!available()) return false;
        try {
            redisTemplate.opsForZSet().add(key, member, score);
            redisTemplate.expire(key, ttl);
            return true;
        } catch (Exception e) {
            return degrade("zadd", key, e, false);
        }
    }

    /** ZREMRANGEBYSCORE 清窗口外成员；-1 = Redis 不可用 */
    public long zsetRemoveRangeByScore(String key, double min, double max) {
        if (!available()) return -1;
        try {
            Long n = redisTemplate.opsForZSet().removeRangeByScore(key, min, max);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return degrade("zremrangebyscore", key, e, -1L);
        }
    }

    /** ZCARD；-1 = Redis 不可用（调用方回落内存计数） */
    public long zsetCard(String key) {
        if (!available()) return -1;
        try {
            Long n = redisTemplate.opsForZSet().zCard(key);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return degrade("zcard", key, e, -1L);
        }
    }

    // ==================== 计数器（每日配额） ====================

    /**
     * INCRBY；首次创建时设置 TTL。返回 -1 = Redis 不可用（调用方跳过计数）。
     */
    public long incrBy(String key, long delta, Duration ttlOnFirst) {
        if (!available()) return -1;
        try {
            Long v = redisTemplate.opsForValue().increment(key, delta);
            if (v != null && v == delta && ttlOnFirst != null) {
                redisTemplate.expire(key, ttlOnFirst);
            }
            return v == null ? -1 : v;
        } catch (Exception e) {
            return degrade("incrby", key, e, -1L);
        }
    }

    // ==================== JSON 对象（用户设置缓存） ====================

    /** 读 JSON 对象缓存；null = 未命中或不可用 */
    public Map<String, Object> getJson(String key) {
        String raw = get(key);
        if (raw == null) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 损坏缓存即删除
            del(key);
            return null;
        }
    }

    /** 写 JSON 对象缓存 */
    public void setJson(String key, Map<String, Object> value, Duration ttl) {
        try {
            set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // 序列化失败不影响主流程
        }
    }
}
