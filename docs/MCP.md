# MCP 客户端使用指南（V1.1.0 C3）

格物 Gewu 支持通过 MCP（Model Context Protocol）接入外部工具服务。
首版支持 **HTTP SSE 传输**（`http_sse`），覆盖绝大多数公共 MCP server 与 stdio 代理网关。

## 一、快速开始

1. 管理端登录（ADMIN 角色必需——MCP 配置挂在 `/api/settings/**` 管理面）。
2. 进入「系统设置 → MCP 服务器」，或直接调 API：

```bash
# 新增并启用（enabled=true 即连接 + 桥接工具）
curl -X POST http://localhost:8081/api/settings/mcp-servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"name":"time","transport":"http_sse","endpoint":"http://localhost:8001/sse","enabled":true}'

# 连通性测试（探测即断，不改状态）
curl -X POST http://localhost:8081/api/settings/mcp-servers/1/test -H "Authorization: Bearer <token>"

# 停用（断开 + 注销桥接工具）
curl -X POST http://localhost:8081/api/settings/mcp-servers/1/disable -H "Authorization: Bearer <token>"
```

## 二、工具桥接规则

- 桥接工具命名：`mcp_{server}_{tool}`（OpenAI 兼容 function name 仅允许字母数字下划线连字符，
  故规划中的 `mcp:{server}:{tool}` 冒号形式不可用，此处为有意偏离）。
- 命名超 64 字符自动截断；server/tool 名中的非法字符替换为 `_`。
- 桥接工具与其他工具一致进入 ToolRegistry，参与 Agent 定义勾选；**默认走审批**（未知工具安全兜底），
  可在 Agent 定义上开免审批或按工具白名单放行。
- server 停用/删除即注销其全部桥接工具。

## 三、公共 server 样例

### 1. mcp-proxy（stdio 转 SSE，官方参考实现）

本地 stdio 型 server（如 filesystem）可通过 mcp-proxy 暴露为 SSE：

```bash
# pip install mcp-proxy
mcp-proxy --port 8001 -- npx -y @modelcontextprotocol/server-filesystem D:/workspace
# 配置 endpoint: http://localhost:8001/sse
```

### 2. 自建 MCP server（Python SDK 最小样例）

```python
# pip install "mcp[cli]"
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("demo", host="0.0.0.0", port=8002)

@mcp.tool()
def add(a: int, b: int) -> int:
    """两数相加"""
    return a + b

if __name__ == "__main__":
    mcp.run(transport="sse")  # endpoint: http://localhost:8002/sse
```

## 四、行为细节

- **启动桥接**：应用就绪后自动连接所有 `enabled=true` 的 server；单个不可达不阻断启动。
- **更新配置**：先断旧连接再按新配置重建，避免工具重名残留。
- **失败兜底**：工具调用失败返回错误文本给模型（不中断对话循环）；连接失败不影响 CRUD。
- **审批联动**：挂起的 MCP 工具审批同样进入 C2 checkpoint 持久化，重启可恢复。

## 五、边界与限制

- 首版仅 `http_sse`；stdio 传输请走 mcp-proxy 网关。
- 不支持需要鉴权头的 server（后续版本扩展 header 配置）。
- 工具 schema 透传 MCP server 的 inputSchema，Gewu 不做二次校验。

---

## 调用边界配置（V2.2.0）

MCP 工具执行是**纯 HTTP/SSE 直通**（本地不执行代码），因此采用「有界直执行」而非进程沙箱。
两个边界都可配置：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `GEWU_MCP_TIMEOUT_MS` | `30000` | 单次调用超时；同时作用于 `HttpMcpTransport.timeout` 与 `DefaultMcpClient.toolExecutionTimeout` |
| `GEWU_MCP_MAX_RESULT_CHARS` | `100000` | 单次结果长度上限，超出截断并在结果末尾标注 |

超时走的是 MCP 库**原生超时**（不占用公共线程池）；超时/失败都会记录日志：

```
[MCP] 调用留档：server=my-server, tool=read_file, elapsedMs=128ms, outLen=2048, truncated=false
[MCP] 调用超时熔断：server=my-server, tool=slow_tool, timeoutMs=30000
[MCP] 工具调用失败：server=my-server, tool=x, elapsedMs=52ms, reason=Connection refused
```

---

## 工具白名单（V2.0.1）

新增 MCP 服务器时可填 `toolAllow`，只桥接清单内的工具：

```bash
curl -X POST http://localhost:8081/api/settings/mcp-servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"name":"fs","transport":"http_sse","endpoint":"http://localhost:8001/sse",
       "enabled":true,"toolAllow":"read_file,list_dir"}'
```

**两种写法都支持**：JSON 数组（`["read_file","list_dir"]`）或逗号分隔（`read_file,list_dir`）。
留空/null = 不限（全量桥接）。

白名单存于 `mcp_server.tool_allow` 列，**重启后依然生效**（启动恢复时按同一清单过滤，不会退化为全量）。

---

## 风险级与执行策略（V2.1.0 起）

MCP 桥接工具默认风险级为 **`EXTERNAL`**。执行方式由策略决定：

| 配置 | 行为 |
|---|---|
| `GEWU_SANDBOX_EXTERNAL=direct`（**默认**，V2.2.0 起） | 有界直执行：超时 + 结果上限 + 审计留档 |
| `GEWU_SANDBOX_EXTERNAL=sandbox` | 需 `SandboxAdapter`；MCP 工具无适配器 → 逐次返回「暂无沙箱适配器」被拒 |
| `GEWU_SANDBOX_EXTERNAL=disabled` | 禁止调用 MCP 工具 |

> **升级提醒**：v2.1.0 曾把 `external` 默认设为 `sandbox` 而适配器只覆盖 calculator，
> 导致插件工具全部不可用（v2.2.0 已修正为 `direct`）。若你从 v2.1.0 升级且自行改过策略，
> 请确认 `GEWU_SANDBOX_EXTERNAL` 的取值。

**为什么不给 MCP 工具上进程沙箱**：MCP 执行本质是 `client.executeTool()` 纯 HTTP/SSE 调用，
本地不执行任何代码。JVM 隔离对**远端风险零收益**（远端服务做什么，本地沙箱管不了），
却要每次调用多付 JVM 启动与 MCP 握手开销。真正有效的是调用侧边界（超时/结果上限/审计）。

---

## 审计与排查

MCP 相关操作会进审计表（V2.4.0）：

| 动作 | 触发 |
|---|---|
| `plugin.install` / `plugin.uninstall` | 经插件仓库安装/卸载 MCP 插件 |
| `plugin.create` / `plugin.delete` | 插件条目增删 |

查询：`GET /api/settings/audit-logs?action=plugin.install`（ADMIN）。

排查调用问题时可结合 traceId：请求带 `X-Trace-Id`，MCP 调用日志与该请求其余日志共享同一值。
