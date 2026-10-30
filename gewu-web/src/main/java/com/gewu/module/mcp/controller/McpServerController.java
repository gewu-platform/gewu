package com.gewu.module.mcp.controller;

import com.gewu.common.R;
import com.gewu.module.mcp.dto.McpServerSaveRequest;
import com.gewu.module.mcp.entity.McpServer;
import com.gewu.module.mcp.service.McpServerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V1.1.0 C3/T3.2：MCP 服务器配置 Controller
 *
 * <p>端点：/api/settings/mcp-servers（GET 列表 / POST 新增）、
 * /api/settings/mcp-servers/{id}（POST 更新 / DELETE 删除）、
 * /api/settings/mcp-servers/{id}/enable（POST 启用=连接+桥接）、
 * /api/settings/mcp-servers/{id}/disable（POST 停用=断开+注销）、
 * /api/settings/mcp-servers/{id}/test（POST 连通性测试）。</p>
 *
 * <p>认证：挂在 /api/settings/** 下，SecurityConfig hasRole("ADMIN") 覆盖（对齐 LlmProviderController）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;

    /** 配置列表 */
    @GetMapping("/mcp-servers")
    public R<List<McpServer>> list() {
        return R.success(mcpServerService.listAll());
    }

    /** 新增配置 */
    @PostMapping("/mcp-servers")
    public R<McpServer> create(@RequestBody @Valid McpServerSaveRequest request) {
        return R.success(mcpServerService.create(request));
    }

    /** 更新配置 */
    @PostMapping("/mcp-servers/{id}")
    public R<McpServer> update(@PathVariable Long id, @RequestBody @Valid McpServerSaveRequest request) {
        return R.success(mcpServerService.update(id, request));
    }

    /** 删除配置 */
    @DeleteMapping("/mcp-servers/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return R.success("已删除", null);
    }

    /** 启用（连接 + 桥接工具） */
    @PostMapping("/mcp-servers/{id}/enable")
    public R<Map<String, Object>> enable(@PathVariable Long id) {
        return R.success(mcpServerService.enable(id));
    }

    /** 停用（断开 + 注销工具） */
    @PostMapping("/mcp-servers/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable Long id) {
        return R.success(mcpServerService.disable(id));
    }

    /** 连通性测试（探测即断，不改状态） */
    @PostMapping("/mcp-servers/{id}/test")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.success(mcpServerService.test(id));
    }
}
