# Gewu API 二开参考（T7）

> 面向二次开发者。基础地址 `http://<host>:8081`，所有接口（除 `/api/auth/login`、`/api/auth/register`）需 `Authorization: Bearer <token>`。统一返回结构 `R<T>`：

```json
{ "code": 200, "message": "success", "data": { }, "timestamp": "..." }
```

业务错误 `code != 200`；配额超限返回 `code = 429`；权限不足 `403`；未登录 `401`。

---

## 模块索引

| 模块 | Base 路径 | 说明 |
|---|---|---|
| 认证 | `/api/auth` | login/register/refresh/me/logout |
| 用户 | `/api/user` | profile/password |
| 对话 | `/api/chat` | 消息收发/会话管理/压缩与模型设置（27 端点，3 个控制器） |
| Agent | `/api/agent` | 对话（4 入口）/工具/审批/定义 CRUD |
| 知识库 | `/api/knowledge` | 文档上传/解析/检索 |
| 监控 | `/api/monitor` | 统计/日志/模型排行/用量报表（T6） |
| 模型配置 | `/api/settings` | Provider CRUD/激活/连通性测试（T3，仅 ADMIN） |
| 提示词 | `/api/prompt` | 模板 list/create/execute/rollback |
| MCP 服务器 | `/api/settings/mcp-servers` | MCP 连接配置 + 启停/测试（V1.1.0 C3，仅 ADMIN） |
| 技能库 | `/api/settings/skills` | SKILL.md 扫描/目录配置/重扫（V2.0.0 + V2.0.1，仅 ADMIN） |
| 插件仓库 | `/api/settings/plugins` | 插件清单/一键安装/卸载（V2.0.0，仅 ADMIN） |
| 工具风险 | `/api/settings/tool-risks` | 风险分级快照/提级/沙箱探针（V2.1.0 + V2.4.0，仅 ADMIN） |
| 审计日志 | `/api/settings/audit-logs` | 管理面操作留痕查询（V2.4.0，仅 ADMIN） |
| 可观测 | `/actuator` | health/metrics/prometheus（V2.2.0） |

---

## 对话（/api/chat）

> 实现侧拆为三个控制器（`ChatController` 消息链路 / `ChatSessionController` 会话生命周期 /
> `ChatSettingsController` 压缩与模式），共享 `/api/chat` 前缀，对外契约不变。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/session` | 创建会话 |
| POST | `/session/active` | 获取或创建（幂等复用） |
| GET | `/sessions` | 会话列表 |
| GET | `/session/{id}` | 会话详情 |
| GET | `/session/{id}/messages` | 历史消息 |
| GET | `/session/{id}/stats` | 会话 token 统计 |
| DELETE | `/session/{id}` | 删除会话 |
| POST | `/session/{id}/clear` | 清空历史 |
| POST | `/message` | 同步对话 |
| POST | `/message/stream` | 流式对话（SSE） |
<!-- 多模态预留说明 -->
> `POST /message` 与 `/message/stream` 接受 `useMultimodal` / `imageUrls` 字段，
> 但当前**仅按文本模式处理**（图像输入为预留能力，尚未实现）。
| POST | `/mode` | 切换会话模式 |
| GET | `/health` | 健康探活（激活模型 / provider / key 是否已配置） |
| GET | `/models` | 可用模型列表 |

### 会话管理扩展（V1.0.1 / V1.1.0）

| 方法 | 路径 | 说明 |
|---|---|---|
| PATCH | `/session/{sessionId}/title` | 重命名会话（body: remark） |
| PATCH | `/session/{sessionId}/group` | 设置分组（body: groupName） |
| DELETE | `/groups/{groupName}` | 解散分组（会话回落「默认」） |
| GET | `/groups` | 分组列表 |
| POST | `/session/{sessionId}/derive` | **派生子会话**（克隆父 systemPrompt + 上下文摘要；仅一层防循环） |
| POST | `/session/{sessionId}/compact` | 压缩上下文（生成摘要并入 `context_summary`，旧消息标记 compressed） |
| GET | `/session/{sessionId}/context-usage` | 上下文用量（供前端判断是否需压缩） |
| GET | `/settings/compact` | 读取压缩配置（autoCompact / 阈值 / 保留条数） |
| POST | `/settings/compact` | 保存压缩配置（用户级） |
| GET | `/commands` | 斜杠命令清单 |
| POST | `/command/stream` | 执行斜杠命令（`/clear` `/reset` `/compact`，Flux 流式返回） |
| GET / POST | `/mode` | 读取 / 切换会话模式 |
| POST / GET | `/model` | 切换 / 读取当前模型 |
| GET | `/models` | 可用模型列表 |

> 对话接口受**限流**保护（V2.2.0），见「限流与配额」节。
**流式事件**（`text/event-stream`，`data:` 行 JSON）：`delta`（增量文本）/ `done` / `error`。Agent 事件见下节。

---

## Agent（/api/agent）

### 对话入口（5 个，均受 T6 配额闸门保护）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat` | 核心对话：agentId 指定走定义，否则全量工具 |
| POST | `/chat/simple` | 简单模式 |
| POST | `/chat/smart` | 智能路由模式 |
| POST | `/chat/stream` | 流式（SseEmitter，`text/event-stream`） |
| POST | `/chat/stream/session` | 会话模式流式（需 sessionId，多轮累计 token） |

