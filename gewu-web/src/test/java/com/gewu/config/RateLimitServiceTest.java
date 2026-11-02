package com.gewu.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B1：RateLimitService 单元测试（纯逻辑，无 Spring 上下文）
 *
 * <p>固化 V2.2.0 A3 语义：令牌补充、容量封顶、连发触发 429 判定（allowed=false）、
 * capacity<=0 关闭该档、总开关、key 隔离、空闲桶回收。</p>
 *
 * <p>注：令牌桶基于 System.nanoTime，测试通过「连发耗尽 + 短等待恢复」的
 * 行为断言验证算法，不直接断言内部时间值。</p>
 */
class RateLimitServiceTest {

    private RateLimitService svc;

    @BeforeEach
    void setUp() {
        svc = new RateLimitService();
        svc.setEnabled(true);
        svc.setChatCapacity(3);
        svc.setChatRefill(100.0);   // 测试用高恢复速率：100/秒
        svc.setDefaultCapacity(60);
        svc.setDefaultRefill(5.0);
        svc.setIdleEvictSeconds(600);
    }

    @Test
    void 总开关关闭时全部放行() {
        svc.setEnabled(false);
        for (int i = 0; i < 10; i++) {
            RateLimitService.Decision d = svc.tryAcquire("chat:k", true);
            assertThat(d.allowed()).isTrue();
        }
    }

    @Test
    void 容量内连发全部放行() {
        for (int i = 0; i < 3; i++) {
            RateLimitService.Decision d = svc.tryAcquire("chat:u1", true);
            assertThat(d.allowed()).isTrue();
        }
    }

    @Test
    void 超出容量后拒绝且给出重试建议() {
        for (int i = 0; i < 3; i++) {
            svc.tryAcquire("chat:u1", true);
        }
        RateLimitService.Decision d = svc.tryAcquire("chat:u1", true);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 高恢复速率下短暂等待后恢复() throws InterruptedException {
        for (int i = 0; i < 3; i++) svc.tryAcquire("chat:u1", true);
        // 恢复 100/秒 → 等 50ms 应补回 ≥5 个令牌
        Thread.sleep(50);
        RateLimitService.Decision d = svc.tryAcquire("chat:u1", true);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void 令牌不超容量封顶() throws InterruptedException {
        // 满桶后等一段再连发，放行数不应超过容量
        Thread.sleep(60);
        int granted = 0;
        for (int i = 0; i < 10; i++) {
            if (svc.tryAcquire("chat:cap", true).allowed()) granted++;
        }
        assertThat(granted).isLessThanOrEqualTo(3);
    }

    @Test
    void capacity为0时该档关闭() {
        svc.setChatCapacity(0);
        for (int i = 0; i < 5; i++) {
            assertThat(svc.tryAcquire("chat:x", true).allowed()).isTrue();
        }
    }

    @Test
    void 不同key互相隔离() {
        for (int i = 0; i < 3; i++) svc.tryAcquire("chat:a", true);
        // b 是独立桶，仍应放行
        assertThat(svc.tryAcquire("chat:b", true).allowed()).isTrue();
    }

    @Test
    void 同key跨档位独立计数() {
        // chat 档耗尽
        for (int i = 0; i < 3; i++) svc.tryAcquire("chat:u", true);
        assertThat(svc.tryAcquire("chat:u", true).allowed()).isFalse();
        // default 档独立，仍放行
        assertThat(svc.tryAcquire("def:u", false).allowed()).isTrue();
    }

    @Test
    void 空闲桶回收() {
        for (int i = 0; i < 3; i++) svc.tryAcquire("chat:idle", true);
        assertThat(svc.bucketCount()).isGreaterThan(0);
        svc.setIdleEvictSeconds(0); // 立即过期
        int removed = svc.evictIdle();
        assertThat(removed).isGreaterThan(0);
        assertThat(svc.bucketCount()).isZero();
    }

    @Test
    void thresholds快照可读() {
        assertThat(svc.thresholds()).contains("chat=3/100.0", "enabled=true");
    }
}
