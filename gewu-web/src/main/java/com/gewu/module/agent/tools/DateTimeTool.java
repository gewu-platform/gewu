package com.gewu.module.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期时间工具（对齐 java-ai-native DateTimeTool）
 */
@Slf4j
@Component
public class DateTimeTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)", java.util.Locale.CHINA);

    @Tool(name = "datetime", value = "获取当前的日期、时间和星期。当用户询问现在几点、今天日期、今天星期几等问题时使用。")
    public String currentDateTime() {
        log.debug("datetime 执行");
        LocalDateTime now = LocalDateTime.now();
        return now.format(FORMATTER);
    }
}