请求体（`AgentChatRequest`）核心字段：`message`（必填）、`agentId`、`sessionId`、`modelId`、`systemPrompt`、`knowledgeDocumentIds`（请求级 RAG 文档）、`ragTopK`、`userId`（废弃信任，服务端以登录态解析）。

### SSE 事件契约（十类，跨版本向后兼容）

| eventType | 字段 | 说明 |
|---|---|---|
| `iteration` | iteration | 迭代轮次（1 起） |
| `thought` | content, iteration | 模型思考文本 |
| `delta` | content, iteration | 增量文本（逐 token，前端累计拼接） |
| `action` | toolName, toolInput, iteration | 调用工具 |
| `tool_result` | toolName, content, iteration | 工具观察结果 |
| `approval_required` | toolCallId, toolName, toolInput | 工具需审批（等待期服务端发 `ping` 心跳） |
| `rag` | ragHits | RAG 注入：>0 命中 / 0 无命中 / -1 检索失败 |
| `done` | content, iteration, toolsUsed, modelId, inputTokens?, outputTokens? | 终态（会话模式带累计 token） |
| `ping` | — | 心跳保活 |
| `error` | content | 出错（流不中断，前端提示） |

> 兼容规则：新增字段只在末尾追加；新事件类型前端按未知类型忽略处理。

### 工具与审批

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tools` | 全量工具规格 |
| GET | `/available-tools` | 当前可用工具 |
| GET | `/approval/pending` | 待审批列表 |
| POST | `/approval/approve` | 批准（body: toolCallId） |
| POST | `/approval/reject` | 拒绝 |

### 定义 CRUD（模板即 `id` 前缀 `template-` 且 `enabled=false` 的定义）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/definitions` | 列表 / 创建 |
| POST | `/definitions/{id}` | 更新（body 带 id） |
| DELETE | `/definitions/{id}` | 删除 |
| GET | `/definitions/enabled-list` | 已启用列表 |
| POST | `/definitions/{id}/enabled` | 启停 |
| POST | `/definitions/reload` | 热重载 |

定义 `config` 核心字段：`modelId`、`maxIterations`、`temperature`、`maxTokens`、`requireApproval`（null=继承工具级 / true / false）、`knowledgeDocumentIds`（T5 定义级知识库绑定，请求级缺省时回退）、`ragTopK`（1-10，默认 4）。

---

## 知识库（/api/knowledge）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 上传文档（multipart：file；支持 md/txt/pdf/docx） |
| GET | `/documents` | 文档列表（`status`：0=解析中 1=失败 2=已索引） |
| GET | `/document/{id}` | 详情 |
| DELETE | `/document/{id}` | 删除（级联清向量） |
| GET | `/search?query=&topK=` | 向量检索（相似度阈值默认 0.3，`ragHits` 语义同上） |

> 解析为异步：上传后轮询 `/documents` 至 `status=2` 再可用于 RAG 绑定。embedding 模型切换后存量文档需重建索引（v1.0 文档化方案，见 `docs/V1.0-PLAN.md`）。

---

