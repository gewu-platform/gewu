package com.gewu.module.agent.controller;

import com.gewu.common.BizException;
import com.gewu.common.R;
import com.gewu.module.agent.sandbox.SandboxRunner;
import com.gewu.module.agent.tools.SandboxPolicy;
import com.gewu.module.agent.tools.ToolRegistry;
import com.gewu.module.agent.tools.ToolRisk;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * V2.1.0 B1/B2：工具风险分级与沙箱诊断 Controller
 *
 * <p>端点：/api/settings/tool-risks（GET 风险级与策略快照）、
 * /api/settings/tool-risks/{toolName}（POST 设置风险级）、
 * /api/settings/tool-risks/sandbox/probe（POST 沙箱诊断，ADMIN）。</p>
 *
 * <p>诊断端点只允许固定动作（health/timeout/crash/tool），不开放任意脚本执行。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/tool-risks")
@RequiredArgsConstructor
public class ToolRiskController {

    private final ToolRegistry toolRegistry;
    private final SandboxPolicy sandboxPolicy;

    /** V2.4.0 C2：审计日志（管理面操作留痕；落库失败不影响业务） */
    private final com.gewu.module.audit.service.AuditService auditService;

    /** 沙箱运行器（可选；B2 未装配时仅保留 B1 能力） */
    @Autowired(required = false)
    private SandboxRunner sandboxRunner;

    /** 风险级、执行策略与沙箱健康状态快照 */
    @GetMapping
    public R<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("risks", toolRegistry.getAllRisks());
        data.put("policy", sandboxPolicy.snapshot());
        data.put("sandboxHealthy", sandboxRunner != null && sandboxRunner.isHealthy());
        data.put("sandboxHealthNote", sandboxRunner != null ? sandboxRunner.getLastHealthNote() : "未装配");
        // V2.4.0 C2：审计——工具风险级调整（安全相关操作，必留痕）
        return R.success(data);
    }

    /** 设置某工具的风险级（仅对已注册工具生效） */
    @PostMapping("/{toolName}")
    public R<Map<String, Object>> setRisk(@PathVariable String toolName,
                                          @RequestBody(required = false) Map<String, String> body, jakarta.servlet.http.HttpServletRequest request) {
        String raw = body == null ? null : body.get("risk");
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "risk 不能为空");
        }
        ToolRisk risk;
        try {
            risk = ToolRisk.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(400,
                    "risk 取值应为 SAFE / DYNAMIC / EXTERNAL / DANGEROUS：" + raw);
        }
        if (!toolRegistry.has(toolName)) {
            throw new BizException(404, "工具不存在：" + toolName);
        }
        toolRegistry.setRisk(toolName, risk);
        log.info("[ToolRisk] 设置工具风险级：tool={}, risk={}", toolName, risk.name());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("risk", risk.name());
        data.put("policy", sandboxPolicy.policyFor(risk));
        data.put("allowed", sandboxPolicy.allowExecution(risk));
        // V2.4.0 C2：审计——工具风险级调整（安全相关操作，必留痕）
        auditService.record(request, "tool.risk.set", "tool", toolName,
                java.util.Map.of("risk", risk.name()), true);
        return R.success(data);
    }

    /**
     * V2.1.0 B2 诊断：固定动作验证沙箱，不走模型，结果可重复。
     * health=1+1；timeout=子进程死循环（预期 timeout）；
     * crash=子进程主动退出（预期 crash/error，主进程不受影响）；
     * tool=经 ToolRegistry 走真实工具路由（body.tool / body.arguments）。
     */
    @PostMapping("/sandbox/probe")
    public R<Map<String, Object>> probe(@RequestBody(required = false) Map<String, Object> body) {
        if (sandboxRunner == null) {
            throw new BizException(503, "沙箱运行器未装配");
        }
        String mode = body == null || body.get("mode") == null ? "health" : String.valueOf(body.get("mode"));
        Map<String, Object> out = new LinkedHashMap<>();
        switch (mode) {
            case "health" -> out.put("run", sandboxRunner.runJs("1+1"));
            case "timeout" -> out.put("run", sandboxRunner.runJs("while(true){}"));
            case "crash" -> out.put("run", sandboxRunner.runJs("java.lang.System.exit(7)"));
            case "tool" -> {
                String tool = body.get("tool") == null ? null : String.valueOf(body.get("tool"));
                String args = body.get("arguments") == null ? "{}" : String.valueOf(body.get("arguments"));
                if (tool == null || tool.isBlank()) {
                    throw new BizException(400, "tool 模式必须提供 tool");
                }
                ToolExecutionRequest req = ToolExecutionRequest.builder()
                        .id("sandbox-probe-" + UUID.randomUUID())
                        .name(tool)
                        .arguments(args)
                        .build();
                out.put("result", toolRegistry.execute(req));
            }
            default -> throw new BizException(400, "mode 仅支持 health/timeout/crash/tool");
        }
        out.put("sandboxHealthy", sandboxRunner.isHealthy());
        out.put("healthNote", sandboxRunner.getLastHealthNote());
        return R.success(out);
    }
}
