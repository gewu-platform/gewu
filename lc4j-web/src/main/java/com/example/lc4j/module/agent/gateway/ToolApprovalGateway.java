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
 * - Agent 级策略优先：requireApproval 页面可配（true=全部审批 / false=免审批 / null=继承默认）
 * - 默认放行：只读工具（calculator/datetime/timezone）
 * - 敏感拦截：外部调用工具（search/自定义 HTTP 工具）
 * - 安全兜底：未知工具默认需要审批
 *
 * 审批流程：
 * 1. Agent 调用工具 → gateway.check() 拦截
 * 2. 需审批 → APPROVAL_REQUIRED + 审批请求入队（SSE 事件推给前端弹窗）
 * 3. 前端确认/拒绝 → /approval/approve、/approval/reject（P8b：仅管理员角色可操作）
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
     * 检查工具调用是否需要审批（兼容旧调用：不传 Agent 策略，走工具级默认）
     */
    public ApprovalResult check(String toolCallId, String toolName, String toolDescription,
                                Map<String, Object> params, String agentId, String sessionId) {
        return check(toolCallId, toolName, toolDescription, params, agentId, sessionId, null);
    }

    /**
     * 检查工具调用是否需要审批（P8b 审批管控：支持 Agent 级策略覆盖）
     *
     * 判定优先级：
     * 1. Agent 级 requireApproval（页面可配：true=全部需审批 / false=免审批）
     * 2. 工具级默认（search 需审批，只读工具放行）
     * 3. 未知工具安全兜底拦截
     */
    public ApprovalResult check(String toolCallId, String toolName, String toolDescription,
                                Map<String, Object> params, String agentId, String sessionId,
                                Boolean agentRequireApproval) {
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

        // 2. Agent 级策略优先（页面可配，P8b 审批管控）
        if (agentRequireApproval != null) {
            if (agentRequireApproval) {
                pendingApprovals.put(toolCallId, request);
                log.info("[ApprovalGateway] 需要审批：tool={}, toolCallId={}, 原因=Agent 策略：全部工具需审批", toolName, toolCallId);
                return ApprovalResult.builder()
                        .status(ApprovalStatus.APPROVAL_REQUIRED)
                        .message("需要审批: Agent 策略要求全部工具调用需审批")
                        .request(request)
                        .build();
            }
            // agentRequireApproval = false：免审批（可信 Agent）
            return ApprovalResult.builder()
                    .status(ApprovalStatus.AUTO_APPROVED)
                    .message("自动放行：Agent 策略为免审批")
                    .build();
        }

        // 3. 工具级默认策略
        if (AUTO_APPROVED_TOOLS.contains(toolName)) {
            return ApprovalResult.builder()
                    .status(ApprovalStatus.AUTO_APPROVED)
                    .message("自动放行：只读工具")
                    .build();
        }

        // 4. 需要审批（search / 未知工具安全兜底）
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
