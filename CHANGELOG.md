# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **插件安装语义**：由「连接成功才登记」改为「登记 + 尽力连接」，占位端点不再阻塞安装；返回 `connected` 标记供前端区分提示
- **公共 Skill 库**：容器化部署下扫描目录不存在（宿主机路径硬编码），改为宿主机目录只读挂载至 `/app/skills` + `GEWU_SKILLS_DIR` 注入
- **对话限流**：只读 GET 不再消耗对话令牌桶（此前一次页面加载即耗尽容量 10）
- **模型 Key 链路**：compose 补 `VOLC_API_KEY` 透传，修复 `llm_provider` seed 跳过导致对话 `AuthenticationError`
- **设置页布局**：修正模板 `</div>` 提前闭合致「插件仓库/公共 Skill」脱离滚动容器（表现为内容区塌陷、区块固定）
- **设置页样式**：补全 `.btn-sm`/`.td-actions` 等缺失样式；插件表单字段卡片化；Skill 清单渲染
- **知识库页空白**：补 `computed` 导入
- **新增插件报错**：补 `createPlugin` 导入

### Changed
- 品牌视觉对齐 FastGPT 设计语言：`--accent` → `#3370FF`，渐变收敛 12 处
- 数据库名全面统一为 `gewu`（本机与部署一致），`gewu-init.sql` 头部重建（品牌化 + 补建库步骤 + 双轨维护约定）
- 清除源码注释中 `java-ai-native` 溯源引用（95 文件）

### Docs
- 新增 `docs/plan/V2.7.0-PLAN.md`（UI 设计系统化 + 部署形态复盘 + 18 项问题清单）
- 新增 `docs/decisions/0002-部署形态评估.md`（容器化合理性评估）

## [v2.6.0] - 2026-11-05

### Added
- **国际化（i18n）**: 前端整体双语化——vue-i18n@9 基建（组合式 + globalInjection）、
  语言包 zh-CN/en-US 按模块组织（nav/common/header/login/model/approval/placeholder/
  reasoning/promptView/knowledge/monitor/chat），浏览器语言自动探测 + localStorage 持久化，
  AntD ConfigProvider 组件库随语言切换，头部语言切换器（中 / EN）
- **英文版文档**: 后端仓库 README.en.md（157 行，与中文版逐节对应）+
  前端仓库 README.en.md（功能全景/技术栈/Quick Start/项目结构），双仓库顶部互设语言切换链接

### Migrated
- 视图文案迁移到 $t()（分五子批，每批构建验证）：ModelView/ApprovalCard/PlaceholderView/
  ReasoningTimeline（3a，45 行）→ PromptView/KnowledgeView（3b，122 行）→ MonitorView（3c，45 行）
  → ChatView（3c-Chat，128 行，`63d45db`）→ AgentView/SettingsView（3d，304 行，`d3ad84b`）
- 语言包 agent/settings 模块注入（终态 570/570 键级对称）；agent/settings 269 键全消费，
  注入预设孤儿键清理 22 个
- SettingsView tabs（categories）数组 computed 化，语言热切换生效
- script 区错误消息统一改用组合式 t()（$t 仅模板可用）

### Fixed
- MinioFileStorage 双构造器致 Spring 启动失败（冒烟实测发现）：主构造器加 @Autowired
- DEPLOY.md 模式 B 构建命令为 bash 语法（PowerShell 报错）：补双语法对照与 FAQ Q8/Q9
- CI 持续失败（每次推送均报错）：前端 setup-node 缺 cache-dependency-path（子目录检出陷阱）、
  后端 dependency-check --failOnCVSS 9 与「报告型不阻断」注释矛盾；
  修复过程中引入的 env 缩进错误亦已归位
- docker/jar/README 与 docker/README 职责重叠：收敛为短指针

