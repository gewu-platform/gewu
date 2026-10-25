-- ========================================== 
-- langchain4j_kb - PostgreSQL 数据库初始化脚本（全量单文件维护）
-- 对齐 java-ai-native ai-native-init.sql 表结构
-- 执行方式:
--   psql -h 127.0.0.1 -p 5432 -U postgres -d langchain4j_kb -f langchain4j-kb-init.sql
-- 维护约定：后续所有表结构调整直接更新本文件（不再拆分独立 SQL）
-- ==========================================

-- pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ==========================================
-- 1. 聊天消息表 (chat_message) - 对齐 native
-- ==========================================
CREATE TABLE IF NOT EXISTS chat_message (
    id            BIGSERIAL       PRIMARY KEY,
    session_id    VARCHAR(64)     DEFAULT NULL,
    user_id       BIGINT          DEFAULT NULL,
    role          VARCHAR(20)     DEFAULT NULL,
    content       TEXT            DEFAULT NULL,
    content_type  VARCHAR(20)     DEFAULT NULL,
    image_urls    JSONB           DEFAULT NULL,
    model         VARCHAR(50)     DEFAULT NULL,
    input_tokens  INT             DEFAULT NULL,
    output_tokens INT             DEFAULT NULL,
    finish_reason VARCHAR(50)     DEFAULT NULL,
    request_id    VARCHAR(64)     DEFAULT NULL,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE chat_message IS '聊天消息表 - 存储 AI 对话的详细消息记录';
COMMENT ON COLUMN chat_message.role IS '角色 - system:系统/user:用户/assistant:AI 助手';

CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_user_id ON chat_message(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_create_time ON chat_message(create_time);

-- P8a: 智能模式工具调用轨迹留档（编排层：模型+工具+RAG 统一编排）
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS tool_calls JSONB DEFAULT NULL;
COMMENT ON COLUMN chat_message.tool_calls IS '智能模式工具调用轨迹（JSON 数组：iteration/tool/input/result）';
CREATE INDEX IF NOT EXISTS idx_chat_message_role ON chat_message(role);

-- ==========================================
-- 2. 聊天会话表 (chat_session) - 对齐 native
-- ==========================================
CREATE TABLE IF NOT EXISTS chat_session (
    id            VARCHAR(64)     PRIMARY KEY,
    user_id       BIGINT          DEFAULT NULL,
    mode          VARCHAR(20)     DEFAULT NULL,
    model         VARCHAR(50)     DEFAULT NULL,
    system_prompt TEXT            DEFAULT NULL,
    message_count INT             DEFAULT NULL,
    input_tokens  BIGINT          DEFAULT NULL,
    output_tokens BIGINT          DEFAULT NULL,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    is_deleted    SMALLINT        DEFAULT 0,
    remark        VARCHAR(255)    DEFAULT NULL
);
COMMENT ON TABLE chat_session IS '聊天会话表 - 存储 AI 对话会话的基础信息';
COMMENT ON COLUMN chat_session.is_deleted IS '是否删除 - 0:未删除/1:已删除（软删除）';

CREATE INDEX IF NOT EXISTS idx_chat_session_create_time ON chat_session(create_time);
CREATE INDEX IF NOT EXISTS idx_chat_session_update_time ON chat_session(update_time);
CREATE INDEX IF NOT EXISTS idx_chat_session_user_id ON chat_session(user_id);

-- ==========================================
-- 3. 系统提示词配置表 (system_prompt_config) - 对齐 native
-- ==========================================
CREATE TABLE IF NOT EXISTS system_prompt_config (
    id               BIGSERIAL       PRIMARY KEY,
    name             VARCHAR(100)    DEFAULT NULL,
    role_description TEXT            DEFAULT NULL,
    model            VARCHAR(50)     DEFAULT NULL,
    is_default       SMALLINT        DEFAULT 0,
    is_deleted       SMALLINT        DEFAULT 0,
    create_time      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE system_prompt_config IS '系统提示词配置表 - 存储预定义的角色设定和提示词模板';

CREATE INDEX IF NOT EXISTS idx_system_prompt_config_is_default ON system_prompt_config(is_default);

-- ==========================================
-- 4. 用户表 (sys_user) - 对齐 native（P1 认证）
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL,
    email           VARCHAR(100)    NOT NULL,
    password        VARCHAR(255)    NOT NULL,
    nickname        VARCHAR(50)     DEFAULT NULL,
    avatar          VARCHAR(255)    DEFAULT NULL,
    status          SMALLINT        DEFAULT 1,
    deleted         SMALLINT        DEFAULT 0,
    create_time     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    last_login_time TIMESTAMP       DEFAULT NULL,
    CONSTRAINT uk_username UNIQUE (username),
    CONSTRAINT uk_email UNIQUE (email)
);
COMMENT ON TABLE sys_user IS '用户表 - 存储系统用户信息和认证数据';
COMMENT ON COLUMN sys_user.status IS '状态 - 0:禁用/1:启用';
COMMENT ON COLUMN sys_user.deleted IS '是否删除 - 0:未删除/1:已删除（软删除）';

CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user(status);
CREATE INDEX IF NOT EXISTS idx_sys_user_deleted ON sys_user(deleted);

-- 默认管理员（密码: admin123，BCrypt 加密，对齐 native）
-- 注：hash 用 spring-security-crypto BCryptPasswordEncoder 生成并 matches 验证（2026-10-16）
INSERT INTO sys_user (username, email, password, nickname, status, deleted)
VALUES ('admin', 'admin@example.com', '$2a$10$jXc2.M.5YHUXnopDEJeM/eK6uJ1JUW1Gq1vhI5x4eVjcPKatUlpfq', '管理员', 1, 0)
ON CONFLICT (username) DO NOTHING;

-- ==========================================
-- 5. Token 表 (sys_token) - 对齐 native（数据库维护 token 状态，替代 Redis）
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_token (
    id                 BIGSERIAL       PRIMARY KEY,
    user_id            BIGINT          NOT NULL,
    token              VARCHAR(500)    NOT NULL,
    refresh_token      VARCHAR(500)    DEFAULT NULL,
    device_info        VARCHAR(255)    DEFAULT NULL,
    ip_address         VARCHAR(45)     DEFAULT NULL,
    status             SMALLINT        DEFAULT 1,
    expires_at         TIMESTAMP       DEFAULT NULL,
    refresh_expires_at TIMESTAMP       DEFAULT NULL,
    create_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sys_token IS '用户Token表 - 数据库维护token状态（替代Redis）';

CREATE INDEX IF NOT EXISTS idx_sys_token_user_id ON sys_token(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_token_token ON sys_token(token);
CREATE INDEX IF NOT EXISTS idx_sys_token_status ON sys_token(status);
CREATE INDEX IF NOT EXISTS idx_sys_token_expires_at ON sys_token(expires_at);

-- ==========================================
-- 6. Token 使用统计表 (token_usage) - 对齐 native（P3）
-- ==========================================
CREATE TABLE IF NOT EXISTS token_usage (
    id            BIGSERIAL       PRIMARY KEY,
    session_id    VARCHAR(64)     DEFAULT NULL,
    message_id    BIGINT          DEFAULT NULL,
    model         VARCHAR(50)     DEFAULT NULL,
    input_tokens  INT             DEFAULT NULL,
    output_tokens INT             DEFAULT NULL,
    total_tokens  INT             DEFAULT NULL,
    cost          DECIMAL(10, 6)  DEFAULT NULL,
    cached_tokens INT             DEFAULT NULL,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE token_usage IS 'Token 使用统计表 - 记录每次 API 调用的 token 消耗和成本';

CREATE INDEX IF NOT EXISTS idx_token_usage_session_id ON token_usage(session_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_create_time ON token_usage(create_time);
CREATE INDEX IF NOT EXISTS idx_token_usage_model ON token_usage(model);
CREATE INDEX IF NOT EXISTS idx_token_usage_message_id ON token_usage(message_id);

-- ==========================================
-- 7. 知识库文档表 (knowledge_document) - 对齐 native（P4）
-- ==========================================
CREATE TABLE IF NOT EXISTS knowledge_document (
    id          BIGSERIAL       PRIMARY KEY,
    file_name   VARCHAR(255)    NOT NULL,
    file_type   VARCHAR(20)     NOT NULL,
    file_size   BIGINT          DEFAULT 0,
    file_path   VARCHAR(500)    NOT NULL,
    content     TEXT            DEFAULT NULL,
    chunk_count INT             DEFAULT 0,
    status      SMALLINT        DEFAULT 0,
    created_by  VARCHAR(50)     DEFAULT 'system',
    error_msg   VARCHAR(1000)   DEFAULT NULL,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT        DEFAULT 0
);
COMMENT ON TABLE knowledge_document IS '知识库文档表 - 上传的文档元数据与解析状态';

CREATE INDEX IF NOT EXISTS idx_knowledge_document_status ON knowledge_document(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_deleted ON knowledge_document(deleted);

-- ==========================================
-- 8. 文档分块表 (document_chunk) - 对齐 native（P4，pgvector 向量）
-- ==========================================
CREATE TABLE IF NOT EXISTS document_chunk (
    id          BIGSERIAL       PRIMARY KEY,
    document_id BIGINT          NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    chunk_index INT             NOT NULL,
    content     TEXT            NOT NULL,
    -- 维度与 embedding 模型输出对齐；doubao-embedding-vision 端点 2048（native 实测）。
    -- 如切换到其它模型，同步 ALTER TABLE ... ALTER COLUMN embedding TYPE vector(N)。
    embedding   vector(2048)    NOT NULL,
    dimension   INT             DEFAULT 0,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE document_chunk IS '文档分块表 - 内容切片与 pgvector 向量';

CREATE INDEX IF NOT EXISTS idx_document_chunk_document_id ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunk_chunk_index ON document_chunk(chunk_index);

-- =============================================================
-- 9. Prompt 模板表 (prompt_template) - 对齐 native（P5）
--    单表多版本：同 name 多 version，uk_name_version 唯一约束
-- =============================================================
CREATE TABLE IF NOT EXISTS prompt_template (
    id                   BIGSERIAL       PRIMARY KEY,
    name                 VARCHAR(100)    NOT NULL,
    version              INT             NOT NULL DEFAULT 1,
    system_prompt        TEXT            DEFAULT NULL,
    user_prompt_template TEXT            NOT NULL,
    model                VARCHAR(50)     DEFAULT NULL,
    temperature          DOUBLE PRECISION DEFAULT 0.7,
    max_tokens           INT             DEFAULT 2048,
    description          VARCHAR(500)    DEFAULT NULL,
    is_active            SMALLINT        NOT NULL DEFAULT 1,
    is_deleted           SMALLINT        NOT NULL DEFAULT 0,
    create_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_name_version UNIQUE (name, version)
);
COMMENT ON TABLE prompt_template IS 'Prompt 模板表 - 版本制模板管理（P5）';
COMMENT ON COLUMN prompt_template.user_prompt_template IS 'User Prompt 模板，{variable} 占位符';
COMMENT ON COLUMN prompt_template.is_active IS '是否启用：1 启用 0 停用（回滚时旧版本置 0）';

-- MyBatis Boolean 参数经 JDBC 发出为 bool 类型，PG 拒绝隐式转换到 smallint；
-- 加函数式 IMPLICIT CAST 修复（INOUT 转换走 I/O 路径会把 "t" 直接喂给 int2 输入函数而报错，
-- 必须用自定义函数真转换）
DO $do$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_cast
        WHERE castsource = 'boolean'::regtype AND casttarget = 'smallint'::regtype
          AND castmethod = 'i'
    ) THEN
        DROP CAST (boolean AS smallint);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_cast
        WHERE castsource = 'boolean'::regtype AND casttarget = 'smallint'::regtype
    ) THEN
        CREATE OR REPLACE FUNCTION bool_to_smallint(b boolean) RETURNS smallint
        AS $$ SELECT CASE WHEN b THEN 1 ELSE 0 END $$
        LANGUAGE sql IMMUTABLE;
        CREATE CAST (boolean AS smallint) WITH FUNCTION bool_to_smallint(boolean) AS IMPLICIT;
    END IF;
END
$do$;

-- =============================================================
-- 10. LLM 调用日志表 (llm_call_log) - 对齐 native（P6）
--     记录每次大模型调用的性能指标和费用
-- =============================================================
CREATE TABLE IF NOT EXISTS llm_call_log (
    id                BIGSERIAL       PRIMARY KEY,
    session_id        VARCHAR(64)     DEFAULT NULL,
    model_name        VARCHAR(50)     NOT NULL,
    template_name     VARCHAR(100)    DEFAULT NULL,
    prompt_tokens     INT             DEFAULT NULL,
    completion_tokens INT             DEFAULT NULL,
    total_tokens      INT             DEFAULT NULL,
    cost              DECIMAL(12, 6)  DEFAULT NULL,
    latency           BIGINT          DEFAULT NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'success',
    error_message     VARCHAR(1000)   DEFAULT NULL,
    request_summary   VARCHAR(200)    DEFAULT NULL,
    response_summary  VARCHAR(500)    DEFAULT NULL,
    user_id           BIGINT          DEFAULT NULL,
    create_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE llm_call_log IS 'LLM 调用日志表 - 记录每次大模型调用的性能指标和费用（P6）';
COMMENT ON COLUMN llm_call_log.status IS '状态：success/error/timeout';

CREATE INDEX IF NOT EXISTS idx_llm_call_log_session_id ON llm_call_log(session_id);
CREATE INDEX IF NOT EXISTS idx_llm_call_log_model_name ON llm_call_log(model_name);
CREATE INDEX IF NOT EXISTS idx_llm_call_log_status ON llm_call_log(status);
CREATE INDEX IF NOT EXISTS idx_llm_call_log_create_time ON llm_call_log(create_time);
CREATE INDEX IF NOT EXISTS idx_llm_call_log_user_id ON llm_call_log(user_id);

-- =============================================================
-- 11. Agent 定义表 (agent_definition) - 对齐 native（P7）
--     DB 持久化 + AgentRegistry 运行时缓存
-- =============================================================
CREATE TABLE IF NOT EXISTS agent_definition (
    id            VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    description   TEXT,
    avatar        VARCHAR(500),
    version       VARCHAR(32)  DEFAULT '1.0.0',
    type          VARCHAR(32)  DEFAULT 'react',
    system_prompt TEXT,
    greeting      TEXT,
    tool_ids      TEXT,
    config        TEXT,
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    creator_id    BIGINT,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE agent_definition IS 'Agent 定义表 - 动态 Agent 定义与工具绑定（P7）';
COMMENT ON COLUMN agent_definition.tool_ids IS '绑定的工具 ID 列表（JSON 数组字符串）';
COMMENT ON COLUMN agent_definition.config IS 'Agent 配置（JSON 字符串：modelId/maxIterations/temperature）';

CREATE INDEX IF NOT EXISTS idx_agent_definition_enabled ON agent_definition(enabled);

-- 种子数据：4 个内置 Agent（对齐 native agent_tables.sql，精简版）
INSERT INTO agent_definition (id, name, description, type, system_prompt, greeting, tool_ids, config)
SELECT 'general-assistant', '通用助手',
       '全能型 AI 助手，能回答各类问题、进行数学计算、查询时间和时区信息。',
       'react',
       '你是一个友好、专业的 AI 助手。你会根据用户的问题自动选择合适的工具来获取信息或执行计算。',
       '你好！我是通用助手，可以帮你计算数学问题、查询时间信息。',
       '["calculator","datetime","timezone"]',
       '{"modelId":null,"maxIterations":5,"temperature":0.7}'
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE id = 'general-assistant');

INSERT INTO agent_definition (id, name, description, type, system_prompt, greeting, tool_ids, config)
SELECT 'math-calculator', '数学计算器',
       '专注于数学计算的专业助手，支持四则运算、幂运算、复杂表达式计算。',
       'react',
       '你是一个专业的数学计算助手，准确执行用户给出的数学计算。',
       '你好！我是数学计算器，可以帮你计算各种数学表达式。',
       '["calculator"]',
       '{"modelId":null,"maxIterations":3,"temperature":0.3}'
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE id = 'math-calculator');

INSERT INTO agent_definition (id, name, description, type, system_prompt, greeting, tool_ids, config)
SELECT 'time-assistant', '时间管家',
       '专注于时间和日期查询的助手，可查询当前时间、日期、星期与时区转换。',
       'react',
       '你是一个时间管家助手，帮助用户查询和管理时间相关信息。默认时区为 Asia/Shanghai。',
       '你好！我是时间管家，可以帮你查询时间、日期和时区信息。',
       '["datetime","timezone"]',
       '{"modelId":null,"maxIterations":3,"temperature":0.5}'
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE id = 'time-assistant');

INSERT INTO agent_definition (id, name, description, type, system_prompt, greeting, tool_ids, config)
SELECT 'smart-qa', '智能问答',
       '综合性问答助手，结合多种工具为用户提供全面的答案。',
       'react',
       '你是一个智能问答助手，理解用户问题意图，选择最合适的工具获取信息并整合答案。',
       '你好！我是智能问答助手，有问题尽管问我。',
       '["calculator","datetime","timezone"]',
       '{"modelId":null,"maxIterations":5,"temperature":0.7}'
WHERE NOT EXISTS (SELECT 1 FROM agent_definition WHERE id = 'smart-qa');

-- P8a: varchar -> jsonb IMPLICIT CAST（MyBatis-Plus String 字段写 jsonb 列，对齐 bool_to_smallint 先例，幂等可重跑）
-- 注意：函数参数必须用 TEXT 而非 VARCHAR —— 若用 VARCHAR，函数体内 v::jsonb 会命中本 CAST 自身造成无限递归（栈溢出）；varchar→text 二进制兼容，cast 函数允许 TEXT 形参
CREATE OR REPLACE FUNCTION varchar_to_jsonb(v TEXT) RETURNS jsonb AS $fn$
  SELECT v::jsonb
$fn$ LANGUAGE SQL IMMUTABLE STRICT;

DROP CAST IF EXISTS (VARCHAR AS jsonb);
CREATE CAST (VARCHAR AS jsonb) WITH FUNCTION varchar_to_jsonb(v TEXT) AS IMPLICIT;

-- ==========================================
-- 12. 模型服务配置表 (llm_provider) - T3 模型配置中心
-- ==========================================
-- 职责：chat / embedding 两类模型服务的统一配置源（网页配置、动态生效）。
-- 生效链路：本表 → ModelProviderService.refresh() → LangChain4jConfig（chat 合并/激活切换）
--          与 VolcMultiModalEmbeddingModel 重建（embedding 按配置版本缓存）。
-- 兼容闸门：切换 embedding 模型后，存量向量按旧模型维度/语义生成，新索引用新模型；
--          存量文档提示「重建索引」（见 DEPLOY.md FAQ）。
CREATE TABLE IF NOT EXISTS llm_provider (
    id            BIGSERIAL       PRIMARY KEY,
    name          VARCHAR(100)    NOT NULL,
    provider_type VARCHAR(20)     NOT NULL,
    vendor        VARCHAR(20)     DEFAULT 'volcengine',
    base_url      VARCHAR(255)    DEFAULT NULL,
    api_key_enc   TEXT            DEFAULT NULL,
    model_name    VARCHAR(255)    DEFAULT NULL,
    dimension     INT             DEFAULT NULL,
    mode          VARCHAR(20)     DEFAULT NULL,
    temperature   DECIMAL(3,2)    DEFAULT 0.70,
    max_tokens    INT             DEFAULT NULL,
    description   VARCHAR(500)    DEFAULT NULL,
    is_active     BOOLEAN         NOT NULL DEFAULT FALSE,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_provider_name UNIQUE (name)
);
COMMENT ON TABLE llm_provider IS '模型服务配置表 - chat/embedding 模型服务统一配置（T3 模型配置中心）';
COMMENT ON COLUMN llm_provider.name IS '配置名称（唯一，chat 侧同时作为 LangChain4jConfig.models 的模型 ID）';
COMMENT ON COLUMN llm_provider.provider_type IS '配置类型 - chat:对话模型/embedding:向量模型';
COMMENT ON COLUMN llm_provider.vendor IS '服务商 - volcengine/aliyun/openai（OpenAI 兼容端点）';
COMMENT ON COLUMN llm_provider.api_key_enc IS 'API Key 密文（AES-256-GCM，v1:iv:ct 格式，接口层永不回显明文）';
COMMENT ON COLUMN llm_provider.dimension IS '向量维度（embedding 专用，实际以模型输出为准）';
COMMENT ON COLUMN llm_provider.mode IS 'embedding 模式 - multimodal:多模态逐条串行/text:标准批量';
COMMENT ON COLUMN llm_provider.temperature IS '随机性 0-2（chat 专用）';
COMMENT ON COLUMN llm_provider.max_tokens IS '最大生成 token 数（chat 专用）';
COMMENT ON COLUMN llm_provider.is_active IS '是否激活 - 同 provider_type 下至多一条 TRUE；chat 激活行覆盖 yml activeModel';
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_provider_active_type
    ON llm_provider(provider_type) WHERE is_active;
