package com.gewu.module.agent.schedule;

import com.gewu.module.agent.service.AgentExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * V1.1.0 C2/T2.4：审批挂起态过期清理
 *
 * 运行时审批等待 3 分钟超时；DB 侧扫描阈值放宽到 30 分钟防误杀（重启恢复窗口内的挂起项仍有价值）。
 * 每 10 分钟扫一轮 waiting_approval，超阈值行标记 expired，不留僵尸行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointScheduler {

    private final AgentExecutionService executionService;

    /** 每 10 分钟一轮；阈值 30 分钟（对齐审批超时语义的放宽版，防重启恢复窗口误杀） */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void expireStaleApprovals() {
        int n = executionService.expireStale(30);
        if (n > 0) {
            log.info("[CheckpointScheduler] 本轮过期清理 {} 行", n);
        }
    }
}
