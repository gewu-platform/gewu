# Gewu 架构总览（T7）

> 面向二次开发者的架构地图。项目为**企业级 AI Agent 平台**（品牌名 Gewu / 格物），后端 Java 17 + Spring Boot 3 + LangChain4j，前端 Vue 3 + Ant Design Vue。

## 模块布局（Maven 多模块）

```
gewu/
├── gewu-common/        通用层：R 统一返回、BizException、ErrorCode、GewuCrypto（AES-256-GCM）
├── gewu-client/        LLM 客户端层：LangChain4jConfig 动态工厂 + EmbeddingModelConfig
├── gewu-web/           业务主模块（Spring Boot 启动类）
│   └── src/main/java/com/gewu/
│       ├── config/           @Configuration 类：Async / MybatisPlus / Scheduling
│       ├── infrastructure/   基础设施域（有运行时行为，非配置）
│       │   ├── security/       JWT 过滤器、UserDetailsService、SecurityConfig、登录限速
│       │   ├── ratelimit/      令牌桶限流（RateLimitService + Filter）
│       │   ├── tracing/        TraceIdFilter（链路 ID）
│       │   └── redis/          Redis 缓存服务（可选加速层）
│       └── module/           业务域（依赖方向见下「模块依赖矩阵」）
│           ├── auth/            登录注册（JWT + DB 令牌校验）→ user
│           ├── user/            用户资料 + 用户设置（基础域，无反向依赖）
│           ├── chat/            会话/消息/多模态对话/SSE → monitor, user
│           ├── agent/           编排核心（见「Agent 编排核心」）
│           ├── knowledge/       知识库（上传/解析/向量检索）→ setting
│           ├── monitor/         监控 + 用量报表 + 配额（T6）→ user
│           ├── audit/           审计日志（V2.4.0 C2，横切关注点，零业务模块依赖）
│           ├── setting/         模型配置中心（T3）+ 技能库（V2.0.0）
│           ├── mcp/            MCP 客户端与桥接（V1.1.0 C3）→ agent
│           ├── plugin/         插件仓库（V2.0.0）→ mcp
│           └── prompt/         提示词模板（版本化）
├── database/gewu-init.sql   单文件真相源（幂等，可重跑 0 ERROR）
├── database/migrations/     Flyway 增量（已有库升级路径）
├── docker/                  构建入口索引（README.md）+ jar/ 预打包产物；
│                            配置文件仍在根目录（.dockerignore/.env/compose 路径基准约束）
└── docs/                    本文档套件

### 模块依赖矩阵（2026-11-03 重构后，零循环）

```
agent     → chat, knowledge, monitor
audit     → (独立)
auth      → user
chat      → monitor, user
knowledge → setting
mcp       → agent
monitor   → user
plugin    → mcp
prompt    → (独立)
setting   → (独立)
user      → (独立)
```

**规则**：依赖只能自上而下指向更基础的域，禁止反向与横向双向。
`auth → user`、`monitor → user` 这类单向依赖是正确方向；
历史问题（`auth ↔ user` 双向、`monitor → auth` 倒挂）已在 V2.5.0 重构中消除。
```

前端：`gewu-web`（Vue 3 + Vite，`src/views/` 每页面一个 View，`src/api/` 每模块一个 api 文件）。

## Agent 编排核心（module/agent）

### agent 子包结构

