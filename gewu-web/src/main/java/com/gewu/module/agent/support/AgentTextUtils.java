package com.gewu.module.agent.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排链路的纯文本 / JSON 辅助函数。
 *
 * <p>从 AgentService（原 920 行）抽取。这些方法不依赖 Spring Bean、不持有状态，
 * 抽为静态工具便于独立测试与复用。</p>
 *
 * <p>注意：UsageQuotaService 另有同名 truncate，语义不同，未合并。</p>
 */
public final class AgentTextUtils {

    /** 工具观察结果最大字符数，超出则保留首尾各一半、中间截断 */
    public static final int MAX_OBSERVATION_CHARS = 2000;

    private AgentTextUtils() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseArgs(String argsJson) {
        Map<String, Object> params = new HashMap<>();
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                params = new com.fasterxml.jackson.databind.ObjectMapper().readValue(argsJson, Map.class);
            } catch (Exception ignored) {
            }
        }
        return params;
    }

    public static String toJsonArgs(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) {
                chars += sm.text().length();
            } else if (m instanceof UserMessage um) {
                chars += um.singleText().length();
            } else if (m instanceof AiMessage am && am.text() != null) {
                chars += am.text().length();
            } else if (m instanceof ToolExecutionResultMessage tm) {
                chars += tm.text().length();
            }
        }
        return chars / 4;
    }

    public static String microCompact(String text) {
        if (text == null || text.length() <= MAX_OBSERVATION_CHARS) {
            return text;
        }
        int half = MAX_OBSERVATION_CHARS / 2;
        return text.substring(0, half) + "\n\n[... 中间内容已截断 ...]\n\n"
                + text.substring(text.length() - half);
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
