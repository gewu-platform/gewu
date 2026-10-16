# langchain4j-learning 对齐 java-ai-native 开发计划（develop 正式版 v2.2）

> 创建：2026-10-16 | 分支：**develop**（正式开发）
> 学习资产已归档至 **learn** 分支（Day1-8 示例 + embedding 适配器验证版 + 设计文档）。
> v2.2：逐模块扫描 native 源码后全面核对，修正 6 处遗漏（prompt 版本制/双统计表/Agent 完整形态/多模态/系统提示词/monitor 形态）。

---

## 一、develop 分支现状（2026-10-16 扫描）

| 维度 | 现状 |
|---|---|
| 提交基线 | `1ee6209` 基础学习资料 → `0a473d0` MySQL 会话持久化 |
| 包结构 | 平铺：config / controller / service / entity / mapper / example |
| 数据库 | MySQL（chat_session / chat_message 会话持久化） |
| AI 能力 | LangChain4j 1.11.7 多模型管理（LangChain4jConfig）+ 会话记忆 + 简单/流式/角色对话 |
| 认证 | ❌ 无 |
| RAG | ❌ 无（pgvector 依赖、embedding 配置、适配器都在 learn 分支） |
| Prompt 模板 / 监控 / Agent | ❌ 无 |
| 前端 | 3 页面（Chat/Examples/Model），暗色自研侧边栏，无 AntD |

### java-ai-native 已有资产（对齐参考 + 可复用）

| 资产 | 位置（native） | 说明 |
|---|---|---|
| `VolcEmbeddingClient.java` | ai-client/ | ✅ **native 已有**：OkHttp+Gson，multimodal（逐条串行）/text（真批量）双模式，429 退避重试 3 次，动态维度 |
| `ai-native-init.sql` | database/ | `vector(2048)` 实测维度确认 |
| knowledge 模块 | module/knowledge/ | DocumentParseService / KnowledgeBaseService / KnowledgeIndexingService 完整链路 |
| 前端 8 页面 + AuthModal | java-ai-native-web/ | UI 风格规格来源 |

### learn 分支资产清单（可移植，已验证编译通过）

| 资产 | 位置（learn） | 移植方式 |
|---|---|---|
| `VolcMultiModalEmbeddingModel.java` | config/ | 复制后补 native 的 429 重试 + 双模式能力 |
| pom：langchain4j-pgvector 1.11.7-beta19 + postgresql 驱动 | pom.xml | 合并进 develop pom |
| application.yml：`langchain4j.embedding` 配置段 | yml | 合并（api-key 用环境变量占位） |
| `Day8RagProdExample.java` | example/ | 作为 RAG 参考实现复制（day8 开关默认关闭） |
| `HANDOFF.md` RAG 中台设计 | 根目录 | 表结构/切片策略/检索过滤方案复用 |
| learn 分支 3 提交 | b0fb479/39e8745/421061b | ⚠️ 未 push，建议保留本地或确认后 push |

---

## 二、对齐策略（v2.2 全面核对后修订）

1. **分支分工**：`learn` = 学习资产库（永久保留 Day1-8 + 验证代码）；`develop` = 正式产品开发。develop 不删除 Day1-7 学习示例（开关全关，保留 ExamplesView 支撑）。
2. **框架不换**：功能与 UI 对齐 native，实现框架保持 LangChain4j 1.11.7 → 「同一套产品，Spring AI / LangChain4j 双框架实现」简历亮点。
3. **embedding 策略（v2.1 明确）**：以 learn 版 `VolcMultiModalEmbeddingModel`（LangChain4j `EmbeddingModel` 接口实现）为基础，**功能形态对齐 native `VolcEmbeddingClient`**：补 429 退避重试（2s/4s/6s×3）+ text/multimodal 双模式 + 动态维度。接口保持 LangChain4j 标准，可直接接 `EmbeddingStoreContentRetriever` 与 AiServices RAG 链路。
4. **移植方式**：从 learn 复制文件（不 cherry-pick 提交），避免学习示例开关/文档污染 develop 历史。
5. **数据库统一**：MySQL → PostgreSQL + pgvector。旧会话数据量少，手工导出插入。
6. **UI 对齐**：Ant Design Vue 3.x + 浅色液态有机风（毛玻璃 header / blob logo / 胶囊导航 / 状态 pill），品牌字样 LC4J。
7. **API 路径对齐**：`/api/auth/*`、`/api/user/*`、`/api/chat/*`、`/api/knowledge/*`、`/api/prompt/*`、`/api/monitor/*`、`/api/agent/*` 与 native 同形。

---

## 三、目标架构（develop 最终形态）

### 后端包结构（平铺 → 模块化）

