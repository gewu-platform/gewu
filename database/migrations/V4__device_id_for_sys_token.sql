ALTER TABLE sys_token ADD COLUMN IF NOT EXISTS device_id VARCHAR(64) DEFAULT NULL;
COMMENT ON COLUMN sys_token.device_id IS '设备标识 - 客户端 localStorage UUID（P19 多设备共活）；空=升级前旧记录';
CREATE INDEX IF NOT EXISTS idx_sys_token_user_device ON sys_token(user_id, device_id);
