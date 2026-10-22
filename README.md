# LangChain4j Learning — Java AI 原生应用

基于 **LangChain4j 1.11.7 + Spring Boot 3.2.4 + PostgreSQL/pgvector** 的 AI 原生全栈应用。
按企业级参考项目 [java-ai-native](https://github.com/) 逐模块对齐开发（P0→P8 九个阶段全部完成），覆盖对话、RAG 知识库、Prompt 管理、LLM 监控、Agent 工具编排五大能力域。

> 后端 ~8.6k 行 Java（108 文件，Maven 四模块）| 前端 ~7.5k 行 Vue（22 文件）| PG 11 表 | 50+ REST 端点

## ✨ 功能全景

| 阶段 | 模块 | 能力 |
|------|------|------|
| P0 | 基础设施 | PostgreSQL 切换、火山多模态 Embedding 适配器（lc4j `EmbeddingModel` 接口 + 429 退避） |
| P1 | 认证 | JWT 登录 + 登录限流（5 次/300s）+ Token 表会话管理 |
| P2 | 前端框架 | Vue3 + Ant Design Vue 换肤、认证链路、路由守卫 |
| P3 | 对话模块 | 16 端点：会话 CRUD、SSE 真流式（逐 token）、消息/token 统计落库 |
| P4 | 知识库 RAG | 文档上传 → 异步分块索引（2048 维）→ pgvector 余弦检索（阈值可配置） |
| P5 | Prompt 模板 | 版本制模板（创建自动 +1）、变量提取、执行、一键回滚 |
| P6 | LLM 监控 | `llm_call_log` 全链路埋点、统计卡片、24h 分布、模型排行、日志筛选分页 |
| P7 | Agent | lc4j 原生 function calling 循环 + 工具审批网关 + **挂起式审批续跑**（同一 SSE 连接）+ Agent 定义 CRUD |
| P8 | 方案 D 编排层 | ChatView 智能模式（对话统一入口：会话记忆 + 工具 + RAG 注入 + Bing 搜索）、AgentView 管理控制台 |

## 🏗️ 架构

```
langchain4j-learning/
├── lc4j-common/    # 统一响应 R<T> / 业务异常体系
├── lc4j-security/  # JwtUtil + 认证入口点
├── lc4j-client/    # LangChain4j 配置 + 火山 Embedding 适配器
└── lc4j-web/       # 业务主体
    └── module/
        ├── auth/       # 登录/注册/登出（限流）
        ├── user/       # 用户信息
        ├── chat/       # 会话/消息/SSE 流式/token 统计
        ├── knowledge/  # 文档上传/索引/向量检索
        ├── prompt/     # 模板版本制管理
        ├── monitor/    # LLM 调用埋点与统计
        └── agent/      # 编排引擎/工具注册/审批网关/定义 CRUD
            ├── tools/       # calculator / datetime / timezone / search(Bing)
            ├── gateway/     # 工具审批网关（search 需审批，只读工具放行）
            ├── registry/    # Agent 定义缓存
            └── definition/  # AgentConfig / AgentDefinition
```

**数据库**（PostgreSQL + pgvector，建表脚本唯一入口 `database/langchain4j-kb-init.sql`）：
`sys_user` / `sys_token` / `chat_session` / `chat_message`（含 `tool_calls` JSONB 工具轨迹）/ `token_usage` / `knowledge_document` / `document_chunk`（vector(2048)）/ `prompt_template` / `llm_call_log` / `agent_definition` / `system_prompt_config`

## 🚀 快速开始

**环境**：JDK 17、PostgreSQL 16+（需 pgvector 扩展）、Node 18+

```powershell
# 1. 建库（幂等脚本，含全部 11 表 + 种子数据）
psql -U postgres -c "CREATE DATABASE langchain4j_kb"
psql -U postgres -d langchain4j_kb -f database/langchain4j-kb-init.sql

# 2. 配置密钥（环境变量，或直接改 lc4j-web/src/main/resources/application.yml）
$env:VOLC_API_KEY="你的火山方舟 Key"          # 必须：对话 + Embedding
$env:BING_SEARCH_API_KEY="你的 Bing Key"      # 可选：Agent search 工具（缺省优雅降级）

# 3. 启动后端（8081）
mvn install -DskipTests
mvn spring-boot:run -pl lc4j-web

# 4. 启动前端（3001，代理到 8081）
cd langchain4j-learning-web
npm i && npm run dev
```

默认管理员：`admin / admin123`（种子数据）。

## 🔌 API 一览

统一 `R<T>` 响应包装，除 `/api/auth/**`、`/api/health/**` 外全部需要 JWT。

| 模块 | 前缀 | 核心端点 |
|------|------|----------|
| 认证 | `/api/auth` | `login` `register` `logout` |
| 对话 | `/api/chat` | `session`（CRUD/复用/统计）、`message`、`message/stream`（SSE） |
| 知识库 | `/api/knowledge` | `upload`（异步索引）、`documents`、`search`（向量检索） |
| 模板 | `/api/prompt` | `create`/`list`/`{name}`/`execute`/`rollback` |
| 监控 | `/api/monitor` | `stats`、`logs`（POST 筛选分页）、`models` |
| Agent | `/api/agent` | `chat/stream`（SSE）、`chat/stream/session`（会话模式）、`tools`、`approval/pending\|approve\|reject`、`definitions/**` |
| Service | `/api/service` | `ask` `translate` `analyze`（AiServices 声明式示例） |

**Agent SSE 事件契约**：`iteration / delta（逐 token）/ thought / action / tool_result / approval_required / rag / ping / done / error`
审批闭环：`approval_required` → 前端批准/拒绝 → 工具原地执行 → 下一轮 LLM 流式 → `done`，全程同一 SSE 连接（等待期 15s ping 心跳，3 分钟超时兜底）。

## 💡 技术亮点

### 1. Servlet 栈真流式 SSE
WebMVC 下返回 `Flux<DTO>` 会被整体缓冲（实测 171 个 delta 挤在 55ms 内一次性到达）。最终方案：`SseEmitter` + `Schedulers.boundedElastic()` 后台执行生产循环，TTFB 104ms、spread 与上游 LLM 逐 token 节奏一致（3.4s）。

### 2. 挂起式工具审批（human-in-the-loop）
`approval_required` 事件后流**不关闭**：循环线程注册 `CompletableFuture` 挂起等待审批决策（200ms 轮询 + 心跳保活），批准后用原始 `ToolExecutionRequest` 原地执行避免双重执行；拒绝/超时回填观察结果由模型继续生成。

### 3. lc4j 原生 function calling 编排
`@Tool` 注解 → `ToolSpecification` → 模型自主决策工具调用，`StreamingChatModel.onPartialResponse` 桥接 `CompletableFuture` 实现流式与工具循环两不误（1.11.7 handler 接口实测）。

### 4. RAG 检索链路
异步索引（上传即返回，后台分块+向量化）→ pgvector 余弦相似度检索 → 编排层按选定文档注入上下文（`rag` 事件回报命中数，失败降级不断流）。多模态 Embedding 对「问题 vs 陈述文」实测相似度仅 0.36~0.45，阈值做成可配置并实测校准。

### 5. 生产级踩坑实录（详见开发日志）
深度思考模型压制工具调用（`thinking:disabled` 修复）、MyBatis String 写 JSONB 列需 IMPLICIT CAST（CAST 函数参数用 TEXT 防无限递归）、SSE 三层验收法（事件计数/实时性 spread/字段语义）等 40+ 条实测记录。

## 🔍 LangChain4j vs Spring AI 速查（项目实战版）

| 功能 | LangChain4j（本项目） | Spring AI（java-ai-native） |
|------|----------------------|---------------------------|
| 模型接入 | `OpenAiChatModel.builder()` + `customParameters` 平铺请求体 | `ChatClient` 自动装配 |
| 流式 | `StreamingChatResponseHandler` 回调 + CompletableFuture 桥接 | `Flux<String>` 响应式 |
| 工具 | `@Tool(name=..., value=...)` 注解，框架生成规格并回填结果 | `ToolCallback` 接口 |
| 记忆 | `MessageWindowChatMemory` / 自管消息列表 | `ChatMemory` 抽象 |
| RAG | `EmbeddingModel` 接口 + `EmbeddingStore`（pgvector） | `VectorStore` 抽象 |
| 结构化输出 | AiServices 接口返回 POJO | `.entity(Class)` |

两套框架各完成一个全栈项目，对照笔记沉淀在 `docs/ALIGNMENT-PLAN.md`。

## 📚 学习资源

- [LangChain4j 官方文档](https://docs.langchain4j.dev/) · [GitHub](https://github.com/langchain4j/langchain4j)
- [火山引擎方舟文档](https://www.volcengine.com/docs/82379)
- [pgvector](https://github.com/pgvector/pgvector)

---

_版本 2.0 | 2026-10-21 | P0-P8 全阶段完成（截图待补）_
