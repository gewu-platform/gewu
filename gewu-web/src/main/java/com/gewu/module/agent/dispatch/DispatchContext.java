package com.gewu.module.agent.dispatch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 主 Agent 的派发上下文：仅在 master 角色 Agent 的会话中建立，
 * 供 {@code agent_dispatch} 工具读取「谁能派发给谁」。
 *
 * <p>从 {@code AgentService} 内部类迁出。迁出的原因不是「类太多」，
 * 而是它造成了真实的包级循环依赖：</p>
 * <pre>
 *   service → tools（AgentService 用 ToolRegistry）
 *   tools   → service（AgentDispatchTool 用 AgentService + 本类）
 * </pre>
 * <p>本类与 {@link DispatchScope} / {@link SubAgentRunner} 一起下沉到 dispatch 包后，
 * 两侧都只单向依赖 dispatch，环被解开。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchContext {

    private Long parentUserId;

    private String parentSessionId;

    /** 发起派发的主 Agent id */
    private String callerAgentId;

    /** 主 Agent 允许派发的子 Agent 清单（null/空 = 未配置，拒绝派发） */
    private List<String> allowedWorkers;
}
