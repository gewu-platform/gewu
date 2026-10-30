package com.gewu.module.mcp.config;

import com.gewu.module.mcp.service.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * V1.1.0 C3/T3.2：启动时桥接已启用的 MCP server
 *
 * <p>ApplicationReadyEvent 时机（对齐 ToolRegistry.autoScanRegister）：
 所有 Bean 就绪后才连外部服务，失败不阻断启动（McpServerService 内部兜底）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpBridgeBoot {

    private final McpServerService mcpServerService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        mcpServerService.bridgeEnabledOnStartup();
    }
}
