package com.gewu.module.agent.tools;

import com.gewu.module.agent.dispatch.DispatchContext;
import com.gewu.module.agent.dispatch.DispatchScope;
import com.gewu.module.agent.dispatch.SubAgentRunner;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * V2.8.0 R4/T4.1+T4.2：主 Agent 批量派发工具（agent_dispatch_batch）。
 *
 * <p>多个子任务并发执行（每个 worker 独立线程），全部完成后按 worker 结构化
 * 聚合回传（workerId/状态/结论），失败 worker 单独标注不阻断整体。</p>
 *
 * <p>安全边界与单发完全一致（四层）：① 编排树仅一层（DispatchScope.ACTIVE
 * ThreadLocal 每线程独立，worker 线程内再派发同样被拒）；② 仅 master 会话
 * 携带派发上下文；③ 每个 worker 必须在 allowedWorkers 清单内；④ worker 非
 * master 且启用（runSubAgent 内校验），子 Agent 强制免审批。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentDispatchBatchTool {

    private final SubAgentRunner subAgentRunner;

    @Tool(name = "agent_dispatch_batch",
            value = "并行派发多个任务给多个子 Agent，同一批内并发执行，全部完成后聚合返回各自结果。适合多个独立子任务需要同时推进的场景；单个任务请用 agent_dispatch。workerAgentIds 与 tasks 一一对应、数量一致。")
    public String dispatchBatch(
            @P("子 Agent 标识列表，逗号分隔（如 worker-a,worker-b,worker-c）")
            String workerAgentIds,
            @P("任务描述列表，用竖线 | 分隔，顺序与 workerAgentIds 一一对应；每个任务自包含且目标明确")
            String tasks) {
        // 1. 深度防护：子 Agent 执行中不允许再派发（编排树仅一层）
        if (DispatchScope.isActive()) {
            log.warn("[agent_dispatch_batch] 深度防护触发：子执行中再派发被拒");
            return "[agent_dispatch_batch] 拒绝：子 Agent 执行期间不允许再派发（编排树仅一层）";
        }
        // 2. 授权校验：仅 master 的会话执行携带派发上下文
        DispatchContext ctx = DispatchScope.current();
        if (ctx == null) {
            return "[agent_dispatch_batch] 拒绝：仅 master 角色 Agent 可派发（当前会话未授权）";
        }
        // 3. 参数解析
        List<String> workers = splitCsv(workerAgentIds);
        List<String> taskList = splitPipe(tasks);
        if (workers.isEmpty()) {
            return "[agent_dispatch_batch] 拒绝：workerAgentIds 为空";
        }
        if (workers.size() != taskList.size()) {
            return "[agent_dispatch_batch] 拒绝：workerAgentIds（" + workers.size() + " 个）与 tasks（" + taskList.size() + " 个）数量不一致，需一一对应";
        }
        // 4. 目标校验：每个 worker 都必须在 allowedWorkers 清单内
        List<String> allowed = ctx.getAllowedWorkers();
        if (allowed == null || allowed.isEmpty()) {
            return "[agent_dispatch_batch] 拒绝：当前主 Agent 未配置 allowedWorkers 派发目标";
        }
        for (String w : workers) {
            if (!allowed.contains(w)) {
                return "[agent_dispatch_batch] 拒绝：子 Agent [" + w + "] 不在 allowedWorkers 清单内（可用: " + allowed + "）";
            }
        }
        // 5. 并行执行（线程数收敛：min(workers, 4)，避免子执行资源挤兑）
        int poolSize = Math.min(workers.size(), 4);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<String[]>> futures = new ArrayList<>();
        log.info("[agent_dispatch_batch] 批量派发：workers={}, 线程数={}", workers, poolSize);
        try {
            for (int i = 0; i < workers.size(); i++) {
                final String w = workers.get(i);
                final String t = taskList.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        var result = subAgentRunner.runSubAgent(w, t, ctx);
                        return new String[]{w, "ok", "完成（迭代 " + result.iterationCount() + " 次，工具 " + result.toolsUsed() + "）：\n" + result.finalAnswer()};
                    } catch (Exception e) {
                        log.warn("[agent_dispatch_batch] worker 执行失败：worker={}, {}", w, e.getMessage());
                        return new String[]{w, "fail", e.getMessage() == null ? "未知异常" : e.getMessage()};
                    }
                }));
            }
        } finally {
            pool.shutdown();
        }
        // 6. 结构化聚合（T4.2）：workerId/状态/结论，失败标注不阻断
        StringBuilder sb = new StringBuilder("批量派发结果（" + workers.size() + " 个子任务并行）：\n");
        int okCount = 0;
        for (Future<String[]> f : futures) {
            try {
                String[] r = f.get(10, TimeUnit.MINUTES);
                if ("ok".equals(r[1])) {
                    okCount++;
                }
                sb.append("\n─────\n【").append(r[0]).append("】").append("ok".equals(r[1]) ? "完成" : "失败").append("\n").append(r[2]);
            } catch (Exception e) {
                sb.append("\n─────\n【未知】失败\n").append(e.getMessage() == null ? "未知异常" : e.getMessage());
            }
        }
        sb.append("\n─────\n汇总：").append(okCount).append("/").append(workers.size()).append(" 成功");
        log.info("[agent_dispatch_batch] 批量完成：{}/{} 成功", okCount, workers.size());
        return sb.toString();
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> splitPipe(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .toList();
    }
}