```
module/agent/
├── controller/   AgentController（/api/agent/** 5 个对话入口）+ SkillLibraryController
│                 + ToolRiskController
├── service/      AgentService（编排主循环，门面）
│                 AgentApprovalWaiter（审批挂起/唤醒）
│                 AgentExecutionService（checkpoint 持久化）
├── dispatch/     派发契约（SubAgentRunner 接口 / DispatchContext / DispatchScope /
│                 SubAgentResult）—— tools 与 service 的解耦点
├── tools/        工具实现（@Tool 注解，**必须在此包**，见下方陷阱说明）+ ToolRegistry
│                 + ToolRisk / SandboxPolicy（风险与执行策略）
├── sandbox/      进程级沙箱（SandboxRunner / SandboxWorker / SandboxAdapter 及实现）
├── registry/     AgentRegistry（定义注册中心，模板 = enabled=false 的定义）
├── gateway/      ToolApprovalGateway（审批闸门，new 创建非 Spring Bean）
├── skill/        SkillLibraryService（公共 Skill 库，只读扫描）
├── support/      AgentTextUtils（纯函数：JSON/截断/token 估算）
├── definition/   AgentDefinition / AgentConfig
├── dto/          AgentChatRequest / AgentStreamResponse 等
├── entity/       AgentDefinitionEntity / AgentExecution
├── mapper/       AgentDefinitionMapper / AgentExecutionMapper
└── schedule/     CheckpointScheduler（过期审批清理）
```

> ⚠️ **陷阱：工具类必须留在 `module/agent/tools` 包**。
> `ToolRegistry.autoScanRegister()` 用硬编码前缀 `"com.gewu.module.agent.tools."` 过滤
> `@Component` Bean。任何工具实现类移出该包，自动扫描会**静默失效**——编译通过、
> 单测可能通过，但运行时该工具根本不存在。若确需调整，必须同步修改该判断。

> **依赖方向**：`tools → dispatch ← service`，`service → tools`。
> 工具不直接依赖 service（历史问题 `service ↔ tools` 双向依赖已消除）。


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

## 数据模型（PostgreSQL + pgvector，16 表）

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

---

## 沙箱与风险分级（V2.1.0 / V2.2.0 / V2.4.0）

### 风险分级

`ToolRisk` 四级，由 `ToolRegistry` 维护「工具名 → 风险级」注册表：

| 级别 | 含义 | 默认可由 |
|---|---|---|
| `SAFE` | 只读/纯计算 | datetime、timezone、search、agent_dispatch |
| `DYNAMIC` | 含动态求值 | calculator（走脚本引擎 eval） |
| `EXTERNAL` | 外部来源 | MCP 桥接工具（`registerDirect` 默认） |
| `DANGEROUS` | 显式高危 | 需人工提级，无条件需审批 |

### 执行策略与 fail-closed

`SandboxPolicy` 按风险级决定执行方式（`direct` / `sandbox` / `disabled`），
管理接口：`GET /api/settings/tool-risks`（快照）/ `POST /api/settings/tool-risks/{toolName}`（提级）/
`POST /api/settings/tool-risks/sandbox/probe`（沙箱诊断）。
`ToolRegistry.execute` 前置拦截：策略要求沙箱但沙箱不可用时**默认拒绝**（fail-closed），
不静默降级。开启 `allow-direct` 才放行，且打 ERROR 级「安全降级」强告警。

### 进程级沙箱运行时

| 组件 | 职责 |
|---|---|
| `SandboxAdapter` | 适配器接口（toolName / risk / prepare / normalized）——**新增工具零改 Runner** |
| `CalculatorSandboxAdapter` / `DateTimeSandboxAdapter` | 具体适配器实现 |
| `SandboxRunner` | spawn 子 JVM、超时强杀、排水线程、启动自检、classpath 自适应（dev / fat-jar） |
| `SandboxWorker` | 子进程工作类：stdin 收任务 → 执行 → stdout 回结果；无 Spring/密钥/DB |

**安全边界**：表达式白名单收敛（阻断 `Java.type`、标识符、多语句注入）、200 字符上限、
`-Xmx` 内存上限、超时 `destroyForcibly` 强杀、结果长度上限、全程审计留档。

**容器级沙箱**：已评估，**实施延后**（本机无可用容器运行时；进程级已覆盖当前威胁面）。
决策记录见 `docs/decisions/0001-容器级沙箱评估.md`。

---

## 审计日志（V2.4.0）