## 监控（/api/monitor）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats` | 今日统计（调用数/token/费用/均延/错误率） |
| POST | `/logs` | 调用日志分页（body: page/size/modelName/status/startDate/endDate） |
| GET | `/models` | 模型使用排行（今日） |
| GET | `/usage/report?view=&days=` | **T6 用量报表**：view=`day|user|agent`，days 默认 7 上限 90 |

`usage/report` 返回 `UsageReportView[]`：`groupKey`（日期/userId/templateName）、`label`（用户名，可空）、`callCount`、`inputTokens`、`outputTokens`、`totalTokens`、`totalCost`。数据源 `llm_call_log` 单表聚合；`template_name` 语义：`agent:{agentId}` / `agent-smart` / 其他为普通 chat。

### 配额（T6，env 配置）

```yaml
gewu:
  quota:
    enabled: false                  # 默认关闭，零配置兼容
    daily-token-limit: 200000       # 每用户每日 token 上限
```

环境变量：`GEWU_QUOTA_ENABLED`、`GEWU_QUOTA_DAILY_TOKEN_LIMIT`。生效范围：上述 5 个对话入口；userId 由服务端从 SecurityContext 解析（请求体 userId 不受信任）；超限抛 `BizException(429)`，**不做预扣**。

---

## 模型配置（/api/settings，仅 ROLE_ADMIN，T3）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/providers` | 列表（key 只回 `sk-****` 掩码）/ 保存（加密落库，保存即生效） |
| POST | `/providers/{id}` | 更新 |
| DELETE | `/providers/{id}` | 删除（激活配置删除后回退 yml activeModel） |
| POST | `/providers/{id}/activate` | 激活（chat 即时生效；embedding 见重建方案） |
| POST | `/providers/{id}/test` | 连通性测试（chat 微补全 / embedding 测维度） |

密钥安全：AES-256-GCM（`v1:iv:ct`），密钥 env `GEWU_ENCRYPT_KEY`（`openssl rand -base64 48` 生成；未配置回退开发密钥，**生产必须配置**）。首次启动自动把 yml 内置模型种子导入 `llm_provider` 表。

---

## 提示词（/api/prompt）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/list` | 模板列表 |
| POST | `/create` | 新建模板（版本化） |
| POST | `/execute?name=` | 按模板渲染执行 |
| POST | `/rollback?name=&version=` | 回滚版本 |
| GET | `/{name}` | 模板详情 |

---

## MCP 服务器（/api/settings/mcp-servers，仅 ROLE_ADMIN，V1.1.0 C3）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mcp-servers` | 配置列表 |
| POST | `/mcp-servers` | 新增（启用即连接 + 桥接工具） |
| POST | `/mcp-servers/{id}` | 更新 |
| DELETE | `/mcp-servers/{id}` | 删除（断开 + 注销工具） |
| POST | `/mcp-servers/{id}/enable` | 启用（连接 + 桥接） |
| POST | `/mcp-servers/{id}/disable` | 停用（断开 + 注销） |
| POST | `/mcp-servers/{id}/test` | 连通性测试（探测即断，不改状态） |

字段：`name`（唯一）、`transport`（首版仅 `http_sse`）、`endpoint`（SSE URL）、`enabled`、`toolAllow`（工具白名单，JSON 数组或逗号分隔；空=全量桥接，V2.0.1）。详见 [MCP.md](./MCP.md)。

---

## 技能库（/api/settings/skills，仅 ROLE_ADMIN，V2.0.0 + V2.0.1）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/skills` | Skill 列表（扫描目录下各子目录的 SKILL.md，解析 frontmatter 与指令段） |
| GET | `/skills/dir` | 当前生效目录 + 是否可读 |
| POST | `/skills/dir` | 运行时切换目录并立即重扫（body: `{"dir":"..."}`；仅内存态，重启回退配置值） |
| POST | `/skills/rescan` | 手动重扫（返回 `count` 与 `dir`） |

Skill 以「提示词包」形态注入 system prompt（只读，不执行其中指令）。目录配置见 [DEPLOY.md](./DEPLOY.md) `GEWU_SKILLS_DIR`。

---

