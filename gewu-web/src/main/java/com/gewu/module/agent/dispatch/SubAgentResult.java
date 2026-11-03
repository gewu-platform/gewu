package com.gewu.module.agent.dispatch;

import java.util.List;

/**
 * 子 Agent 执行结果（派发视角的精简视图）。
 *
 * <p>为什么单独定义一个结果类型，而不是直接用 {@code AgentService.AgentResult}：
 * 那样 {@code agent_dispatch} 工具就必须 import service 包，形成
 * {@code tools → service} 反向依赖，与 {@code service → tools} 构成包级循环。
 * 这里只承载工具回传所需的三个字段，把编排层的完整结果类型留在 service 内。</p>
 */
public record SubAgentResult(String finalAnswer, int iterationCount, List<String> toolsUsed) {
}
