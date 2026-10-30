package com.gewu.module.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gewu.common.BizException;
import com.gewu.module.mcp.dto.McpServerSaveRequest;
import com.gewu.module.mcp.entity.McpServer;
import com.gewu.module.mcp.mapper.McpServerMapper;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.1.0 C3/T3.2：MCP 服务器配置服务（CRUD + 启停编排）
 *
 * <p>启停 = DB enabled 位 + McpClientHolder 连接编排的复合操作；
 * 工具执行失败不影响 CRUD 主流程（桥接器内部兜底）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerMapper mapper;
    private final McpClientHolder holder;

    public List<McpServer> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<McpServer>().orderByAsc(McpServer::getId));
    }

    public McpServer create(McpServerSaveRequest req) {
        String transport = normalizeTransport(req.getTransport());
        if (mapper.selectCount(new LambdaQueryWrapper<McpServer>().eq(McpServer::getName, req.getName())) > 0) {
            throw new BizException(400, "配置名称已存在：" + req.getName());
        }
        McpServer row = new McpServer();
        row.setName(req.getName());
        row.setTransport(transport);
        row.setEndpoint(req.getEndpoint());
        row.setEnabled(Boolean.TRUE.equals(req.getEnabled()));
        row.setRemark(req.getRemark());
        mapper.insert(row);
        if (Boolean.TRUE.equals(row.getEnabled())) {
            holder.connectAndBridge(row.getName(), row.getEndpoint());
        }
        return row;
    }

    public McpServer update(Long id, McpServerSaveRequest req) {
        McpServer row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "配置不存在：" + id);
        }
        String transport = normalizeTransport(req.getTransport());
        if (!row.getName().equals(req.getName())
                && mapper.selectCount(new LambdaQueryWrapper<McpServer>().eq(McpServer::getName, req.getName())) > 0) {
            throw new BizException(400, "配置名称已存在：" + req.getName());
        }
        // 先按旧 enabled 收敛旧连接，再按新配置重建
        if (holder.isConnected(row.getName())) {
            holder.disconnectAndUnbridge(row.getName());
        }
        LambdaUpdateWrapper<McpServer> uw = new LambdaUpdateWrapper<>();
        uw.eq(McpServer::getId, id)
          .set(McpServer::getName, req.getName())
          .set(McpServer::getTransport, transport)
          .set(McpServer::getEndpoint, req.getEndpoint())
          .set(McpServer::getEnabled, Boolean.TRUE.equals(req.getEnabled()))
          .set(McpServer::getRemark, req.getRemark())
          .set(McpServer::getUpdateTime, LocalDateTime.now());
        mapper.update(null, uw);
        if (Boolean.TRUE.equals(req.getEnabled())) {
            try {
                holder.connectAndBridge(req.getName(), req.getEndpoint());
            } catch (Exception e) {
                throw new BizException(400, "MCP server 连接失败（配置已保存，可修复端点后重新启用）：" + rootMessage(e));
            }
        }
        return mapper.selectById(id);
    }

    public void delete(Long id) {
        McpServer row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "配置不存在：" + id);
        }
        if (holder.isConnected(row.getName())) {
            holder.disconnectAndUnbridge(row.getName());
        }
        mapper.deleteById(id);
    }

    /** 启用（连接 + 桥接工具） */
    public Map<String, Object> enable(Long id) {
        McpServer row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "配置不存在：" + id);
        }
        if (!holder.isConnected(row.getName())) {
            try {
                holder.connectAndBridge(row.getName(), row.getEndpoint());
            } catch (Exception e) {
                // V1.1.0 冒烟反馈：不可达端点返回友好错误而非裸 500
                throw new BizException(400, "MCP server 连接失败：" + rootMessage(e));
            }
        }
        setEnabled(id, true);
        return statusOf(row);
    }

    /** 停用（断开 + 注销工具） */
    public Map<String, Object> disable(Long id) {
        McpServer row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "配置不存在：" + id);
        }
        holder.disconnectAndUnbridge(row.getName());
        setEnabled(id, false);
        return statusOf(row);
    }

    /** T3.1 验收：连通性测试（不落库不改状态，探测即断） */
    public Map<String, Object> test(Long id) {
        McpServer row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "配置不存在：" + id);
        }
        Map<String, Object> result = new HashMap<>();
        McpClient probe = null;
        try {
            probe = DefaultMcpClient.builder()
                    .transport(new HttpMcpTransport.Builder().sseUrl(row.getEndpoint()).build())
                    .build();
            probe.checkHealth();
            int n = probe.listTools().size();
            result.put("ok", true);
            result.put("toolCount", n);
            result.put("message", "连接成功，发现 " + n + " 个工具");
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "连接失败：" + e.getMessage());
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (Exception ignore) { }
            }
        }
        return result;
    }

    /** 应用启动桥接：enabled 行逐一连接，失败不阻断启动 */
    public void bridgeEnabledOnStartup() {
        try {
            LambdaQueryWrapper<McpServer> qw = new LambdaQueryWrapper<>();
            qw.eq(McpServer::getEnabled, true);
            for (McpServer row : mapper.selectList(qw)) {
                try {
                    holder.connectAndBridge(row.getName(), row.getEndpoint());
                } catch (Exception one) {
                    log.warn("[MCP] 启动桥接失败（server 不可达或未就绪）：server={}, {}",
                            row.getName(), one.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[MCP] 启动桥接扫描失败：{}", e.getMessage());
        }
    }

    private void setEnabled(Long id, boolean enabled) {
        LambdaUpdateWrapper<McpServer> uw = new LambdaUpdateWrapper<>();
        uw.eq(McpServer::getId, id)
          .set(McpServer::getEnabled, enabled)
          .set(McpServer::getUpdateTime, LocalDateTime.now());
        mapper.update(null, uw);
    }

    private Map<String, Object> statusOf(McpServer row) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", row.getName());
        m.put("connected", holder.isConnected(row.getName()));
        return m;
    }

    /** 取异常链最深层因的可读消息 */
    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private String normalizeTransport(String transport) {
        if (transport == null || transport.isBlank()) {
            return "http_sse";
        }
        if (!"http_sse".equals(transport)) {
            throw new BizException(400, "不支持的传输类型：" + transport + "（首版仅支持 http_sse）");
        }
        return transport;
    }
}
