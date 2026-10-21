package com.example.lc4j.module.agent.gateway;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行权限网关（对齐 java-ai-native ToolApprovalGateway）
 *
 * 设计原则：
 * - 默认放行：只读工具（calculator/datetime/timezone）
 * - 敏感拦截：外部调用工具（search/自定义 HTTP 工具）
 * - 安全兜底：未知工具默认需要审批
 *
 * 审批流程：
 * 1. Agent 调用工具 → gateway.check() 拦截
 * 2. 需审批 → APPROVAL_REQUIRED + 审批请求入队（SSE 事件推给前端弹窗）
 * 3. 前端确认/拒绝 → /approval/approve、/approval/reject
 */
@Slf4j
public class ToolApprovalGateway {

    public enum ApprovalStatus {
        /** 自动放行（无需审批） */
        AUTO_APPROVED,
        /** 已审批通过 */
        APPROVED,
        /** 需要用户审批 */
        APPROVAL_REQUIRED,
        /** 审批被拒绝 */
        REJECTED
    }

    @Data
    @Builder
    public static class ApprovalRequest {
        private String toolCallId;
        private String toolName;
        private String toolDescription;
        private Map<String, Object> params;
        private String agentId;
        private String sessionId;
    }

    @Data
    @Builder
    public static class ApprovalResult {
        private ApprovalStatus status;
        private String message;
        private ApprovalRequest request;
    }

    /** 需要审批的工具名（精确匹配） */
    private static final List<String> APPROVAL_REQUIRED_TOOLS = List.of("search");

    /** 自动放行的工具名（只读） */
    private static final List<String> AUTO_APPROVED_TOOLS = List.of("calculator", "datetime", "timezone", "agent_dispatch");

    /** 待审批队列：toolCallId → ApprovalRequest */
    private final Map<String, ApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();

    /** 已审批记录：toolCallId → true */
    private final Map<String, Boolean> approvedCache = new ConcurrentHashMap<>();

    /**
     * 检查工具调用是否需要审批
     */
    public ApprovalResult check(String toolCallId, String toolName, String toolDescription,
                                Map<String, Object> params, String agentId, String sessionId) {
        // 1. 已审批放行
        if (approvedCache.remove(toolCallId) != null) {
            return ApprovalResult.builder()
                    .status(ApprovalStatus.APPROVED)
                    .message("已审批通过")
                    .build();
        }

        ApprovalRequest request = ApprovalRequest.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .toolDescription(toolDescription)
                .params(params)
                .agentId(agentId)
                .sessionId(sessionId)
                .build();

        // 2. 策略匹配
        if (AUTO_APPROVED_TOOLS.contains(toolName)) {
            return ApprovalResult.builder()
                    .status(ApprovalStatus.AUTO_APPROVED)
                    .message("自动放行：只读工具")
                    .build();
        }

        // 3. 需要审批（search / 未知工具安全兜底）
        boolean unknown = !APPROVAL_REQUIRED_TOOLS.contains(toolName);
        pendingApprovals.put(toolCallId, request);
        String reason = unknown ? "未知工具默认拦截（安全兜底）" : "外部网络请求需审批";
        log.info("[ApprovalGateway] 需要审批：tool={}, toolCallId={}, 原因={}", toolName, toolCallId, reason);

        return ApprovalResult.builder()
                .status(ApprovalStatus.APPROVAL_REQUIRED)
                .message(String.format("需要审批: %s", reason))
                .request(request)
                .build();
    }

    /**
     * 审批通过（缓存，等待引擎下次 check 时放行）
     */
    public ApprovalResult approve(String toolCallId) {
        ApprovalRequest request = pendingApprovals.remove(toolCallId);
        if (request == null) {
            return ApprovalResult.builder()
                    .status(ApprovalStatus.REJECTED)
                    .message("审批请求不存在或已处理")
                    .build();
        }
        approvedCache.put(toolCallId, true);
        log.info("[ApprovalGateway] 审批通过：toolCallId={}", toolCallId);
        return ApprovalResult.builder()
                .status(ApprovalStatus.APPROVED)
                .message("已通过，工具可在下次调用时执行")
                .request(request)
                .build();
    }

    /**
     * 审批拒绝
     */
    public ApprovalResult reject(String toolCallId, String reason) {
        ApprovalRequest request = pendingApprovals.remove(toolCallId);
        if (request == null) {
            return ApprovalResult.builder()
                    .status(ApprovalStatus.REJECTED)
                    .message("审批请求不存在或已处理")
                    .build();
        }
        log.info("[ApprovalGateway] 审批拒绝：toolCallId={}, reason={}", toolCallId, reason);
        return ApprovalResult.builder()
                .status(ApprovalStatus.REJECTED)
                .message("已拒绝：" + (reason != null ? reason : "用户未说明"))
                .request(request)
                .build();
    }

    /**
     * 待审批列表
     */
    public Map<String, ApprovalRequest> getPendingApprovals() {
        return pendingApprovals;
    }
}
