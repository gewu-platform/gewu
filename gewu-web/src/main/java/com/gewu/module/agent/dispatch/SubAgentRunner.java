package com.gewu.module.agent.dispatch;

/**
 * 子 Agent 执行入口。
 *
 * <p>这个接口存在的唯一目的，是打断 {@code service ↔ tools} 的包级循环依赖：</p>
 * <pre>
 *   解之前：
 *     AgentDispatchTool（tools）  →  AgentService（service）
 *     AgentService（service）     →  ToolRegistry（tools）
 *
 *   解之后：
 *     AgentDispatchTool（tools）    →  SubAgentRunner（dispatch）
 *     AgentService（service）       →  SubAgentRunner（dispatch）   [实现它]
 *     AgentService（service）       →  ToolRegistry（tools）
 *     ⇒ tools 不再依赖 service，环解开
 * </pre>
 *
 * <p>实现方由 Spring 注入：{@code AgentService} 是当前唯一实现。</p>
 */
public interface SubAgentRunner {

    /**
     * 以独立子会话执行子 Agent 任务。
     *
     * <p>实现方职责：校验 worker 存在/启用/非 master、建子会话并挂 parent_session_id、
     * 强制免审批、置位深度防护标志、执行完成后回流结果。</p>
     *
     * @param workerAgentId 子 Agent 标识（须在主 Agent 的 allowedWorkers 清单内）
     * @param task          自包含的任务描述（子 Agent 看不到父会话历史）
     * @param ctx           派发上下文（提供 parentUserId / parentSessionId）
     * @return 精简执行结果（供工具回传给主 Agent）
     * @throws IllegalArgumentException worker 不存在 / 是 master / 未启用等可预期拒绝
     */
    SubAgentResult runSubAgent(String workerAgentId, String task, DispatchContext ctx);
}
