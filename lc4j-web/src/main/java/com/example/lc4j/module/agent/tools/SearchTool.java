package com.example.lc4j.module.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页搜索工具（P8c：Bing Web Search API v7 真实接入）
 *
 * 配置驱动（application.yml langchain4j.search.*）：
 * - api-key 非空 → 调用 Bing v7 端点，返回前 N 条（标题+URL+摘要）
 * - api-key 为空 → 降级返回友好提示（审批演示链路不受影响）
 *
 * 审批网关继续拦截本工具（外部网络请求需审批）。
 */
@Slf4j
@Component
public class SearchTool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${langchain4j.search.api-key:}")
    private String apiKey;

    @Value("${langchain4j.search.endpoint:https://api.bing.microsoft.com/v7.0/search}")
    private String endpoint;

    @Value("${langchain4j.search.count:5}")
    private int resultCount;

    @Value("${langchain4j.search.timeout-seconds:15}")
    private int timeoutSeconds;

    @Tool(name = "search", value = "搜索互联网获取最新信息。当用户询问实时新闻、最新动态、外部事实等需要联网查询的问题时使用。")
    public String search(
            @P("搜索关键词或问题")
            String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("search 执行（未配置 Key，降级提示）：{}", query);
            return "[搜索不可用] 未配置搜索 API Key（langchain4j.search.api-key），无法执行真实联网搜索。"
                    + "请管理员在 application.yml 配置 Bing Search API Key 后重试，"
                    + "或基于已有知识回答用户问题。查询内容：" + query;
        }

        try {
            String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + Math.max(1, Math.min(10, resultCount))
                    + "&textDecorations=false&textFormat=Raw";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Bing 搜索失败：status={}, body={}", response.statusCode(),
                        response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "");
                return "[搜索失败] Bing API 返回 HTTP " + response.statusCode() + "，请稍后重试或改用已有知识回答。";
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode webPages = root.path("webPages").path("value");
            if (!webPages.isArray() || webPages.isEmpty()) {
                return "[搜索结果] 未找到与「" + query + "」相关的网页结果。";
            }

            List<String> lines = new ArrayList<>();
            int idx = 1;
            for (JsonNode item : webPages) {
                String name = item.path("name").asText("");
                String snippet = item.path("snippet").asText("");
                String link = item.path("url").asText("");
                lines.add(String.format("%d. %s\n   %s\n   来源：%s", idx++, name, snippet, link));
                if (idx > resultCount) break;
            }
            log.info("Bing 搜索成功：query={}, hits={}", query, lines.size());
            return "[搜索结果] 关键词「" + query + "」的前 " + lines.size() + " 条结果：\n\n"
                    + String.join("\n\n", lines);
        } catch (Exception e) {
            log.error("Bing 搜索异常：query={}", query, e);
            return "[搜索异常] " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "。请改用已有知识回答用户问题。";
        }
    }
}
