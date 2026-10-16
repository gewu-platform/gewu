-- ==========================================
-- langchain4j_kb - PostgreSQL 数据库初始化脚本（P0: 会话表）
-- 对齐 java-ai-native ai-native-init.sql 表结构
-- 执行方式:
--   psql -h 127.0.0.1 -p 5432 -U postgres -d langchain4j_kb -f langchain4j-kb-init.sql
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
