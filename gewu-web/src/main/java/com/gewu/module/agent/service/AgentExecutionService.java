package com.gewu.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.module.agent.entity.AgentExecution;
import com.gewu.module.agent.gateway.ToolApprovalGateway;
import com.gewu.module.agent.mapper.AgentExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.1.0 C2：Agent 执行态持久化服务（checkpoint）
 *
 * 设计要点：
 - 叶子服务：仅依赖 Mapper，不反向依赖 AgentService（避免循环依赖，对齐 ToolRegistry 教训）
 - AgentService 在审批入队/终结时调用本服务落库与更新
 - AgentService 启动时通过 restoreWaitingApprovals() 重建内存挂起队列
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentExecutionMapper executionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** T2.2：审批挂起落库（waiting_approval 行，toolCallId 主键幂等） */
    public void recordWaitingApproval(ToolApprovalGateway.ApprovalRequest request) {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("toolCallId", request.getToolCallId());
            state.put("toolName", request.getToolName());
            state.put("toolDescription", request.getToolDescription());
            state.put("params", request.getParams());
            AgentExecution row = new AgentExecution();
            row.setId(request.getToolCallId());
            row.setSessionId(request.getSessionId());
            row.setAgentId(request.getAgentId());
            row.setStatus("waiting_approval");
            row.setStateJson(objectMapper.writeValueAsString(state));
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            executionMapper.insert(row);
            log.info("[Checkpoint] 审批挂起已落库：id={}, tool={}", row.getId(), request.getToolName());
        } catch (Exception e) {
            log.warn("[Checkpoint] 审批挂起落库失败（不阻断审批流）：{}", e.getMessage());
        }
    }

    /** T2.2：审批终结（approved/rejected） */
    public void finalizeApproval(String toolCallId, String finalStatus) {
        try {
            LambdaUpdateWrapper<AgentExecution> uw = new LambdaUpdateWrapper<>();
            uw.eq(AgentExecution::getId, toolCallId)
              .set(AgentExecution::getStatus, finalStatus)
              .set(AgentExecution::getUpdateTime, LocalDateTime.now());
            executionMapper.update(null, uw);
            log.info("[Checkpoint] 审批已终结：id={}, status={}", toolCallId, finalStatus);
        } catch (Exception e) {
            log.warn("[Checkpoint] 审批终结落库失败：id={}, {}", toolCallId, e.getMessage());
        }
    }

    /** T2.3：重启恢复——扫描 waiting_approval 行，重建内存挂起队列 */
    public Map<String, ToolApprovalGateway.ApprovalRequest> restoreWaitingApprovals() {
        Map<String, ToolApprovalGateway.ApprovalRequest> restored = new HashMap<>();
        try {
            LambdaUpdateWrapper<AgentExecution> uw = new LambdaUpdateWrapper<>();
            uw.eq(AgentExecution::getStatus, "waiting_approval");
            List<AgentExecution> rows = executionMapper.selectList(uw);
            for (AgentExecution row : rows) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> state = objectMapper.readValue(row.getStateJson(), Map.class);
                    ToolApprovalGateway.ApprovalRequest req = ToolApprovalGateway.ApprovalRequest.builder()
                            .toolCallId(row.getId())
                            .toolName((String) state.get("toolName"))
                            .toolDescription((String) state.get("toolDescription"))
                            .params((Map<String, Object>) state.get("params"))
                            .agentId(row.getAgentId())
                            .sessionId(row.getSessionId())
                            .build();
                    restored.put(row.getId(), req);
                } catch (Exception parseEx) {
                    log.warn("[Checkpoint] 恢复行解析失败，标记 expired：id={}", row.getId());
                    finalizeApproval(row.getId(), "expired");
                }
            }
            log.info("[Checkpoint] 重启恢复完成：waiting_approval 行数={}", restored.size());
        } catch (Exception e) {
            log.warn("[Checkpoint] 重启恢复失败（不影响启动）：{}", e.getMessage());
        }
        return restored;
    }

    /** T2.4：过期清理（AppScheduler 定时调用；对齐审批 3 分钟超时语义，扫描阈值放宽到 30 分钟防误杀） */
    public int expireStale(long thresholdMinutes) {
        try {
            LambdaUpdateWrapper<AgentExecution> uw = new LambdaUpdateWrapper<>();
            uw.eq(AgentExecution::getStatus, "waiting_approval")
              .lt(AgentExecution::getUpdateTime, LocalDateTime.now().minusMinutes(thresholdMinutes))
              .set(AgentExecution::getStatus, "expired")
              .set(AgentExecution::getUpdateTime, LocalDateTime.now());
            int n = executionMapper.update(null, uw);
            if (n > 0) {
                log.info("[Checkpoint] 过期清理：{} 行 waiting_approval → expired", n);
            }
            return n;
        } catch (Exception e) {
            log.warn("[Checkpoint] 过期清理失败：{}", e.getMessage());
            return 0;
        }
    }
}
