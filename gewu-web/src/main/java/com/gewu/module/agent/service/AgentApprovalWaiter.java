package com.gewu.module.agent.service;

import com.gewu.module.agent.dto.AgentStreamResponse;
import com.gewu.module.agent.gateway.ToolApprovalGateway;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批协调器：从 AgentService 抽出的「挂起等待 / 决策唤醒 / 审批终结落库」职责。
 *
 * <p>为什么单独成类：AgentService 曾同时承担编排主循环、审批挂起、会话落库、工具派发、
 * 文本处理五类职责（920 行）。审批这块状态独立（一个 gateway + 一张凭证表），
 * 与主循环只通过 toolCallId 交互，是低耦合的可剥离单元。</p>
 *
 * <p>为什么放在 {@code module.agent.service} 而非独立的 {@code agent.approval} 包：
 * 本类需要 {@link AgentExecutionService} 做审批终结落库，而后者同在本包。
 * 若把本类移到子包，会形成 {@code approval → service} 与 {@code service → approval}
 * 的包级双向依赖，与本次重构「消除循环依赖」的目标相悖。
 * 待 {@code AgentExecutionService} 下沉到叶子包后，本类可一并迁移。</p>
 *
 * <p>线程模型：{@link #waitDecision} 在 SSE 流式线程上阻塞轮询（200ms 间隔），
 * 由 {@link #completeDecision}（Controller 线程）唤醒。凭证表用 ConcurrentHashMap 保证可见性。</p>
 */
@Slf4j
public class AgentApprovalWaiter {

    /** 审批等待：3 分钟超时兜底 */
    private static final long APPROVAL_TIMEOUT_MS = 3 * 60 * 1000L;

    /** 审批等待期心跳间隔 15s（防代理断连） */
    private static final long APPROVAL_PING_INTERVAL_MS = 15 * 1000L;

    /** 轮询粒度：过密空转 CPU，过疏影响响应体感 */
    private static final long POLL_INTERVAL_MS = 200L;

    /**
     * 审批闸门。由 new 创建而非 Spring Bean —— V2.1.0 的既有设计，
     * 因此风险分级来源必须在 {@link com.gewu.module.agent.service.AgentService#init()}
     * 中通过 setRiskSource 显式注入（自动注入对本实例无效）。
     */
    private final ToolApprovalGateway gateway = new ToolApprovalGateway();

    /** 挂起中的审批决策凭证：toolCallId → future（true=批准，false=拒绝／超时） */
    private final Map<String, CompletableFuture<Boolean>> decisions = new ConcurrentHashMap<>();

    /** 审批终结落库（checkpoint 叶子服务） */
    private final AgentExecutionService executionService;

    public AgentApprovalWaiter(AgentExecutionService executionService) {
        this.executionService = executionService;
    }

    /** 暴露闸门供编排主循环做 check / approve / reject 与启动期风险源注入 */
    public ToolApprovalGateway gateway() {
        return gateway;
    }

    /**
     * 重启恢复：把 DB 中 waiting_approval 的记录灌回内存队列。
     *
     * @return 恢复条数
     */
    public int restorePending() {
        Map<String, ToolApprovalGateway.ApprovalRequest> restored = executionService.restoreWaitingApprovals();
        if (restored == null || restored.isEmpty()) {
            return 0;
        }
        // getPendingApprovals 返回内部 Map 引用，可直接回填
        gateway.getPendingApprovals().putAll(restored);
        return restored.size();
    }

    /**
     * 流式循环内挂起等待审批决策（同一 SSE 流续跑）。
     *
     * @param sink       SSE 出口，用于发送心跳 ping
     * @param toolCallId 审批凭证
     * @return true=批准（原地执行工具），false=拒绝／超时／流已取消／异常
     */
    public boolean waitDecision(FluxSink<AgentStreamResponse> sink, String toolCallId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        decisions.put(toolCallId, future);
        try {
            long deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS;
            long nextPing = System.currentTimeMillis() + APPROVAL_PING_INTERVAL_MS;
            while (!future.isDone()) {
                if (sink.isCancelled()) {
                    future.cancel(true);
                    return false;
                }
                if (System.currentTimeMillis() >= deadline) {
                    log.warn("[ApprovalGateway] 审批等待超时：toolCallId={}", toolCallId);
                    future.complete(false);
                    break;
                }
                if (System.currentTimeMillis() >= nextPing) {
                    sink.next(AgentStreamResponse.ping());
                    nextPing = System.currentTimeMillis() + APPROVAL_PING_INTERVAL_MS;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("审批等待异常：toolCallId={}", toolCallId, e);
            return false;
        } finally {
            decisions.remove(toolCallId);
        }
    }

    /**
     * 审批决策入口（由 Controller approve/reject 调用）：完成决策凭证唤醒挂起线程。
     *
     * @return true=确有挂起任务被唤醒
     */
    public boolean completeDecision(String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = decisions.get(toolCallId);
        if (future == null) {
            return false;
        }
        return future.complete(approved);
    }

    /**
     * 审批通过：闸门置 APPROVED → 落库 → 唤醒挂起的流式循环原地执行（避免双重执行）。
     */
    public String approveAndExecute(String toolCallId) {
        var result = gateway.approve(toolCallId);
        if (result.getStatus() != ToolApprovalGateway.ApprovalStatus.APPROVED) {
            return "[审批失败] " + result.getMessage();
        }
        // V1.1.0 C2/T2.2：审批终结落库
        executionService.finalizeApproval(toolCallId, "approved");
        boolean woke = completeDecision(toolCallId, true);
        return woke ? "已批准，Agent 继续执行" : "已批准（当前无挂起任务，重试时放行）";
    }

    /**
     * 审批拒绝：闸门置 REJECTED → 落库 → 唤醒挂起线程（返回 false 走未通过分支）。
     */
    public String rejectAndNotify(String toolCallId, String reason) {
        var result = gateway.reject(toolCallId, reason);
        // V1.1.0 C2/T2.2：审批终结落库
        executionService.finalizeApproval(toolCallId, "rejected");
        completeDecision(toolCallId, false);
        return "[审批拒绝] " + result.getMessage();
    }

    /** 当前挂起中的审批项（管理端查看） */
    public Map<String, ToolApprovalGateway.ApprovalRequest> getPendingApprovals() {
        return gateway.getPendingApprovals();
    }
}
