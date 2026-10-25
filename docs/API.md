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
| 对话 | `/api/chat` | 会话管理 + 同步/流式消息 + 多模态 |
| Agent | `/api/agent` | 对话（4 入口）/工具/审批/定义 CRUD |
| 知识库 | `/api/knowledge` | 文档上传/解析/检索 |
| 监控 | `/api/monitor` | 统计/日志/模型排行/用量报表（T6） |
| 模型配置 | `/api/settings` | Provider CRUD/激活/连通性测试（T3，仅 ADMIN） |
| 提示词 | `/api/prompt` | 模板 list/create/execute/rollback |

---

## 对话（/api/chat）

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
| POST | `/mode` | 切换会话模式 |
| GET | `/models` | 可用模型列表 |
| POST | `/upload` | 上传多模态图片 |

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

`usage/report` 返回 `UsageReportDTO[]`：`groupKey`（日期/userId/templateName）、`label`（用户名，可空）、`callCount`、`inputTokens`、`outputTokens`、`totalTokens`、`totalCost`。数据源 `llm_call_log` 单表聚合；`template_name` 语义：`agent:{agentId}` / `agent-smart` / 其他为普通 chat。

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
- 版本规划：`docs/V1.0-PLAN.md`
