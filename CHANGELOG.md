# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
