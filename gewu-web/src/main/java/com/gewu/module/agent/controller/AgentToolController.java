package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.service.AgentService;
import com.gewu.module.agent.tools.ToolRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具与审批 REST API（/api/agent）。
 *
 * <p>承载工具清单（/tools、/available-tools）、健康探活（/health）、
 * 审批网关（/approval/pending、/approval/approve、/approval/reject）共 6 个端点。</p>
 *
 * <p>从原 AgentController（420 行 / 18 端点）按资源边界拆出；共享 /api/agent 前缀，
 * (verb, path) 与原实现逐一对应，前端无感知。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentService agentService;
    private final ToolRegistry toolRegistry;

    @GetMapping("/tools")
    public R<List<Map<String, Object>>> getTools() {
        return R.success(agentService.getTools());
    }

    /**
     * 可用工具清单（定义绑定用）
     */
    @GetMapping("/available-tools")
    public R<List<Map<String, Object>>> availableTools() {
        return R.success(agentService.getTools());
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> info = new HashMap<>();
        info.put("status", "UP");
        info.put("toolCount", agentService.getTools().size());
        info.put("tools", toolRegistry.getToolNames());
        info.put("engine", "langchain4j-native-function-calling");
        return R.success(info);
    }

    @GetMapping("/approval/pending")
    public R<Map<String, Object>> getPendingApprovals() {
        var pending = agentService.getPendingApprovals();
        Map<String, Object> result = new HashMap<>();
        result.put("count", pending.size());
        result.put("requests", pending);
        return R.success(result);
    }

    @PostMapping("/approval/approve")
    public R<String> approveTool(@RequestBody ApprovalRequest request) {
        if (request == null || request.getToolCallId() == null) {
            return R.error(400, "toolCallId 不能为空");
        }
        return R.success(agentService.approveAndExecute(request.getToolCallId()));
    }

    @PostMapping("/approval/reject")
    public R<String> rejectTool(@RequestBody ApprovalRequest request) {
        if (request == null || request.getToolCallId() == null) {
            return R.error(400, "toolCallId 不能为空");
        }
        return R.success(agentService.rejectAndNotify(request.getToolCallId(), request.getReason()));
    }

    @lombok.Data
    public static class ApprovalRequest {
        private String toolCallId;
        private String reason;
    }

}
