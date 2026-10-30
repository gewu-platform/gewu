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
