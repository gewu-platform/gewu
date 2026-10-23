package com.gewu.module.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 时区转换工具（对齐 java-ai-native TimeZoneTool）
 */
@Slf4j
@Component
public class TimeZoneTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 常用城市 → IANA 时区映射 */
    private static final Map<String, String> CITY_ZONES = Map.ofEntries(
            Map.entry("北京", "Asia/Shanghai"),
            Map.entry("上海", "Asia/Shanghai"),
            Map.entry("东京", "Asia/Tokyo"),
            Map.entry("首尔", "Asia/Seoul"),
            Map.entry("新加坡", "Asia/Singapore"),
            Map.entry("伦敦", "Europe/London"),
            Map.entry("巴黎", "Europe/Paris"),
            Map.entry("纽约", "America/New_York"),
            Map.entry("洛杉矶", "America/Los_Angeles"),
            Map.entry("悉尼", "Australia/Sydney"),
            Map.entry("迪拜", "Asia/Dubai"),
            Map.entry("莫斯科", "Europe/Moscow")
    );

    @Tool(name = "timezone", value = "查询指定城市/时区当前的日期时间。当用户询问某个城市现在几点、跨时区时间换算时使用。")
    public String timezone(
            @P("城市名（如 北京/东京/伦敦/纽约）或 IANA 时区 ID（如 Asia/Shanghai）")
            String city) {
        log.debug("timezone 执行：{}", city);

        String zoneId = CITY_ZONES.getOrDefault(city, city);
        try {
            ZoneId zone = ZoneId.of(zoneId);
            ZonedDateTime now = ZonedDateTime.now(zone);
            return String.format("%s 当前时间：%s（%s）",
                    city, now.format(FORMATTER), zone.getId());
        } catch (Exception e) {
            return "无法识别的时区：" + city + "。支持城市：" + String.join("/", CITY_ZONES.keySet());
        }
    }
}
