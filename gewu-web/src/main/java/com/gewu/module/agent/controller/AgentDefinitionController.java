package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.definition.AgentConfig;
import com.gewu.module.agent.definition.AgentDefinition;
import com.gewu.module.agent.dto.AgentDefinitionRequest;
import com.gewu.module.agent.registry.AgentRegistry;
import com.gewu.module.audit.service.AuditService;

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
import java.util.UUID;

/**
 * Agent 定义管理 REST API（/api/agent）。
 *
 * <p>承载定义的新增/更新/删除/列表/已启用清单/启停/缓存重载共 7 个端点。
 * 写操作（创建/更新/删除/启停）均落审计日志。</p>
 *
 * <p>从原 AgentController（420 行 / 18 端点）按资源边界拆出；共享 /api/agent 前缀，
 * (verb, path) 与原实现逐一对应，前端无感知。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentDefinitionController {

    private final AgentRegistry agentRegistry;
    /** V2.4.0 C2：审计日志（定义写操作留痕；落库失败不影响业务） */
    private final AuditService auditService;

    /**
     * 创建定义
     */
    @PostMapping("/definitions")
    public R<AgentDefinition> createDefinition(@RequestBody AgentDefinitionRequest req, jakarta.servlet.http.HttpServletRequest request) {
        if (req.getName() == null || req.getName().isBlank()) {
            return R.error(400, "Agent 名称不能为空");
        }
        AgentDefinition definition = AgentDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(req.getName())
                .description(req.getDescription())
                .avatar(req.getAvatar())
                .systemPrompt(req.getSystemPrompt())
                .greeting(req.getGreeting())
                .toolIds(req.getToolIds())
                .config(AgentConfig.builder()
                        .modelId(req.getModelId())
                        .maxIterations(req.getMaxIterations())
                        .temperature(req.getTemperature())
                        .maxTokens(req.getMaxTokens())
                        .requireApproval(req.getRequireApproval())
                        .knowledgeDocumentIds(req.getKnowledgeDocumentIds())
                        .ragTopK(req.getRagTopK())
                        .role(req.getRole())
                        .allowedWorkers(req.getAllowedWorkers())
                        .skillIds(req.getSkillIds())
                        .build())
                .enabled(req.getEnabled() != null ? req.getEnabled() : true)
                .build();
        // V2.4.0 C2：审计——Agent 定义创建
        auditService.record(request, "agent.create", "agent_definition", definition.getId(),
                java.util.Map.of("name", definition.getName(), "role",
                        definition.getConfig() == null ? null : definition.getConfig().getRole()), true);
        return R.success(agentRegistry.register(definition));
    }

    /**
     * 更新定义
     */
    @PutMapping("/definitions")
    public R<AgentDefinition> updateDefinition(@RequestBody AgentDefinitionRequest req, jakarta.servlet.http.HttpServletRequest request) {
        if (req.getId() == null || agentRegistry.get(req.getId()) == null) {
            return R.error(400, "Agent 不存在: " + req.getId());
        }
        AgentDefinition existing = agentRegistry.get(req.getId());
        AgentDefinition definition = AgentDefinition.builder()
                .id(req.getId())
                .name(req.getName() != null ? req.getName() : existing.getName())
                .description(req.getDescription())
                .avatar(req.getAvatar())
                .systemPrompt(req.getSystemPrompt())
                .greeting(req.getGreeting())
                .toolIds(req.getToolIds())
                .config(AgentConfig.builder()
                        .modelId(req.getModelId())
                        .maxIterations(req.getMaxIterations())
                        .temperature(req.getTemperature())
                        .maxTokens(req.getMaxTokens())
                        // P8b 三态语义：前端表单始终显式传 requireApproval，null = 清除回「继承默认」
                        .requireApproval(req.getRequireApproval())
                        .knowledgeDocumentIds(req.getKnowledgeDocumentIds())
                        .ragTopK(req.getRagTopK())
                        .role(req.getRole())
                        .allowedWorkers(req.getAllowedWorkers())
                        .skillIds(req.getSkillIds())
                        .build())
                .enabled(req.getEnabled() != null ? req.getEnabled() : existing.getEnabled())
                .creatorId(existing.getCreatorId())
                .build();
        // V2.4.0 C2：审计——Agent 定义更新
        auditService.record(request, "agent.update", "agent_definition", definition.getId(),
                java.util.Map.of("name", definition.getName()), true);
        return R.success(agentRegistry.update(definition));
    }

    /**
     * 删除定义
     */
    @DeleteMapping("/definitions/{id}")
    public R<Void> deleteDefinition(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        if (agentRegistry.get(id) == null) {
            return R.error(400, "Agent 不存在: " + id);
        }
        agentRegistry.delete(id);
        // V2.4.0 C2：审计——Agent 定义删除
        auditService.record(request, "agent.delete", "agent_definition", id, null, true);
        return R.success();
    }

    /**
     * 定义列表（含禁用）
     */
    @GetMapping("/definitions")
    public R<List<AgentDefinition>> listDefinitions() {
        return R.success(agentRegistry.listAll());
    }

    /**
     * 已启用定义列表（对话选择用）
     */
    @GetMapping("/definitions/enabled-list")
    public R<List<AgentDefinition>> listEnabledDefinitions() {
        return R.success(agentRegistry.listEnabled());
    }

    /**
     * 启用/禁用
     */
    @PutMapping("/definitions/{id}/enabled")
    public R<Void> setEnabled(@PathVariable String id, @RequestParam boolean enabled, jakarta.servlet.http.HttpServletRequest request) {
        if (agentRegistry.get(id) == null) {
        // V2.4.0 C2：审计——Agent 启停
        auditService.record(request, enabled ? "agent.enable" : "agent.disable", "agent_definition", id,
                java.util.Map.of("enabled", enabled), true);
            return R.error(400, "Agent 不存在: " + id);
        }
        agentRegistry.setEnabled(id, enabled);
        return R.success();
    }

    /**
     * 重载缓存
     */
    @PostMapping("/definitions/reload")
    public R<Map<String, Object>> reload() {
        agentRegistry.reload();
        Map<String, Object> result = new HashMap<>();
        result.put("loaded", agentRegistry.listEnabled().size());
        return R.success(result);
    }

}
