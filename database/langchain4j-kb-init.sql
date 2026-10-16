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