```
com.example.lc4j/
├── LangChain4jLearningApplication.java
├── common/                  # 新增：R / ErrorCode / BizException / PageResult
├── config/                  # 已有 + 移植 VolcMultiModalEmbeddingModel + 新增 SecurityConfig/AsyncConfig
├── infrastructure/security/ # 新增：JwtUtil / JwtAuthFilter
├── module/
│   ├── auth/                # 新增：login/register/refresh/logout/me
│   ├── user/                # 新增：profile/password
│   ├── chat/                # 重构迁入：多会话 CRUD + SSE + 模型管理
│   ├── knowledge/           # 新增：上传/解析/向量化/检索（对照 native knowledge 模块）
│   ├── prompt/              # 新增：模板 CRUD/执行/版本/回滚
│   ├── monitor/             # 新增：llm_call_log 埋点 + 统计/排行/分页
│   └── agent/               # 新增：LangChain4j @Tool 工具 + AgentService
└── example/                 # 保留：Day1-7（开关全关）+ 移植 Day8 参考
```

### 数据库表（PostgreSQL 17 + pgvector 0.8.2，库 `langchain4j_kb`）

| # | 表 | 说明 |
|---|---|---|
| 1 | sys_user | username/password(BCrypt)/nickname/email/status |
| 2 | sys_token | JWT 状态维护（替代 Redis） |
| 3 | chat_session | 迁移自 MySQL + 补 user_id/active |
| 4 | chat_message | 迁移自 MySQL + 补 user_id |
| 5 | knowledge_document | 文件名/类型/大小/SHA-256/状态/chunk_count/dimension |
| 6 | document_chunk | content/chunk_index/vector(2048)/元数据 |
| 7 | prompt_template | **单表版本制**（对齐 native）：name/system_prompt/user_prompt_template/model/temperature/max_tokens/is_active，UNIQUE(name,version)，无独立版本表 |
| 8 | system_prompt_config | 对话系统提示词配置（native chat 模块在用） |
| 9 | token_usage | 会话/模型级 token 统计与成本（native 有 TokenUsageService） |
| 10 | llm_call_log | user/model/场景/tokens/耗时/状态/错误 |
| 11 | operation_log | 可选 P8 |

向量维度：native 实测 2048（`ai-native-init.sql` 为证），直接沿用。

---

## 四、阶段计划（约 9~11 个学习日）

### P0 资产移植 + 数据库切换（1 天）
- [ ] 从 learn 复制 VolcMultiModalEmbeddingModel + pom 依赖 + embedding 配置段 → 编译验证
- [ ] 对齐 native VolcEmbeddingClient 功能形态：补 429 退避重试（2s/4s/6s×3）+ 预留 text 模式（标准 /embeddings 批量）；接口保持 LangChain4j EmbeddingModel
- [ ] ~~curl 验证向量维度~~（已确认：native 实测 2048）
- [ ] PG 建库 `langchain4j_kb` + chat_session/chat_message 迁移表 + MySQL 数据导出
- [ ] pom 移除 mysql-connector-j；yml 切 PG 数据源；统一 R/ErrorCode/BizException/PageResult
- [ ] 验收：应用在 PG 上启动，旧对话 API（simple/stream/session/role）全走通

### P1 认证模块（1 天）
- [ ] sys_user/sys_token 表；JwtUtil + JwtAuthFilter + SecurityConfig
- [ ] auth/user 模块（BCrypt、登录限流 5 次/5min 对齐 native）
- [ ] 验收：注册→登录→me→refresh→logout 全链路 curl 走通

### P2 前端 UI 框架对齐（1.5 天）★ 风格分水岭
- [ ] ant-design-vue 引入；App.vue 整体重写（毛玻璃 header / blob logo / nav-tabs / 状态 pill / 用户下拉）
- [ ] AuthModal.vue + stores/auth.js + axios 拦截器（token 注入 + 401 → auth-expired → 弹窗）
- [ ] 路由扩展：/knowledge /prompt /monitor /agent /settings /profile 占位
- [ ] 验收：与 native 并排对比观感一致；登录弹窗流程走通

### P3 对话模块对齐（1~1.5 天）
- [ ] chat 模块化迁入 + 多会话 CRUD + 消息归属 user
- [ ] **对齐 native API 形态**：POST /message 与 /message/stream（SSE）、session/{id}/stats、mode GET/POST
- [ ] **多模态对话**（对齐 native）：图文混合消息（content_type=multimodal + imageUrls JSON 存储）；后端先预留字段与 API，图像输入可后置
- [ ] **system_prompt_config 表**：对话模式绑定系统提示词（native ChatSessionService 在用）
- [ ] **token_usage 表 + TokenUsageService**：会话/模型级统计与成本核算（与 llm_call_log 埋点同一批实现）
- [ ] ChatView 重写（会话侧栏 + AntD 消息区）+ SettingsView（模型管理迁入）
- [ ] 验收：多会话增删切换、流式输出、模型切换即时生效、token_usage/llm_call_log 有记录

### P4 知识库 RAG（2~3 天）★ 功能核心
- [ ] knowledge_document/document_chunk 表（vector 2048）
- [ ] DocumentService（上传/SHA-256 去重/本地存储）+ @Async Tika 解析 → 段落切片(500/80) → 批量向量化 → pgvector 入库（metadata: docId/fileName/chunkIndex）
- [ ] EmbeddingStoreContentRetriever + RAG 问答（同步 + SSE，回答带 [来源n]）
- [ ] KnowledgeView（上传/列表/状态轮询/删除/检索测试面板）
- [ ] 验收：上传 PDF/MD → SUCCESS → 提问命中文档内容并标注来源；删除同步删向量

