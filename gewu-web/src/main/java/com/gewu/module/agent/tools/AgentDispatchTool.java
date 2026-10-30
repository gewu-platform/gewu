package com.gewu.module.agent.tools;

import com.gewu.module.agent.service.AgentService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V2.0.0 R1/T1.2：主 Agent 派发工具（agent_dispatch）
 *
 * <p>仅 master 角色 Agent（config.role=master）可用：AgentService.executeWithDispatch
 * 只为 master 会话建立派发上下文，本工具无上下文即拒绝。子 Agent 以独立子会话执行
 * （自动落库+监控），结果经工具返回值同步回流主 Agent 对话。</p>
 *
 * <p>安全边界（四层）：① 编排树仅一层（子执行中拒绝再派发）；② 仅 master 会话携带
 * 派发上下文；③ worker 必须在主 Agent config.allowedWorkers 清单内；④ worker 非 master
 * 且启用（runSubAgent 内校验），子 Agent 强制免审批。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentDispatchTool {

    private final AgentService agentService;

    @Tool(name = "agent_dispatch",
            value = "把一个任务派发给指定的子 Agent 执行。workerAgentId 必须是可用的子 Agent 标识；task 是交给子 Agent 的完整任务描述（自包含，子 Agent 看不到你们的对话历史）。返回子 Agent 的执行结果摘要。")
    public String dispatch(
            @P("要派发的子 Agent 标识（workerAgentId）")
            String workerAgentId,
            @P("交给子 Agent 的完整任务描述，自包含且目标明确")
            String task) {
        // 1. 深度防护：子 Agent 执行中不允许再派发（编排树仅一层）
        if (AgentService.inDispatch()) {
            log.warn("[agent_dispatch] 深度防护触发：子执行中再派发被拒");
            return "[agent_dispatch] 拒绝：子 Agent 执行期间不允许再派发（编排树仅一层）";
        }
        // 2. 授权校验：仅 master 的会话执行携带派发上下文
        AgentService.DispatchContext ctx = AgentService.currentDispatchContext();
        if (ctx == null) {
            return "[agent_dispatch] 拒绝：仅 master 角色 Agent 可派发（当前会话未授权）";
        }
        // 3. 目标校验：worker 必须在 allowedWorkers 清单内
        List<String> allowed = ctx.getAllowedWorkers();
        if (allowed == null || allowed.isEmpty()) {
            return "[agent_dispatch] 拒绝：当前主 Agent 未配置 allowedWorkers 派发目标";
        }
        if (!allowed.contains(workerAgentId)) {
            return "[agent_dispatch] 拒绝：子 Agent [" + workerAgentId + "] 不在 allowedWorkers 清单内（可用: " + allowed + "）";
        }
        try {
            AgentService.AgentResult result = agentService.runSubAgent(workerAgentId, task, ctx);
            return "子 Agent [" + workerAgentId + "] 执行完成（迭代 " + result.getIterationCount()
                    + " 次，工具 " + result.getToolsUsed() + "）：\n" + result.getFinalAnswer();
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[agent_dispatch] 派发被拒：worker={}, {}", workerAgentId, e.getMessage());
            return "[agent_dispatch] 派发失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("[agent_dispatch] 子 Agent 执行异常：worker={}, {}", workerAgentId, e.getMessage());
            return "[agent_dispatch] 子 Agent 执行异常：" + e.getMessage();
        }
    }
}