`audit_log` 表 + `AuditService`，记录管理面操作：谁、何时、对什么、做了什么、成功与否。

| 覆盖域 | 动作 |
|---|---|
| Agent 定义 | `agent.create/update/delete/enable/disable` |
| 模型与密钥 | `provider.create/update/delete/activate` |
| 插件仓库 | `plugin.create/install/uninstall/delete` |
| 工具风险 | `tool.risk.set` |
| 会话与分组 | `session.clear/delete`、`group.dissolve` |

**设计约束**：

- `detail` 中键名含 key/secret/token/password 等字段值一律掩码 `***`（密钥不落审计表）
- 长度上限 2000（超长截断）
- **旁路失败只 WARN 不阻断业务**——审计不能回滚或阻断主流程
- IP 优先取 `X-Forwarded-For` 首地址，回落 `remoteAddr`（与限流一致）

---

## 可观测（V2.2.0）

| 组件 | 职责 |
|---|---|
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | 健康与指标端点 |
| `TraceIdFilter`（HIGHEST_PRECEDENCE） | 透传/生成 traceId → MDC → 日志 `%X{traceId}`；finally 清理防线程池串号；响应头回写 |

**暴露面**：`/actuator/health` 公开（容器探针），其余 `/actuator/**` 需 ADMIN。

**traceId 贯穿全链路**：HTTP 请求 → 业务服务 → 沙箱子进程（三段协议透传），
沙箱内日志与父进程日志可用同一 traceId 关联。上游 traceId 会做清洗（仅留字母数字与连字符、限长 64）防日志注入。

---

## 限流（V2.2.0）

| 组件 | 职责 |
|---|---|
| `RateLimitService` | 进程内令牌桶（按流逝时间补充、容量封顶、CHM + 桶内同步、5 分钟回收空闲桶） |
| `RateLimitFilter`（@Order 0，Security 之后） | 仅拦 `/api/chat*`、`/api/agent/chat*`；已认证按 username、未认证回落 IP |

**与配额分层**：限流管**速率**（先行拒绝，不占配额），配额管**每日总量**。

**选型说明**：用进程内令牌桶而非 Redis——本项目 Redis 是可选加速层（不可达自动降级），
限流若强依赖 Redis，会在 Redis 故障时丧失保护。代价是多实例部署时各实例独立计数，
全局精确限流需改 Redis 实现（见 ROADMAP V2.5.0）。

---

## 数据库迁移（V2.3.0）

**双轨契约**（关键设计取舍）：

| 文件 | 角色 | 说明 |
|---|---|---|
| `database/gewu-init.sql` | **全量快照** | 建库真相源，含**所有迁移产物**（当前 16 张表）；Docker compose 与空库初始化用它，一次建全 |
| `database/migrations/V{n}__*.sql` | **增量历史** | 已有库的升级路径；`baseline-version=1` 后只应用增量 |

**同步规则（必守）**：每次新增迁移后，**必须同步更新 `init.sql`**，保持快照完整。
否则新库必须依赖 Flyway 跑完才有完整结构——这是依赖启动顺序的隐性坑。
两边都使用 `IF NOT EXISTS`，重复执行安全。

- `database/gewu-init.sql`：**建库真相源**（Docker compose / 空库初始化），16 表全量幂等
- `database/migrations/V{n}__*.sql`：**Flyway 增量**，只管 V2+ 变更（与 init.sql 同目录）

`baseline-on-migrate=true` + `baseline-version=1`：已有库首次启动打基线，仅应用增量；

**路径解析**：Flyway 使用 `filesystem:database/migrations`（相对进程工作目录，可用
`GEWU_FLYWAY_LOCATIONS` 覆盖为绝对路径）。因此**必须在仓库根目录启动**，或显式指定绝对路径；
容器镜像已内置 `./database/migrations` 并支持只读挂载覆盖。

增量 SQL 必须幂等（`IF NOT EXISTS`），与手工应用历史兼容。
