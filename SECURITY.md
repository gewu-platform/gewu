# 安全策略（Security Policy）

## 支持版本

| 版本 | 状态 |
|---|---|
| 1.0.x | ✅ 支持 |

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

### 配额与滥用防护

- 用户级每日 token 配额（`gewu.quota.enabled` / `gewu.quota.daily-token-limit`，默认关闭）
- 超限返回 429，不做预扣

### 数据安全

- 全部业务表按 `user_id` 隔离；会话/消息/文档仅本人可见（admin 例外）
- 文件上传限制类型（md/txt/pdf/docx/图片）与大小

## 安全配置基线（部署必查）

1. [ ] `GEWU_ENCRYPT_KEY` 已配置且不在代码库/镜像中
2. [ ] 数据库口令非默认值，不暴露公网
3. [ ] JWT 密钥已更换（见 `.env.example`）
4. [ ] nginx 层已限制上传体积与超时（参考 `docs/DEPLOY.md`）
5. [ ] `gewu.quota.enabled=true`（多用户场景建议开启）
