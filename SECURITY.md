# 安全策略（Security Policy）

## 支持版本

| 版本 | 状态 |
|---|---|
| 2.4.x | ✅ 支持（当前） |
| 2.0.x ~ 2.3.x | ⚠️ 安全修复仅（建议升级至 2.4.x） |
| 1.x | ❌ 已停止支持 |

## 报告漏洞

**不要通过公开 Issue 报告安全漏洞。**

请通过 GitHub Security Advisories（仓库页 → Security → Report a vulnerability）私下报告，或联系仓库维护者。报告后 72 小时内会有初步响应。

报告时请包含：

- 漏洞类型与影响面（认证绕过 / 密钥泄露 / 注入 / 越权……）
- 复现步骤或 PoC
- 影响版本

## 已知安全设计（供审计参考）

### 密钥管理

- 模型 Provider 的 API Key 使用 **AES-256-GCM** 加密落库（`llm_provider.api_key_enc`，`v1:iv:ct` 格式）
- 加密密钥来自环境变量 `GEWU_ENCRYPT_KEY`（建议 `openssl rand -base64 48` 生成）
- ⚠️ 未配置 `GEWU_ENCRYPT_KEY` 时回退到硬编码开发密钥（`gewu-dev-encrypt-key-do-not-use-in-prod`）——**生产环境必须配置**，否则密文等同明文
- 密钥只进不出：所有 API 仅返回 `sk-****` 掩码，无任何导出密钥的端点
- yml 不再内置明文 key：模型 key 经 `VOLC_API_KEY` 环境变量注入，或通过模型配置中心写入 DB

### 认证与授权

- JWT（HS256）+ 数据库令牌状态校验（`sys_token` 表）：登出/管理端踢人即时生效
- 登录限流：5 次 / 300 秒
- `/api/settings/**`（模型配置）仅 `ROLE_ADMIN`
- 其余业务端点 `anyRequest().authenticated()`
- 服务端从 SecurityContext 解析用户身份；请求体中的 `userId` 字段不受信任（T6 配额按服务端身份计算）

### 工具执行沙箱（V2.1.0 / V2.4.0）

工具执行按**风险分级**决定执行方式，核心原则是 **fail-closed**（不确定就拒绝，不静默降级）：

| 风险级 | 含义 | 默认执行 |
|---|---|---|
| `SAFE` | 只读/纯计算 | 直执行 |
| `DYNAMIC` | 含动态求值（如脚本引擎 eval） | 直执行（表达式受白名单约束） |
| `EXTERNAL` | 外部来源（MCP 桥接工具） | 直执行（有界：超时 + 结果上限） |
| `DANGEROUS` | 高危能力 | **强制沙箱 + 无条件需审批** |

**进程级沙箱**（DYNAMIC/DANGEROUS 可路由至沙箱）：

- 独立子 JVM 执行，与主进程隔离；子进程崩溃/死循环不影响主服务
- 超时强杀（`destroyForcibly`，默认 5s 可配）+ 内存上限（`-Xmx`，默认 256m）
- 子进程极简：无 Spring、无 DB、无密钥
- **表达式白名单**：仅允许数字与算术运算符（含括号、幂、科学计数），
  阻断 `Java.type` 逃逸、字母标识符、分号多语句注入；长度上限 200 字符
- 沙箱不可用时按策略处理，**默认拒绝**（`on-unavailable=reject`）；
  仅显式配置 `allow-direct` 才降级直执行，且记录 ERROR 级「安全降级」告警

> 容器级沙箱已评估并**延后实施**（本机无可用容器运行时；进程级已覆盖当前威胁面），
> 决策记录见 `docs/decisions/0001-容器级沙箱评估.md`。

### 外部工具与插件边界（V1.1.0 / V2.0.0 / V2.0.1 / V2.2.0）

**MCP 外部工具**：

- 工具白名单 `toolAllow`（JSON 数组或逗号分隔）：只桥接清单内工具，空=不限；
  白名单持久化在 `mcp_server.tool_allow`，**重启后依然生效**（不会退化为全量）
- 调用有界：原生超时（默认 30s）+ 结果长度上限（默认 10 万字符，超出截断标注）
- 全程审计留档：server / tool / 耗时 / 输出长度 / 是否截断 / 失败根因
- 桥接工具默认风险级 `EXTERNAL`，执行方式受策略控制（见上）

**插件仓库**：

