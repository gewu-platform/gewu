package com.gewu.module.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.BizException;
import com.gewu.module.mcp.dto.McpServerSaveRequest;
import com.gewu.module.mcp.entity.McpServer;
import com.gewu.module.mcp.service.McpServerService;
import com.gewu.module.plugin.entity.PluginRegistry;
import com.gewu.module.plugin.mapper.PluginRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V2.0.0 R3/T3.2+T3.4：插件仓库服务
 *
 * <p>维护常用插件清单（首版形态 = 外部 MCP server），支持一键安装/卸载：
 * 安装 = 把条目登记进 mcp_server 并桥接工具（复用 C3 原语，不重造连接逻辑）；
 * 卸载 = 删除登记并断开桥接。</p>
 *
 * <p>内置样例（T3.4）在首次访问时灌入；端点为占位地址，用户按 docs/PLUGIN.md
 * 改为自建 server 真实地址后即可安装。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginRegistryService {

    private final PluginRegistryMapper mapper;
    private final McpServerService mcpServerService;

    /** T3.4 内置样例：常用公共 MCP server（端点为占位，需自建或用公共代理） */
    private static final List<String[]> BUILTIN = List.of(
            new String[]{"filesystem", "文件系统", "读写本地文件（受目录白名单约束），需自建 server", "http://localhost:8001/sse"},
            new String[]{"fetch", "网页抓取", "抓取 URL 内容并转为 Markdown，需自建 server", "http://localhost:8002/sse"},
            new String[]{"time", "时间服务", "时区转换与当前时间，需自建 server", "http://localhost:8003/sse"},
            new String[]{"memory", "记忆存储", "键值型持久化记忆，需自建 server", "http://localhost:8004/sse"}
    );

    /** 列表（内置样例缺失时自动灌入） */
    public List<PluginRegistry> list() {
        seedBuiltin();
        return mapper.selectList(new LambdaQueryWrapper<PluginRegistry>()
                .orderByAsc(PluginRegistry::getBuiltin)
                .orderByAsc(PluginRegistry::getCode));
    }

    /** 一键安装：登记进 mcp_server（启用即连接桥接） */
    public Map<String, Object> install(Long id) {
        PluginRegistry row = requireRow(id);
        if (Boolean.TRUE.equals(row.getInstalled())) {
            throw new BizException(400, "插件已安装：" + row.getCode());
        }
        McpServerSaveRequest req = new McpServerSaveRequest();
        req.setName(row.getCode());
        req.setTransport(row.getTransport() != null ? row.getTransport() : "http_sse");
        req.setEndpoint(row.getEndpoint());
        req.setEnabled(true);
        req.setRemark("由插件仓库安装：" + row.getName());
        req.setToolAllow(row.getToolAllow());
        McpServer server;
        try {
            server = mcpServerService.create(req);
        } catch (BizException be) {
            // R3/错误分类：业务异常（如名称已存在）原样透传，不伪装成连接问题
            throw be;
        } catch (Exception e) {
            // R3/安装失败回滚：C3 create 在连接失败时抛异常，此时 mcp_server 行可能已落库——
            // 反查删除以免留下「已登记但连不上」的半安装状态，并给前端友好提示
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String reason = root.getMessage() == null ? e.getMessage() : root.getMessage();
            log.warn("[Plugin] 安装失败：code={}, endpoint={}, 原因={}", row.getCode(), row.getEndpoint(), reason);
            try {
                McpServer ghost = mcpServerService.listAll().stream()
                        .filter(s -> s.getName().equals(row.getCode()))
                        .findFirst().orElse(null);
                if (ghost != null) {
                    mcpServerService.delete(ghost.getId());
                    log.info("[Plugin] 已回滚 mcp 登记：name={}", row.getCode());
                }
            } catch (Exception ignore) {
                log.warn("[Plugin] 回滚 mcp 登记失败（可手动清理）：{}", ignore.getMessage());
            }
            throw new BizException(400, "插件安装失败（无法连接端点 " + row.getEndpoint() + "）：" + reason);
        }

        row.setInstalled(true);
        row.setInstalledName(server.getName());
        row.setUpdateTime(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[Plugin] 安装完成：code={}, mcpName={}", row.getCode(), server.getName());
        return Map.of("code", row.getCode(), "installedName", server.getName(),
                "endpoint", row.getEndpoint());
    }

    /** 卸载：删除 mcp_server 登记（断开桥接） */
    public Map<String, Object> uninstall(Long id) {
        PluginRegistry row = requireRow(id);
        if (!Boolean.TRUE.equals(row.getInstalled())) {
            throw new BizException(400, "插件未安装：" + row.getCode());
        }
        // 反查 mcp_server（按名称），删除即断开+注销工具
        McpServer target = mcpServerService.listAll().stream()
                .filter(s -> s.getName().equals(row.getInstalledName()))
                .findFirst().orElse(null);
        if (target != null) {
            mcpServerService.delete(target.getId());
        }
        row.setInstalled(false);
        row.setInstalledName(null);
        row.setUpdateTime(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[Plugin] 卸载完成：code={}, 删除 mcp 登记={}", row.getCode(), target != null);
        return Map.of("code", row.getCode(), "removed", target != null);
    }

    /** 新增自定义插件条目（非内置） */
    public PluginRegistry create(PluginRegistry req) {
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw new BizException(400, "插件编码不能为空");
        }
        if (mapper.selectCount(new LambdaQueryWrapper<PluginRegistry>()
                .eq(PluginRegistry::getCode, req.getCode())) > 0) {
            throw new BizException(400, "插件编码已存在：" + req.getCode());
        }
        req.setId(null);
        req.setBuiltin(false);
        req.setInstalled(false);
        req.setInstalledName(null);
        req.setTransport(req.getTransport() == null || req.getTransport().isBlank()
                ? "http_sse" : req.getTransport());
        req.setCreateTime(LocalDateTime.now());
        req.setUpdateTime(LocalDateTime.now());
        mapper.insert(req);
        return req;
    }

    /** 删除条目（内置不可删；已安装需先卸载） */
    public void delete(Long id) {
        PluginRegistry row = requireRow(id);
        if (Boolean.TRUE.equals(row.getBuiltin())) {
            throw new BizException(400, "内置样例不可删除：" + row.getCode());
        }
        if (Boolean.TRUE.equals(row.getInstalled())) {
            throw new BizException(400, "请先卸载该插件：" + row.getCode());
        }
        mapper.deleteById(id);
    }

    private PluginRegistry requireRow(Long id) {
        PluginRegistry row = mapper.selectById(id);
        if (row == null) {
            throw new BizException(400, "插件不存在：" + id);
        }
        return row;
    }

    /** 内置样例灌入（幂等：按 code 判重） */
    private void seedBuiltin() {
        for (String[] b : BUILTIN) {
            Long n = mapper.selectCount(new LambdaQueryWrapper<PluginRegistry>()
                    .eq(PluginRegistry::getCode, b[0]));
            if (n != null && n > 0) {
                continue;
            }
            PluginRegistry row = new PluginRegistry();
            row.setCode(b[0]);
            row.setName(b[1]);
            row.setDescription(b[2]);
            row.setTransport("http_sse");
            row.setEndpoint(b[3]);
            row.setBuiltin(true);
            row.setInstalled(false);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            mapper.insert(row);
        }
    }
}
