package com.gewu.module.user.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.gewu.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * T3 用户级对话设置（v1.0.1 B3）：autoCompact / compactThreshold / compactKeepRecent
 *
 * <p>存储：sys_user_setting（user_id 主键，settings 为 JSON 文本）。无行 = 全默认。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingService {

    /** 默认值（与 ContextCompactService 常量一致） */
    public static final boolean DEFAULT_AUTO_COMPACT = true;
    public static final int DEFAULT_THRESHOLD = 8000;
    public static final int DEFAULT_KEEP_RECENT = 20;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 读用户设置（不存在/损坏 → 默认值） */
    public Map<String, Object> loadSettings(Long userId) {
        Map<String, Object> conf = new HashMap<>();
        conf.put("autoCompact", DEFAULT_AUTO_COMPACT);
        conf.put("compactThreshold", DEFAULT_THRESHOLD);
        conf.put("compactKeepRecent", DEFAULT_KEEP_RECENT);
        if (userId == null) {
            return conf;
        }
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT settings FROM sys_user_setting WHERE user_id = ?", userId);
            if (!rows.isEmpty() && rows.get(0).get("settings") != null) {
                JsonNode n = objectMapper.readTree(rows.get(0).get("settings").toString());
                if (n.hasNonNull("autoCompact")) conf.put("autoCompact", n.get("autoCompact").asBoolean());
                if (n.hasNonNull("compactThreshold")) conf.put("compactThreshold", n.get("compactThreshold").asInt());
                if (n.hasNonNull("compactKeepRecent")) conf.put("compactKeepRecent", n.get("compactKeepRecent").asInt());
            }
        } catch (Exception e) {
            log.warn("[T3] 读取用户设置失败（用默认）：userId={}, err={}", userId, e.getMessage());
        }
        return conf;
    }

    /** 写用户设置（UPSERT；部分更新：只传的字段覆盖，其余保默认） */
    public void saveSettings(Long userId, Map<String, Object> patch) {
        if (userId == null) {
            throw new BizException(401, "请先登录");
        }
        Map<String, Object> merged = loadSettings(userId);
        if (patch.containsKey("autoCompact")) merged.put("autoCompact", Boolean.TRUE.equals(patch.get("autoCompact")) || "true".equals(patch.get("autoCompact")));
        if (patch.containsKey("compactThreshold")) {
            int v = ((Number) patch.get("compactThreshold")).intValue();
            if (v < 1000 || v > 200000) throw new BizException(400, "压缩阈值取值 1000-200000 tokens");
            merged.put("compactThreshold", v);
        }
        if (patch.containsKey("compactKeepRecent")) {
            int v = ((Number) patch.get("compactKeepRecent")).intValue();
            if (v < 2 || v > 200) throw new BizException(400, "保留最近消息数取值 2-200");
            merged.put("compactKeepRecent", v);
        }
        try {
            String json = objectMapper.writeValueAsString(merged);
            int n = jdbcTemplate.update(
                    "UPDATE sys_user_setting SET settings = ?, update_time = CURRENT_TIMESTAMP WHERE user_id = ?",
                    json, userId);
            if (n == 0) {
                jdbcTemplate.update(
                        "INSERT INTO sys_user_setting (user_id, settings) VALUES (?, ?)", userId, json);
            }
            log.info("[T3] 用户设置已保存: userId={}", userId);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[T3] 保存用户设置失败", e);
            throw new BizException(500, "保存设置失败：" + e.getMessage());
        }
    }
}
