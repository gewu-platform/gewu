# Gewu 架构总览（T7）

> 面向二次开发者的架构地图。项目为**企业级 AI Agent 平台**（品牌名 Gewu / 格物），后端 Java 17 + Spring Boot 3 + LangChain4j，前端 Vue 3 + Ant Design Vue。

## 模块布局（Maven 多模块）

```
gewu/
├── gewu-common/        通用层：R 统一返回、BizException、ErrorCode、GewuCrypto（AES-256-GCM）
├── gewu-client/        LLM 客户端层：LangChain4jConfig 动态工厂 + EmbeddingModelConfig
├── gewu-web/           业务主模块（Spring Boot 启动类）
│   └── src/main/java/com/gewu/
│       ├── framework/          安全配置（SecurityConfig/JWT）
│       ├── infrastructure/      JWT 过滤器、UserDetailsService、AdminAccounts
│       └── module/
│           ├── auth/            登录注册（JWT + DB 令牌校验）
│           ├── user/            用户资料
│           ├── chat/            会话/消息/多模态对话/SSE
│           ├── agent/           编排核心（见下）
│           ├── knowledge/       知识库（上传/解析/向量检索）
│           ├── monitor/         监控 + 用量报表 + 配额（T6）
│           ├── settings/        模型配置中心（T3）
│           └── prompt/          提示词模板（版本化）
├── database/gewu-init.sql   单文件真相源（幂等，可重跑 0 ERROR）
└── docs/                    本文档套件
```

前端：`gewu-web`（Vue 3 + Vite，`src/views/` 每页面一个 View，`src/api/` 每模块一个 api 文件）。

## Agent 编排核心（module/agent）

```
AgentController（/api/agent/**，5 个对话入口）
      │
      ▼
AgentService.chatStream()          编排循环（Flux + SseEmitter 直写）
  ├─ resolveModel()                模型解析（请求指定 > 定义 > 激活）
  ├─ AgentRegistry                 定义注册中心（模板= enabled=false 的定义）
  │    └─ getToolSpecifications()  定义级工具绑定
  ├─ ToolRegistry                  工具注册中心（@Tool 注解反射 → Specification+Executor）
  ├─ ToolApprovalGateway           审批闸门（approval_required / ping 心跳 / approve|reject）
  ├─ RAG 注入                      knowledgeDocumentIds（请求级 > 定义级回退）→ 检索 → SystemMessage 附加
  └─ LLM 循环                      function calling 至终态，事件逐个推送
      │
      ▼
AgentStreamResponse（十类 SSE 事件，向后兼容契约）
```

**关键设计约束**：

1. **SSE 直写**：`SseEmitter` 在 boundedElastic 后台线程逐事件 `send`——返回 Flux 会被 MVC 整体缓冲（实测一次性冲出），不得回退。
2. **深度思考压制**：`LangChain4jConfig.thinkingDisabledParams()` 针对火山 doubao-seed 的思考参数压制，工厂重构必须保留。
3. **事件契约向后兼容**：新字段只在末尾追加，前端按未知事件类型忽略。

## LLM 客户端层（gewu-client）

`LangChain4jConfig` 是**配置驱动的工厂**（T3 后 DB 优先 + yml 兜底）：

- `chatClientCache` / `streamingCache` 双缓存池：按 modelId 缓存构建好的 `ChatModel`/`StreamingChatModel`
- `getModels()`：合并 yml 内置模型与 `llm_provider` 表激活配置；`getActiveModel()` 返回当前激活
- `clearFactoryCaches()`：模型配置变更后清缓存（保存即生效的关键）
- `ModelProviderService`（settings 模块）：DB 配置的版本化缓存，embedding 侧同样走动态工厂

模型配置生命周期：`POST /api/settings/providers`（key AES-256-GCM 加密落库）→ activate → 缓存清理 → 下次对话用新模型。

## 数据流（一次 Agent 对话）

```
前端 → POST /api/agent/chat/stream（JWT）
  → JwtAuthenticationFilter（U4：Redis 令牌校验快路径，未命中/不可用回落 DB）
  → UsageQuotaService.checkQuota（T6：SecurityContext 解析 userId，超限 429）
  → AgentService.chatStream
      → 会话持久化（chat_session/chat_message）
      → RAG 检索（pgvector，llm_provider 激活的 embedding 模型）
      → LLM function-calling 循环（工具经 ToolRegistry 执行，审批经 Gateway）
      → llm_call_log 异步落库（token/费用/延迟）
  → SSE 十类事件流 → 前端渲染
```

## 数据模型（PostgreSQL + pgvector，12 表）

| 表 | 说明 |
|---|---|
| `sys_user` / `sys_token` | 用户与令牌 |
| `chat_session` / `chat_message` | 会话与消息（message 含 multimodal image_urls JSONB） |
| `token_usage` | token 计量（报表主数据源为 llm_call_log，此表保留对账） |
| `llm_call_log` | LLM 调用日志（user_id/template_name/tokens/cost/latency，T6 聚合源） |
| `knowledge_document` / `knowledge_chunk` | 文档与切片（chunk 含 pgvector 向量列） |
| `llm_provider` | 模型配置中心（chat|embedding 双类型，AES 密文，激活唯一部分索引） |
| `prompt_template` | 提示词模板（版本化） |
| `agent_definition` 等 | Agent 定义持久化（运行时内存注册中心为主） |

初始化：`database/gewu-init.sql` 单文件真相源，幂等重跑 0 ERROR；升级只跑该 SQL。

## 安全模型

- `SecurityConfig`：`anyRequest().authenticated()`；`/api/auth/login|register` 放行；`/api/settings/**` 需 `ROLE_ADMIN`
- JWT（HS256）+ DB 令牌状态校验（`sys_token`，登出即失效）
- 密钥类配置（`GEWU_ENCRYPT_KEY`）只进不出：接口仅回 `sk-****` 掩码
- 配额：服务端解析 userId，请求体 `userId` 字段不受信任（T6）

## 扩展点

- **新工具**：`docs/EXTEND-TOOL.md`（@Component + @Tool 即插即用）
- **新模型**：`/api/settings/providers` 动态接入，无需改代码
- **新 API**：API 契约见 `docs/API.md`；SSE 事件扩展遵守向后兼容规则
- **前端页面**：`src/views/` 加 View + 路由；`src/api/` 对应模块文件加函数

## 部署形态

后端 jar（Dockerfile 多阶段 + layertools 分层）+ `pgvector/pgvector:pg16`；前端 nginx 托管 dist + `/api` 反代（SSE 需 `proxy_buffering off`）。详见 `docs/DEPLOY.md`。

## U4 Redis 中间件

用途（纯缓存/加速层，DB 始终为真相源）：

| 场景 | 键 | 策略 |
|---|---|---|
| 令牌状态校验 | `gewu:token:{sha256}` | TTL=剩余有效期；登出/换新即删 |
| 登录限流滑动窗口 | `gewu:throttle:{username\|ip}` | ZSET + TTL=窗口长度 |
| 每日配额计数 | `gewu:quota:{userId}:{date}` | INCRBY + 当日到期；快速拒绝超限请求 |
| 用户设置缓存 | `gewu:usetting:{userId}` | cache-aside，TTL 30min，写后删 |

降级：Redis 不可达时全部操作自动回落 DB/内存原路径（60s 失败冷却），`gewu.redis.enabled=false` 可整体关闭。
