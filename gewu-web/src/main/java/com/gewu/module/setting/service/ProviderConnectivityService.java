package com.gewu.module.setting.service;

import com.gewu.common.GewuCrypto;
import com.gewu.module.setting.entity.LlmProvider;
import com.gewu.module.setting.mapper.LlmProviderMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型连通性测试服务（T3）
 *
 * <p>chat：OpenAI 兼容 /chat/completions 微型补全（max_tokens=16）；</p>
 * <p>embedding：按 mode 走真实端点——multimodal 走 /embeddings/multimodal（data 为对象），
 * text 走标准 /embeddings（data 为数组）。直接用 JDK HttpClient，避免为测试引入模型实例。</p>
 *
 * <p>返回 {success, message, latencyMs, dimension?}；任何异常折叠为失败 message，不抛出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConnectivityService {

    private final LlmProviderMapper providerMapper;

    /**
     * 测试指定配置的连通性
     */
    public Map<String, Object> test(Long id) {
        LlmProvider p = providerMapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("配置不存在: id=" + id);
        }
        if (!StringUtils.hasText(p.getApiKeyEnc())) {
            throw new IllegalArgumentException("该配置尚未设置 API Key，无法测试");
        }
        String apiKey = GewuCrypto.isEncrypted(p.getApiKeyEnc())
                ? GewuCrypto.decrypt(p.getApiKeyEnc())
                : p.getApiKeyEnc();

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = "embedding".equals(p.getProviderType())
                    ? testEmbedding(p, apiKey)
                    : testChat(p, apiKey);
            result.put("latencyMs", System.currentTimeMillis() - start);
            log.info("[T3] 连通性测试: name={}, type={}, success={}",
                    p.getName(), p.getProviderType(), result.get("success"));
            return result;
        } catch (Exception e) {
            log.warn("[T3] 连通性测试失败: name={}, err={}", p.getName(), e.getMessage());
            Map<String, Object> fail = new HashMap<>();
            fail.put("success", false);
            fail.put("message", summarizeError(e));
            fail.put("latencyMs", System.currentTimeMillis() - start);
            return fail;
        }
    }

    // ==================== chat 测试 ====================

    private Map<String, Object> testChat(LlmProvider p, String apiKey) {
        // 独立短超时实例，与运行时模型实例隔离
        // 探针构建：thinking 压制对齐工厂（doubao-seed 系列不开 thinking 才回 content）
        Map<String, Object> custom = new HashMap<>();
        custom.put("thinking", Map.of("type", "disabled"));
        ChatModel probe = OpenAiChatModel.builder()
                .baseUrl(p.getBaseUrl())
                .apiKey(apiKey)
                .modelName(p.getModelName())
                .temperature(0.0)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(0)
                .maxTokens(16)
                .defaultRequestParameters(OpenAiChatRequestParameters.builder().customParameters(custom).build())
                .logRequests(false)
                .logResponses(false)
                .build();
        String answer = probe.chat(java.util.List.of(UserMessage.from("回复OK 两个字母即可"))).aiMessage().text();
        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("message", "对话模型连通正常，回复: " + (answer == null ? "(空)" :
                answer.substring(0, Math.min(20, answer.length()))));
        return ok;
    }

    // ==================== embedding 测试 ====================

    /**
     * embedding 真实端点测试（JDK HttpClient，不走模型实例）
     */
    private Map<String, Object> testEmbedding(LlmProvider p, String apiKey) throws Exception {
        String base = p.getBaseUrl().endsWith("/")
                ? p.getBaseUrl().substring(0, p.getBaseUrl().length() - 1)
                : p.getBaseUrl();
        boolean multimodal = !"text".equals(p.getMode());
        String url = multimodal ? base + "/embeddings/multimodal" : base + "/embeddings";

        String body = multimodal
                ? "{\"model\":\"" + p.getModelName() + "\",\"input\":[{\"type\":\"text\",\"text\":\"连通性测试\"}]}"
                : "{\"model\":\"" + p.getModelName() + "\",\"input\":[\"连通性测试\"]}";

        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        Map<String, Object> result = new HashMap<>();
        if (resp.statusCode() / 100 != 2) {
            result.put("success", false);
            result.put("message", "HTTP " + resp.statusCode() + ": " + summarizeBody(resp.body()));
            return result;
        }
        int dim = extractEmbeddingDimension(resp.body(), multimodal);
        result.put("success", dim > 0);
        result.put("dimension", dim);
        result.put("message", dim > 0
                ? "向量模型连通正常，输出维度=" + dim
                : "端点返回 200 但未能解析出向量（响应格式异常）");
        return result;
    }

    /**
     * 从响应体提取向量维度（multimodal: data.embedding / text: data[0].embedding）
     */
    private int extractEmbeddingDimension(String body, boolean multimodal) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            var data = root.path("data");
            var embedding = multimodal
                    ? data.path("embedding")
                    : (data.isArray() && data.size() > 0 ? data.get(0).path("embedding") : com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            return embedding.isArray() ? embedding.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String summarizeBody(String body) {
        if (body == null) return "(空)";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private String summarizeError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
