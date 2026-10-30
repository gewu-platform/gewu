package com.gewu.module.mcp.service;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import com.gewu.module.agent.tools.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V1.1.0 C3/T3.1+T3.3：MCP 连接持有器与工具桥接
 *
 * <p>生命周期：应用级常驻（对齐 ToolRegistry），连接懒创建，锁内建连防并发重复；
 * 不含 DB 依赖（叶子组件），启停编排由 {@link McpServerService} 完成。</p>
 *
 * <p>API 对齐 langchain4j-mcp 1.11.7-beta19：DefaultMcpClient.builder() 构建即建连
 * （无独立 initialize()，checkHealth() 强制校验）；listTools() 直返 ToolSpecification；
 * executeTool() 直通执行；AutoCloseable 收尾。</p>
 *
 * <p>工具命名：mcp_{server}_{tool}——OpenAI 兼容 function calling 的 name 仅允许
 * [a-zA-Z0-9_-]，规划中的 mcp:{server}:{tool} 冒号形式不可用（偏离已记录 PLAN）。</p>
 */
@Slf4j
@Component
public class McpClientHolder {

    private final ToolRegistry toolRegistry;

    /** server name → 已连接客户端 */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    public McpClientHolder(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 连接并桥接一个 server 的全部工具；返回桥接数（供启停接口展示） */
    public int connectAndBridge(String serverName, String endpoint) {
        return connectAndBridge(serverName, endpoint, null);
    }

    /**
     * R3/T1.1 白名单：连接并桥接指定工具（v2.0.1 B1）
     *
     * @param allowList 允许桥接的原始工具名清单；null 或空 = 不限（全量桥接，保持既有行为）
     * @return 实际桥接的工具数
     */
    public int connectAndBridge(String serverName, String endpoint, java.util.List<String> allowList) {
        synchronized (this) {
            if (clients.containsKey(serverName)) {
                return 0; // 已连接
            }
            McpClient client = DefaultMcpClient.builder()
                    .transport(new HttpMcpTransport.Builder()
                            .sseUrl(endpoint)
                            .build())
                    .build();
            // 构建即建连；checkHealth 强制校验可达性（不可达直接抛出，由调用方决定收敛路径）
            client.checkHealth();
            List<ToolSpecification> tools = client.listTools();
            boolean limited = allowList != null && !allowList.isEmpty();
            int bridged = 0;
            for (ToolSpecification t : tools) {
                if (limited && !allowList.contains(t.name())) {
                    log.info("[MCP] 工具被白名单过滤跳过：server={}, tool={}", serverName, t.name());
                    continue;
                }
                String bridgedName = toolName(serverName, t.name());
                String desc = (t.description() == null || t.description().isBlank())
                        ? "MCP tool " + t.name() + " (server: " + serverName + ")"
                        : t.description() + " [MCP server: " + serverName + "]"
                ;
                ToolSpecification renamed = t.toBuilder()
                        .name(bridgedName)
                        .description(desc)
                        .build();
                toolRegistry.registerDirect(renamed, passthrough(serverName, t.name()));
                bridged++;
            }
            clients.put(serverName, client);
            log.info("[MCP] 已连接并桥接：server={}, 桥接工具数={}/{}（白名单={}）",
                    serverName, bridged, tools.size(), limited ? allowList.toString() : "不限");
            return bridged;
        }
    }

    /** 断开并注销一个 server 的全部桥接工具 */
    public void disconnectAndUnbridge(String serverName) {
        McpClient client = clients.remove(serverName);
        if (client == null) {
            return;
        }
        try {
            for (ToolSpecification t : client.listTools()) {
                toolRegistry.unregister(toolName(serverName, t.name()));
            }
        } catch (Exception e) {
            log.warn("[MCP] 断开时工具清点失败（已尽力注销）：server={}, {}", serverName, e.getMessage());
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[MCP] 客户端关闭异常：server={}, {}", serverName, e.getMessage());
        }
        log.info("[MCP] 已断开：server={}", serverName);
    }

    /** 是否已连接 */
    public boolean isConnected(String serverName) {
        return clients.containsKey(serverName);
    }

    /** 当前已连接 server 清单 */
    public List<String> connectedServers() {
        return new ArrayList<>(clients.keySet());
    }

    private String toolName(String serverName, String tool) {
        String sn = serverName.replaceAll("[^a-zA-Z0-9_]", "_");
        String tn = tool.replaceAll("[^a-zA-Z0-9_]", "_");
        String name = "mcp_" + sn + "_" + tn;
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** 透传执行器：桥接名回映原始工具名后直通 executeTool */
    private ToolExecutor passthrough(String serverName, String rawTool) {
        return (ToolExecutionRequest request, Object memoryId) -> {
            McpClient client = clients.get(serverName);
            if (client == null) {
                return "[MCP] server 未连接：" + serverName;
            }
            try {
                ToolExecutionRequest raw = ToolExecutionRequest.builder()
                        .id(request.id())
                        .name(rawTool)
                        .arguments(request.arguments())
                        .build();
                ToolExecutionResult result = client.executeTool(raw);
                return result.resultText() == null ? "" : result.resultText();
            } catch (Exception e) {
                log.warn("[MCP] 工具调用失败：server={}, tool={}, {}", serverName, rawTool, e.getMessage());
                return "[MCP] 工具调用失败：" + e.getMessage();
            }
        };
    }
}