### Docs
- DEPLOY.md 补「文件存储 MinIO」小节与架构图更新（V2.5.0 收尾）
- 数据库双轨契约（init.sql 全量快照 + migrations 增量）写入 ARCHITECTURE/DEPLOY
- CHANGELOG 补 v2.2.0~v2.5.0 段；文档用语规范（中性表述）写入 AGENTS.md

## [v2.5.0] - 2026-11-04

### Added
- **M1 图片对话（多模态）**: `/api/chat/upload` 图片上传端点（jpg/jpeg/png/webp/gif，≤10MB，扩展名白名单 + MIME 前缀双保险）；多模态消息链路消费（`buildUserMessage` 构造 TextContent+ImageContent 混合消息，图片经 FileStorage 读取后以 base64 传入模型——云端视觉模型无法访问自部署 MinIO，故不能传 URL）；图片能力门禁（纯文本模型发图返回 400 可读提示，Provider 新增 `capability` 字段，安全默认 text）；前端 Ctrl+V 粘贴直入输入框 + 附件按钮 + 待发送预览条 + 气泡缩略图渲染
- **M1.1.1 文件存储统一 MinIO**: `FileStorage` 抽象 + MinIO 实现（S3 兼容，双 client 设计——内网操作端点与对外预签名端点分离，适配容器部署拓扑）；知识库三消费点（上传/删除/解析读流）迁离本地磁盘，库表仅存 object key；compose 新增 `gewu-minio` 服务与数据卷；存量 `uploads/` 不自动迁移（删除安全降级，重解析需手工 `mc mirror`）
- **M2 对话内文件解析**: pdf/docx/txt/md 上传 → 复用 DocumentParseService 全文抽取 → 注入本次对话上下文（预算 20000 字符截断；**不进向量库、不产生 KnowledgeDocument**，与 RAG 明确分工：临时阅读 vs 长期沉淀）；单文档失败跳过不阻断对话
- **E1 评测确定性约束**（2c45ab4）: 评测执行器强制 `temperature=0` + 固定 `seed=42`（EVAL_TEMPERATURE / EVAL_SEED 可覆盖）；报告输出可复现元信息；基线文件化（EVAL_BASELINE_OUT 写出 / BASELINE_FILE 对比，回退即失败——已用篡改基线反向验证）
- **E2 数据流与最小化声明**: README「数据流向与隐私」章节（存本地 / 凭据不出机 / 无遥测）+「如何自行核对」三条独立验证路径；经全仓库扫描核实无遥测/崩溃上报/版本检查代码

### Changed
- `/upload` 按扩展名分流：图片返回预签名 URL；文档返回 `{ key, url, name }`
- ChatRequest 新增 `imageKeys` / `fileKeys`（object key，后端消费）；`imageUrls` 保留为前端展示用
- sendTextMessage / streamTextMessage 新增含附件参数的重载（旧签名保留委托，向后兼容）
- 移除「多模态模式预留中，本次按文本模式处理」日志（M1 上线后已不成立）

### Docs
- DEPLOY.md 新增「文件存储 MinIO」小节；架构图更新（minio 容器 + Redis 链路）
- API.md 重写多模态与文档附件说明（三字段语义 + 能力门禁要求）
- ARCHITECTURE.md 补 `infrastructure/storage` 分层；新建 AGENTS.md（项目级代理约定）
- ROADMAP/V2.5.0-PLAN 全程同步；文档用语按规范统一中性表述

## [v2.4.0] - 2026-11-02

### Added
- **C1 Sandbox adapter registry**: `SandboxAdapter` interface (toolName / risk / prepare / normalized); `SandboxRunner` now builds a registry from constructor-injected `List<SandboxAdapter>` and dispatches by lookup — **adding a sandbox-capable tool no longer requires touching Runner**. `CalculatorSandboxAdapter` (migrated logic) + `DateTimeSandboxAdapter` (second adapter proving the extension point)
- **C2 Audit logging**: `audit_log` table (init.sql source of truth + Flyway `V3`) with `AuditService` / `AuditLogMapper` / `GET /api/settings/audit-logs`; covers agent definition CRUD & enable/disable, model provider & secret changes, plugin create/install/uninstall/delete, tool risk changes, session & group cleanup
- **C3 Container sandbox evaluation**: decision document concluding **defer** (no usable container runtime on the build host; process-level sandbox already covers the current threat surface). Trigger conditions documented

