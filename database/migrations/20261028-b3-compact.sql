-- ==========================================
-- T3 v1.0.1：用户对话设置（compact 配置 per-user）
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_user_setting (
    user_id      BIGINT       PRIMARY KEY,
    settings     TEXT         DEFAULT NULL,
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
-- settings JSON: {"autoCompact":true,"compactThreshold":8000,"compactKeepRecent":20}
COMMENT ON TABLE sys_user_setting IS '用户级设置表 - 对话压缩等个人偏好（JSON 文本）';

-- T3 上下文压缩标记：1 = 内容已并入会话 context_summary，构建 LLM 上下文时跳过
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS compressed SMALLINT DEFAULT 0;
