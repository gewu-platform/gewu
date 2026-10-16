-- ==========================================
-- langchain4j_kb P1 认证表 - 对齐 java-ai-native
-- 执行方式:
--   psql -h 127.0.0.1 -p 5432 -U postgres -d langchain4j_kb -f langchain4j-kb-auth.sql
-- ==========================================

-- ==========================================
-- 1. 用户表 (sys_user) - 对齐 native
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
-- 2. Token 表 (sys_token) - 对齐 native（数据库维护 token 状态，替代 Redis）
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