### Fixed
- `runTool` input validation order was inverted (validation ran inside the "missing argument" branch, so illegal expressions could bypass it) — corrected during adapter migration
- Plugin `create` endpoint was missing audit instrumentation (found only by end-to-end testing, not by compile or unit tests)
- `LlmProviderController.update` lost its `request` parameter when a multi-line signature was rewritten

### Security
- Audit `detail` masks any field whose key contains key/secret/token/password as `***`; detail capped at 2000 chars; audit writes never block business flow

## [v2.3.0] - 2026-11-02

### Added
- **B1 Test baseline** (project had zero tests): 6 test classes / 48 cases — `ToolRiskTest`, `SandboxPolicyTest`, `SandboxExpressionGuardTest`, `CalculatorToolTest`, `RateLimitServiceTest`, `TraceIdFilterTest`, mirrored package structure
- **B2 LLM evaluation loop**: `EvalSuiteTest` with `@TestFactory` dynamic tests, exact/prefix assertions, `[EVAL-FAIL]` details and `[EVAL-SUMMARY]`; `calculator-basic.yaml` with 20 fixed cases; directory guard against "empty run passes"
- **B3 Release governance**: `CHANGELOG.md` (Keep a Changelog + SemVer, backfilled to v1.0.0); Flyway for incremental migrations (`baseline-on-migrate` + `baseline-version=1`, `init.sql` remains the build-from-scratch source of truth)

### Fixed
- Calculator power operator never worked: Nashorn does not support `**`, so `replaceAll("^","**")` was a syntax error — rewritten to `Math.pow(a,b)` with bracket/nested operand scanning and integer float normalization
- Chained power `2^3^2` produced an illegal expression (`Math.powMath.pow(2,3)`) because the left-operand scan dropped the function name prefix
- Integers above int range printed in scientific notation (`999999*999999` → `9.99998000001E11`) — normalized via `BigDecimal.toPlainString`

> The three defects above were found by the new tests and evaluation suite; manual smoke testing had never covered them.

## [v2.2.0] - 2026-11-02

