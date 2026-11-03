-- V2.4.0 C2：审计日志表（与 init.sql 同构；幂等，供已有库增量升级）
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL       PRIMARY KEY,
    actor         VARCHAR(64)     DEFAULT NULL,
    action        VARCHAR(64)     NOT NULL,
    target_type   VARCHAR(64)     DEFAULT NULL,
    target_id     VARCHAR(128)    DEFAULT NULL,
    detail        TEXT            DEFAULT NULL,
    ip            VARCHAR(64)     DEFAULT NULL,
    success       SMALLINT        DEFAULT 1,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
