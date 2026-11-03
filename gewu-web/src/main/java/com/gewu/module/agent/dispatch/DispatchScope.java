package com.gewu.module.agent.dispatch;

/**
 * 派发上下文的作用域持有者（ThreadLocal）。
 *
 * <p>两个 ThreadLocal 的语义完全不同，不要合并：</p>
 * <ul>
 *   <li>{@link #CONTEXT}：<b>谁能派发</b>。由 master 会话在工具执行前 set、执行后 remove，
 *       供 agent_dispatch 读取 allowedWorkers 做授权校验。</li>
 *   <li>{@link #ACTIVE}：<b>是否正在派发中</b>。子 Agent 执行期间置位，
 *       用于强制「编排树仅一层」——子执行中再调 agent_dispatch 直接被拒。</li>
 * </ul>
 *
 * <p>线程模型依据：{@code DefaultToolExecutor} 的反射调用与 AgentService 的
 * check/execute 在同一线程，因此 ThreadLocal 可确定性可见。</p>
 */
public final class DispatchScope {

    /** 派发上下文：仅 master 会话在工具执行期间持有 */
    private static final ThreadLocal<DispatchContext> CONTEXT = new ThreadLocal<>();

    /** 子 Agent 执行标志：深度防护的唯一判据 */
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private DispatchScope() {
    }

    /** 子 Agent 是否正在当前线程执行（深度校验用） */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** 标记进入子 Agent 执行（调用方必须在 finally 中 {@link #clearActive()}） */
    public static void markActive() {
        ACTIVE.set(Boolean.TRUE);
    }

    /** 清除子 Agent 执行标志 */
    public static void clearActive() {
        ACTIVE.remove();
    }

    /** 读当前线程派发上下文；无授权则为 null */
    public static DispatchContext current() {
        return CONTEXT.get();
    }

    /** 建立派发上下文（仅 master 会话；调用方负责在 finally 中 {@link #clear()}） */
    public static void set(DispatchContext ctx) {
        CONTEXT.set(ctx);
    }

    /** 清除派发上下文 */
    public static void clear() {
        CONTEXT.remove();
    }
}
