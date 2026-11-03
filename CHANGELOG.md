# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
