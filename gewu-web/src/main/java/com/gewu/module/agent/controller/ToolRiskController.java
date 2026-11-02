package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.tools.SandboxPolicy;
import com.gewu.module.agent.tools.ToolRegistry;
import com.gewu.module.agent.tools.ToolRisk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.1.0 B1：工具风险分级管理 Controller（可观测 + 提级）
 *
 * <p>端点：/api/settings/tool-risks（GET 风险级与策略快照）、
 * /api/settings/tool-risks/{toolName}（POST 设置风险级：SAFE/DYNAMIC/EXTERNAL/DANGEROUS）。</p>
 *
 * <p>认证：挂在 /api/settings/** 下，SecurityConfig hasRole("ADMIN") 覆盖。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/tool-risks")
@RequiredArgsConstructor
public class ToolRiskController {

    private final ToolRegistry toolRegistry;
    private final SandboxPolicy sandboxPolicy;

    /** 风险级与执行策略快照 */
    @GetMapping
    public R<Map<String, Object>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("risks", toolRegistry.getAllRisks());
        data.put("policy", sandboxPolicy.snapshot());
        return R.success(data);
    }

    /**
     * 设置某工具的风险级（提级用；仅对已注册工具生效）
     *
     * @param body { "risk": "DANGEROUS" }
     */
    @PostMapping("/{toolName}")
    public R<Map<String, Object>> setRisk(@PathVariable String toolName,
                                          @RequestBody(required = false) Map<String, String> body) {
        String raw = body == null ? null : body.get("risk");
        if (raw == null || raw.isBlank()) {
            throw new com.gewu.common.BizException(400, "risk 不能为空");
        }
        ToolRisk risk;
        try {
            risk = ToolRisk.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.gewu.common.BizException(400,
                    "risk 取值应为 SAFE / DYNAMIC / EXTERNAL / DANGEROUS：" + raw);
        }
        if (!toolRegistry.has(toolName)) {
            throw new com.gewu.common.BizException(404, "工具不存在：" + toolName);
        }
        toolRegistry.setRisk(toolName, risk);
        log.info("[ToolRisk] 设置工具风险级：tool={}, risk={}", toolName, risk.name());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("risk", risk.name());
        data.put("policy", sandboxPolicy.policyFor(risk));
        data.put("allowed", sandboxPolicy.allowExecution(risk));
        return R.success(data);
    }
}