- 一键安装 = 把清单条目登记进 `mcp_server` 并桥接工具，**不引入独立执行路径**
- 内置样例不可删除；已安装插件需先卸载才能删条目
- 安装失败会回滚已落库的 `mcp_server` 行，避免「已登记但连不上」的半安装状态

**公共 Skill 库**：

- SKILL.md 内容**仅作提示词注入**，服务端不解析、不执行其中任何指令
- 扫描目录**只读**（只做 `Files.read`，从不 write/delete）
- 运行时切换目录仅改内存态（不回写配置文件），重启回退到 env/yml 值

### 可观测端点暴露面（V2.2.0）

| 端点 | 认证 | 说明 |
|---|---|---|
| `/actuator/health` | **公开** | 容器探针需要；详情（db/diskSpace 等）仍需 ADMIN |
| `/actuator/metrics` | ADMIN | Micrometer 指标 |
| `/actuator/prometheus` | ADMIN | Prometheus 抓取点 |

仅暴露上述三个端点，其余 actuator 端点关闭。**指标不对外裸奔**——含内部线程池、
JVM、HTTP 路径等运行细节，仅授权可读。

### 审计日志（V2.4.0）

管理面操作全量留痕到 `audit_log`（谁在何时对什么做了什么、成功与否）：

- 覆盖：Agent 定义增删改与启停、模型与密钥变更、插件安装卸载与条目增删、
  工具风险级调整、会话与分组清理
- **密钥不落审计表**：`detail` 中键名含 key/secret/token/password 等字段一律掩码 `***`，
  长度上限 2000
- 审计写入是旁路：失败只 WARN 不阻断业务（但会留下告警日志）
- 查询端点 `/api/settings/audit-logs` 仅 ADMIN，支持按操作者/动作/对象/时间筛选

### 限流（V2.2.0）

- 对话类接口（`/api/chat*`、`/api/agent/chat*`）受**进程内令牌桶**限流
- 超限返回 `429` + `Retry-After`；阈值可配（默认突发 10 次、恢复 0.5/秒）
- **与配额分层**：限流管**速率**（先行拒绝，被限请求不消耗配额）；配额管**每日总量**。
  两者错误码同为 429，靠 message 区分
- 限流主体：已认证按用户名，未认证回落客户端 IP（优先 `X-Forwarded-For` 首地址）

> 选型说明：用进程内令牌桶而非 Redis——Redis 在本项目是**可选加速层**（不可达自动降级），
> 限流若强依赖 Redis，会在 Redis 故障时丧失保护。代价是多实例部署时各实例独立计数，
> 全局精确限流需改 Redis 实现（见 ROADMAP V2.5.0）。

### 配额与滥用防护

- 用户级每日 token 配额（`gewu.quota.enabled` / `gewu.quota.daily-token-limit`，默认关闭）
- 超限返回 429，不做预扣
- 与限流的分工：配额管「每日总量」，限流管「瞬时速率」（见上「限流」节）

### 数据安全

- 全部业务表按 `user_id` 隔离；会话/消息/文档仅本人可见（admin 例外）
- 文件上传限制类型（md/txt/pdf/docx/图片）与大小

## 安全配置基线（部署必查）

1. [ ] `GEWU_ENCRYPT_KEY` 已配置且不在代码库/镜像中
2. [ ] 数据库口令非默认值，不暴露公网
3. [ ] JWT 密钥已更换（见 `.env.example`）
4. [ ] nginx 层已限制上传体积与超时（参考 `docs/DEPLOY.md`）
5. [ ] `gewu.quota.enabled=true`（多用户场景建议开启）
6. [ ] 沙箱策略生产取值已确认：`GEWU_SANDBOX_DANGEROUS=sandbox`、
       `GEWU_SANDBOX_ON_UNAVAILABLE=reject`（**不要改成 allow-direct**）
7. [ ] 限流已开启（`GEWU_RATELIMIT_ENABLED=true`，默认即开）
8. [ ] MCP 服务器填了 `toolAllow` 白名单（不要留空全量桥接）
9. [ ] `/actuator/**` 未被反向代理直接暴露到公网（仅 health 可公开）
10. [ ] 审计日志已可查询（`GET /api/settings/audit-logs` ADMIN 可访问）
11. [ ] 数据库迁移目录可访问：裸 jar 需在仓库根启动，否则设
        `GEWU_FLYWAY_LOCATIONS=filesystem:/绝对路径/database/migrations`