### Added
- **A1 MCP tools restored** (fixes a v2.1.0 regression where `external=sandbox` with only a `calculator` adapter made every plugin/MCP tool fail): switched to **bounded direct execution** — `external` defaults to `direct`, native `HttpMcpTransport.timeout` + `DefaultMcpClient.toolExecutionTimeout` (30s, configurable), result length cap (100k chars, configurable, truncation annotated), full audit logging
- **A2 OpenTelemetry-lite**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`; `/actuator/health` public for probes, other `/actuator/**` requires ADMIN; `TraceIdFilter` (X-Trace-Id passthrough or generation → MDC → `%X{traceId}` in logs, finally cleanup, response header echo, input sanitization); traceId propagated into sandbox child processes via three-segment protocol
- **A3 Rate limiting**: in-process token bucket (`RateLimitService` + `RateLimitFilter`) on `/api/chat*` and `/api/agent/chat*`; 429 + `Retry-After`; authenticated requests keyed by username, anonymous by IP (X-Forwarded-For first); layered with quota (rate vs daily total)

### Changed
- `external` risk level default changed from `sandbox` to `direct` (MCP execution is pure HTTP/SSE with no local code execution; JVM isolation gives zero security benefit at 500ms+ cost per call)

### Fixed
- Sandbox materialization cache did not invalidate on rebuild (stale worker class reused, causing protocol mismatch) — cache key now includes jar path + size + mtime, plus worker class existence check
- `logging.pattern` was inserted between `level:` and its children, breaking startup (`Failed to bind logging.level`)
- Redis being unreachable pulled overall health to DOWN — Redis is an optional acceleration layer, so it no longer participates in overall health by default (`GEWU_HEALTH_REDIS_ENABLED=false`)

## [v2.1.0] - 2026-11-02

### Added
- **B1 Tool risk classification**: `ToolRisk` (SAFE/DYNAMIC/EXTERNAL/DANGEROUS) + risk registry in ToolRegistry; `SandboxPolicy` (direct/sandbox/disabled); fail-closed rejection in `ToolRegistry.execute`; DANGEROUS unconditional approval via `ToolApprovalGateway`; `/api/settings/tool-risks` management API (GET snapshot / POST elevate, ADMIN)
- **B2 Process-level sandbox**: `SandboxWorker` (isolated child JVM, pure-JDK protocol) + `SandboxRunner` (spawn with `-Xmx`, timeout `destroyForcibly()`, drain threads, startup self-check, adaptive classpath for dev/fat-jar incl. Spring Boot 3.2 nested URL); `ToolRegistry.execute` routes `requireSandbox` → sandbox
- **B3 Expression hardening**: whitelist character validation on both direct and sandbox paths (blocks `Java.type` / identifiers / multi-statement injection), 200-char limit

### Changed
- `dangerous` risk level now unconditionally requires approval (priority over tool-level whitelist and agent policy); approval gateway is `new`-instantiated → explicit `setRiskSource` injection in `AgentService.init()`
- Default policy: `external=sandbox`, `on-unavailable=reject` (fail-closed); yml `gewu.sandbox.policy.*`

## [v2.0.1] - 2026-11-02

### Fixed
- Plugin tool allowlist now actually enforced: `mcp_server.tool_allow` persisted; `McpClientHolder.connectAndBridge` filters by allowlist (5 call sites incl. startup restore); JSON-array or comma-separated formats
- Skill directory configurable: runtime `setSkillsDir` (memory-only, restore on restart), `GET/POST /api/settings/skills/dir`, yml `gewu.skills.dir` with env override, settings panel

## [v2.0.0] - 2026-10-30

### Added
- **R1 Multi-agent orchestration**: master/worker roles (`role`, `allowed_workers`), `agent_dispatch` tool (whitelisted, auditable), child-session execution with result propagation, single-level orchestration tree
- **R2 Public skill library**: `SKILL.md` scanning as prompt packages (read-only, frontmatter parsing), injection into system prompts, `/api/settings/skills` API
- **R3 Plugin registry**: MCP-server-based plugins, `plugin_registry` table, one-click install bridging into `mcp_server`, builtin samples (filesystem/fetch/time/memory), `/api/settings/plugins` API, docs/PLUGIN.md
- Frontend: master badge/role UI, skill picker, plugin registry panel, parent-child session tree

### Fixed
- Child-session persistence (F-1): sync `executeLoop` now persists user/assistant messages, tool trace, token stats
- DTO silent field drop for `role`/`allowedWorkers`

## [v1.1.0] - 2026-10-30

### Added
- **C1 Parent-child sessions**: derivation (clone parent systemPrompt + summary), auto result propagation, single-level tree
- **C2 Checkpoint**: `agent_execution` persistence for approval state, restart restore, expiry scheduler
- **C3 MCP client**: `McpClientHolder`, bridging, CRUD + enable/disable/test APIs, docs/MCP.md
- Frontend: session tree display and derivation entry

## [v1.0.1] - 2026-10-28

### Added
- B1 Session ownership (`user_id` migration, list isolation, operation gates)
- B2 Session management (rename via `remark`, groups with collapse UI)
- B3 Slash commands (`/clear` `/reset` `/compact`) + context compression (`context_summary`, auto-compact)
- B4 Parent-child session groundwork
- B5 Ollama chat compatibility (embedding blocked by fixed vector dimension)

## [v1.0.0] - 2026-10-20

### Added
- 5 user-facing features: model config center, knowledge base + retrieval testbench, prompt templates + wizard, reports (3 views), token quota
- Containerization, docs, release engineering, org public repo
