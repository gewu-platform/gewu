# Gewu — 企业级 AI Agent 平台（Java AI 原生）

<p align="center">
  <a href="#-quick-start"><img src="https://img.shields.io/badge/Java-17-blue" alt="Java 17"></a>
  <a href="#-quick-start"><img src="https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen" alt="Spring Boot 3.2"></a>
  <img src="https://img.shields.io/badge/LangChain4j-1.11.7-orange" alt="LangChain4j 1.11.7">
  <img src="https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-blueviolet" alt="PG16+pgvector">
  <img src="https://img.shields.io/badge/Vue-3-42b883" alt="Vue 3">
  <img src="https://img.shields.io/badge/License-Apache--2.0-green" alt="Apache-2.0">
</p>

**Gewu（格物）** 是基于 **LangChain4j 1.11.7 + Spring Boot 3.2 + PostgreSQL/pgvector** 的企业级 AI Agent 全栈平台。覆盖对话（真流式 SSE）、RAG 知识库、Prompt 版本管理、LLM 监控与用量报表、Agent 工具编排（审批网关）、模型配置中心六大能力域，开箱即可作为企业 AI 中台底座或二开起点。

> 后端 ~8.8k 行 Java（108 文件，Maven 四模块）| 前端 ~7.8k 行 Vue（22 文件）| PG 12 表 | 50+ REST 端点