## 插件仓库（/api/settings/plugins，仅 ROLE_ADMIN，V2.0.0）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/plugins` | 插件清单（内置样例首访自动灌入） |
| POST | `/plugins` | 新增自定义条目（`code`/`name`/`transport`/`endpoint`/`toolAllow`） |
| POST | `/plugins/{id}/install` | 一键安装（登记进 `mcp_server` 并桥接工具） |
| POST | `/plugins/{id}/uninstall` | 卸载（删除登记 + 断开桥接） |
| DELETE | `/plugins/{id}` | 删除条目（内置不可删；已安装需先卸载） |

内置样例：`filesystem` / `fetch` / `time` / `memory`（端点为占位，需按 [PLUGIN.md](./PLUGIN.md) 改为真实地址）。

---

## 工具风险分级（/api/settings/tool-risks，仅 ROLE_ADMIN，V2.1.0 + V2.4.0）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tool-risks` | 风险级快照 + 执行策略 + 沙箱健康状态 |
| POST | `/tool-risks/{toolName}` | 设置某工具风险级（body: `{"risk":"SAFE\|DYNAMIC\|EXTERNAL\|DANGEROUS"}`） |
| POST | `/tool-risks/sandbox/probe` | 沙箱诊断（固定动作：`health` / `timeout` / `crash` / `tool`） |

风险级语义：`SAFE`（只读）· `DYNAMIC`（含动态求值）· `EXTERNAL`（外部来源）· `DANGEROUS`（高危，无条件需审批）。执行策略由 `gewu.sandbox.policy.*` 决定（`direct`/`sandbox`/`disabled`）；沙箱不可用时按 `on-unavailable` 处理（默认 `reject`，fail-closed）。

---

## 审计日志（/api/settings/audit-logs，仅 ROLE_ADMIN，V2.4.0）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/audit-logs` | 查询审计记录 |

查询参数：`actor`（操作者）、`action`（如 `agent.create`）、`targetType`、`from` / `to`（ISO 时间）、`limit`（默认 100，上限 200）。

覆盖动作：`agent.create|update|delete|enable|disable`、`provider.create|update|delete|activate`、`plugin.create|install|uninstall|delete`、`tool.risk.set`、`session.clear|delete`、`group.dissolve`。`detail` 中密钥类字段（键名含 key/secret/token/password 等）一律掩码 `***`。

---

## 可观测（/actuator，V2.2.0）

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/actuator/health` | 公开 | 健康检查（容器探针用；详情需 ADMIN） |
| GET | `/actuator/metrics` | ADMIN | Micrometer 指标 |
| GET | `/actuator/prometheus` | ADMIN | Prometheus 格式指标 |

**traceId 贯穿**：请求可带 `X-Trace-Id`（缺失则自动生成 16 位），响应头回写；同一请求的全部日志可用该值 grep 串联。沙箱子进程亦透传同一 traceId。

---

## 限流与配额（V2.2.0）

| 层 | 触发条件 | 响应 |
|---|---|---|
| 限流 | 对话类接口超出令牌桶速率（`/api/chat*`、`/api/agent/chat*`） | `429` + `Retry-After: <秒>` + `{"code":429,"message":"请求过于频繁，请 N 秒后重试"}` |
| 配额 | 用户当日 token 用量超上限 | `429` + `{"code":429,"message":"今日 token 配额已用完（used/limit）"}` |

**分层语义**：限流管**速率**（先行拒绝，被限请求不消耗配额）；配额管**每日总量**。两者错误码同为 429，靠 message 区分。阈值见 [DEPLOY.md](./DEPLOY.md) `GEWU_RATELIMIT_*`。

---
## 认证（/api/auth）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/login` | 登录（返回 JWT） |
| POST | `/register` | 注册 |
| POST | `/refresh` | 刷新令牌 |
| GET | `/me` | 当前用户 |
| POST | `/logout` | 登出（服务端令牌失效，替代 Redis 的 DB 校验） |

---

## 扩展阅读

- 自定义工具开发：`docs/EXTEND-TOOL.md`
- 架构总览：`docs/ARCHITECTURE.md`
- 部署：`docs/DEPLOY.md`
- 版本规划：`docs/plan/ROADMAP.md` + `docs/plan/Vx.y.z-PLAN.md`
- 架构决策记录：`docs/decisions/`（ADR）
- MCP 接入：`docs/MCP.md`
- 插件仓库：`docs/PLUGIN.md`