### P5 Prompt 模板管理（1 天）
- [ ] prompt_template 表（**单表版本制**，对齐 native：UNIQUE(name,version)，含 system_prompt/model/temperature/max_tokens/is_active）
- [ ] CRUD + 创建自动 version+1 + 执行（LangChain4j PromptTemplate）+ 回滚到指定版本
- [ ] PromptView（列表/编辑抽屉/变量表单/执行结果/版本历史）
- [ ] 验收：建模板→填变量执行→改内容产生 v2→回滚 v1

### P6 LLM 监控（1 天）
- [ ] MonitorService（stats / models 排行 / POST /logs 分页筛选 + HourlyStat 小时统计，对齐 native LlmMonitorController）
- [ ] MonitorView（统计卡片/排行条形/日志表格）
- [ ] 验收：与 llm_call_log/token_usage 数据一致

### P7 Agent（2~2.5 天）
- [ ] LangChain4j 原生 Tool Calling（@Tool + AiServices）：Calculator/DateTime/TimeZone/Search 四件套（Search 占位，对齐 native）
- [ ] **AgentDefinition 动态定义**（对齐 native）：id/name/description/avatar/systemPrompt/绑定工具/配置，DB 持久化 + AgentRegistry 运行时注册
- [ ] **双对话模式**（对齐 native）：/chat/simple（直答）/chat/smart（ReAct 式工具链）/chat/stream + /chat/smart/stream（SSE）
- [ ] **ToolApprovalGateway 工具审批网关**（对齐 native）：只读工具自动放行、search/自定义 HTTP 工具需审批、未知工具默认拦截；/approval/pending、/approval/approve、/approval/reject 三端点
- [ ] **AgentDispatchTool**：Agent 间调度工具（主 Agent 可调用其他 Agent 作为工具，对齐 native）
- [ ] AgentDefinitionController：definitions CRUD + enabled-list + reload + available-tools
- [ ] SSE 推送「思考→调用工具(参数)→观察→最终回答/需审批」事件链
- [ ] AgentView（对话 + 工具调用时间线 + 审批弹窗）+ AgentManagementView（定义管理）
- [ ] 学习笔记：native 文本提示 ReAct vs lc4j 原生 function calling（沉淀 progress.md）
- [ ] 验收：数学/时间问题触发工具且前端可见调用链；search 触发审批弹窗；自定义 Agent 定义可运行

### P8 收尾（0.5 天）
- [ ] ~~operation_log~~（可选，不阻塞）
- [ ] README 重写（功能全景 + 双框架对照表 + 截图）
- [ ] progress.md 更新 + 简历素材
- [ ] 全量回归一条龙；最终 commit 前等老板确认（铁律）

---

## 五、决策记录（v2.2 修订）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| 1 | 分支策略 | learn=学习资产库 / develop=正式开发 | 老板已手动分工 |
| 2 | Day1-7 示例 | develop 保留（开关全关） | 学习对照价值 + 删除不可逆 |
| 3 | 移植方式 | 复制文件不 cherry-pick | 避免污染 develop 历史 |
| 4 | 数据库 | 统一 PG + pgvector | RAG 硬需求 + 对齐 native |
| 5 | 框架 | LangChain4j 1.11.7 不换 | 双框架简历亮点 |
| 6 | embedding 实现 | learn 接口 + native 功能形态 | 接口接 RAG 链路，功能对齐 429 重试/双模式 |
| 7 | 向量维度 | 2048 直接沿用 | native 实测确认 |
| 8 | HANDOFF 中台方向 | 搁置（表结构/切片/检索设计复用） | 与对齐目标冲突 |
| 9 | prompt 版本管理 | 单表版本制（UNIQUE name+version） | v2.1 的独立版本表与 native 实现不符，已纠正 |
| 10 | 双统计表 | token_usage（会话/成本）+ llm_call_log（调用日志）| native 双表各有分工，v2.1 漏了前者 |
| 11 | Agent 范围 | 完整对齐：定义/审批网关/双模式/调度工具 | v2.1 只对齐了基础四工具，砍太多 |
| 12 | 多模态对话 | 后端字段+API 先预留，图像输入可后置 | native 已支持图文混合，不补就称不上「完全对齐」 |

## 六、风险

1. ~~火山 embedding 维度未实测~~（已解除：native 实测 2048）
2. **流式 TokenUsage 时机**与 Spring AI 不同 → P3 预留调试
3. **前端换肤工作量**（3 旧页全重写）→ P2 集中处理
4. **MySQL→PG 会话迁移**手工进行，量大再写脚本
5. **learn 分支 3 提交未 push** → 建议确认后 push 留档，防本地丢失
6. **Agent 阶段工作量最大**（审批网关/动态定义/调度工具）→ P7 已扩至 2~2.5 天，必要时可拆 P7a/P7b 两批交付
7. **多模态对话**依赖 LangChain4j 图文消息 API → 先落库结构和 API 契约，图像输入后置，不阻塞主链路