[English](#english--快速开始英文) · [API 参考](docs/API.md) · [工具扩展](docs/EXTEND-TOOL.md) · [架构总览](docs/ARCHITECTURE.md) · [部署指南](docs/DEPLOY.md)

---

## ✨ 能力全景

| 能力域 | 说明 |
|------|------|
| 💬 **对话** | 16 端点：会话 CRUD、逐 token 真流式 SSE、多模态图片输入、token 统计落库 |
| 🤖 **Agent 编排** | lc4j 原生 function calling 循环、5 个对话入口、**挂起式审批网关**（human-in-the-loop，同一 SSE 连接续跑）、Agent 定义 CRUD + 3 个预置模板 |
| 📚 **RAG 知识库** | md/txt/pdf/docx 上传 → 异步分块索引（2048 维）→ pgvector 余弦检索 → Agent 定义级知识库绑定（请求级参数可覆盖） |
| 🧩 **Prompt 管理** | 版本制模板（创建自动 +1）、变量提取、渲染执行、一键回滚 |
| 📊 **监控与报表** | `llm_call_log` 全链路埋点、今日统计卡、24h 分布、模型排行；**用量报表三视图**（按天/用户/Agent）+ 用户级每日 token 配额 |
| ⚙️ **模型配置中心** | chat/embedding 双类型 Provider 动态接入（AES-256-GCM 密文落库）、保存即生效、连通性测试、密钥只进不出 |

## 🏗️ 架构

```
gewu/                 gewu-web/
├── gewu-common/   统一返回/异常/加密   ├── src/views/   页面（Vue3 + AntD）
├── gewu-client/   LLM 动态工厂        ├── src/api/     模块化 API
├── gewu-web/      业务模块             └── nginx 托管 dist + /api 反代
│   ├── auth/user       认证与用户
│   ├── chat            会话/SSE/多模态
│   ├── agent           编排/工具/审批
│   ├── knowledge       RAG
│   ├── monitor         监控/报表/配额
│   ├── settings        模型配置中心
│   └── prompt          模板管理
└── database/gewu-init.sql  幂等建表（12 表）
```

细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 🚀 Quick Start

**前置**：JDK 17、Node 18+、PostgreSQL 16（需 pgvector 扩展）、一个 OpenAI 兼容模型端点（如火山方舟 / DeepSeek / 通义）。

```bash
# 1. 建库（幂等脚本，含全部 12 表 + 种子数据）
psql -U postgres -d gewu -f database/gewu-init.sql

# 2. 配置模型（环境变量方式，不改代码）
export VOLC_API_KEY=sk-xxx          # 或其他 OpenAI 兼容端点的 key
export GEWU_ENCRYPT_KEY=$(openssl rand -base64 48)   # 生产必配：模型密钥加密

# 3. 启动后端（8081）
mvn spring-boot:run -pl gewu-web

# 4. 启动前端（3001，代理到 8081）
cd ../gewu-web && npm i && npm run dev
```

浏览器打开 `http://localhost:3001`，默认管理员见 `.env.example` 注释。Docker 部署（compose 一键 server+db）见 [docs/DEPLOY.md](docs/DEPLOY.md)。

## 🔌 API 一览

50+ REST 端点，完整契约见 [docs/API.md](docs/API.md)：

| 模块 | 代表端点 |
|---|---|
| 对话 | `POST /api/chat/message/stream`（SSE）、`GET /api/chat/models` |
| Agent | `POST /api/agent/chat/stream`（十类事件契约）、`/api/agent/definitions` CRUD |
| 知识库 | `POST /api/knowledge/upload`、`GET /api/knowledge/search` |
| 监控 | `GET /api/monitor/stats`、`GET /api/monitor/usage/report?view=day|user|agent` |
| 模型配置 | `POST /api/settings/providers`、`POST /api/settings/providers/{id}/test` |

## 💡 技术亮点

1. **Servlet 栈真流式 SSE** — SseEmitter 后台线程逐事件推送（实测 161 事件 spread 103ms），Flux 直返会被 MVC 缓冲的坑已内置规避
2. **挂起式工具审批（human-in-the-loop）** — 审批等待期同一 SSE 连接心跳保活（ping），批准后续跑，无需重建编排
3. **lc4j 原生 function calling 编排** — ToolRegistry 反射注册（@Component + @Tool 即插即用），定义级工具绑定与审批三态
4. **RAG 检索链路** — 多格式解析（POI/PDFBox）+ 2048 维 multimodal embedding + 余弦检索 + 阈值可配
5. **模型配置中心** — DB 优先 + yml 兜底的双源工厂，密钥 AES-256-GCM 落库，保存即生效零重启

## 🛡️ 安全基线

- JWT + DB 令牌状态校验（登出即失效）
- 密钥只进不出：接口仅回 `sk-****` 掩码；`GEWU_ENCRYPT_KEY` 未配置时回退开发密钥（**生产必须配置**）
- 服务端解析用户身份，请求体 userId 不受信任；配额超限 429

详见 [SECURITY.md](SECURITY.md)。

## 📖 文档

| 文档 | 内容 |
|---|---|
| [docs/API.md](docs/API.md) | 全量 API 契约 + SSE 事件表 + 配额/模型配置说明 |
| [docs/EXTEND-TOOL.md](docs/EXTEND-TOOL.md) | 自定义工具开发（3 步接入） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构地图 + 数据流 + 扩展点 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Docker / nginx 部署 + 离线包 + FAQ |
| [docs/V1.0-PLAN.md](docs/V1.0-PLAN.md) | 版本规划 |

## English · Quick Start (brief)

```bash
psql -U postgres -d gewu -f database/gewu-init.sql
export VOLC_API_KEY=sk-xxx
export GEWU_ENCRYPT_KEY=$(openssl rand -base64 48)
mvn spring-boot:run -pl gewu-web
cd ../gewu-web && npm i && npm run dev   # http://localhost:3001
```

Gewu is an enterprise-grade AI Agent platform built on LangChain4j 1.11.7 + Spring Boot 3.2 + PostgreSQL/pgvector: streaming chat (SSE), RAG knowledge base (md/txt/pdf/docx → pgvector), versioned prompt management, LLM monitoring with usage reports & token quotas, agent orchestration with human-in-the-loop tool approval, and a dynamic model configuration center (AES-256-GCM encrypted keys). Full docs are in `docs/` (Chinese; API tables are language-neutral).

## License

[Apache-2.0](LICENSE)
