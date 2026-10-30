# 插件仓库使用指南（V2.0.0 R3）

格物 Gewu 支持接入常用插件仓库的 plugin 插件。**首版插件形态 = 外部 MCP server**，
复用 V1.1.0 C3 的 MCP 客户端（HTTP SSE 传输）与工具桥接能力。

插件仓库本身不重新实现连接逻辑：仓库只维护「清单」，
**一键安装 = 把条目登记进 `mcp_server` 并桥接工具**（C3 原语）。

---

## 一、概念区分

| 概念 | 位置 | 作用 |
|---|---|---|
| 插件清单（plugin_registry） | 设置 → 插件仓库 | 常用插件目录，记录编码/描述/端点/工具白名单 |
| MCP 服务器（mcp_server） | 设置 → MCP 服务器（C3） | 真实连接配置，启用即连接 + 桥接工具 |
| 工具桥接（ToolRegistry） | 运行时 | 工具以 `mcp_{server}_{tool}` 命名进入 Agent 可调用集 |

安装动作会在两者间建立关联（`plugin_registry.installed_name` 指向 `mcp_server.name`），
卸载则删除该 MCP 登记并注销工具。

---

## 二、快速开始

### 1. 浏览与安装（ADMIN 角色必需）

管理端登录 →「系统设置 → 插件仓库」，或调 API：

```bash
# 查看清单（内置样例首次访问自动灌入）
curl http://localhost:8081/api/settings/plugins -H "Authorization: Bearer <token>"

# 一键安装（登记进 mcp_server 并桥接工具）
curl -X POST http://localhost:8081/api/settings/plugins/1/install \
  -H "Authorization: Bearer <token>"
```

> 内置样例（filesystem / fetch / time / memory）的**端点是占位地址**，
> 直接安装会连接失败。请先按第四节自建 server，再把端点改成真实地址。

### 2. 卸载与删除

```bash
# 卸载（删除 mcp_server 登记，断开桥接）
curl -X POST http://localhost:8081/api/settings/plugins/1/uninstall \
  -H "Authorization: Bearer <token>"

# 删除条目（内置不可删；已安装需先卸载）
curl -X DELETE http://localhost:8081/api/settings/plugins/1 \
  -H "Authorization: Bearer <token>"
```

### 3. 新增自定义插件

```bash
curl -X POST http://localhost:8081/api/settings/plugins \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"code":"my-plugin","name":"我的插件","description":"自建服务","transport":"http_sse","endpoint":"http://localhost:9001/sse"}'
```

字段说明：

| 字段 | 说明 |
|---|---|
| code | 插件编码，仓库内唯一；同时作为安装后的 `mcp_server.name` |
| transport | 首版仅支持 `http_sse` |
| endpoint | SSE URL |
| tool_allow | 工具白名单 JSON 数组；空 = 不限（全部桥接） |
| builtin | 内置样例标记，系统预置不可删除 |

---

## 三、工具命名与审批

桥接进 ToolRegistry 的工具命名为 `mcp_{server}_{tool}`。
（规划中原定 `mcp:{server}:{tool}`，因 langchain4j 工具名不接受冒号，实现改为下划线，文档以实际实现为准。）

工具调用受既有审批网关管控，建议为插件工具配置审批策略，避免外部副作用未确认即执行。

---

## 四、自建 MCP server 接入样例（http_sse）

以 Node 为例，起一个暴露 SSE 端点的参考 server：

```js
// server.js —— 最小 SSE MCP server 示例（示意）
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js'
import express from 'express'

const server = new McpServer({ name: 'my-plugin', version: '1.0.0' })
server.tool('echo', { text: {} }, async ({ text }) => ({
  content: [{ type: 'text', text: 'echo: ' + text }]
}))

const app = express()
app.get('/sse', async (req, res) => {
  const transport = new SSEServerTransport('/messages', res)
  await server.connect(transport)
})
app.post('/messages', express.json(), (req, res) => res.status(200).end())
app.listen(9001, () => console.log('MCP SSE on http://localhost:9001/sse'))
```

启动后：

1. 在插件仓库把该条目的端点改为 `http://localhost:9001/sse`
2. 点「安装」→ 后端连接并桥接 `echo` 工具
3. 在 Agent 定义中绑定该工具，对话即可调用

---

## 五、安全边界

- 插件工具是**真实外部能力**，务必关注权限与副作用（写文件、发请求等）
- 建议：仅安装可信来源 server；为插件工具开启审批；端点不要暴露到公网
- 卸载即断开连接并注销工具，不留运行态残留

---

## 六、关联文档

- MCP 客户端基础能力：[MCP.md](./MCP.md)（V1.1.0 C3）
- 多 Agent 主从编排：[plan/V2.0.0-PLAN.md](./plan/V2.0.0-PLAN.md)
