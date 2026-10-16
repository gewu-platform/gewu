package com.example.lc4j.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 火山方舟多模态 Embedding 适配器（对齐 java-ai-native VolcEmbeddingClient 功能形态）
 *
 * 适配 doubao-embedding-vision 多模态模型
 * API 路径: /api/v3/embeddings/multimodal
 * 请求格式: input 为对象数组 [{type:"text", text:"..."}]
 *
 * 响应格式: {"created":123,"data":{"embedding":[...]}}
 * 注意：data 是对象不是数组（与 OpenAI 标准 /embeddings 不同）
 *
 * 对齐 native 的能力：
 * 1. 双模式：multimodal（多模态端点，逐条串行）/ text（标准 /embeddings，真批量）
 * 2. 429 限流自动退避重试（最多 3 次，2s/4s/6s 递增）
 * 3. 动态维度记录（首次调用后缓存实际维度）
 *
 * @author Matilda
 * @since 2026-06-10
 */
@Slf4j
public class VolcMultiModalEmbeddingModel extends DimensionAwareEmbeddingModel {

    /** multimodal 端点：多模态融合模型，每次请求融合成 1 个向量，必须逐条串行 */
    public static final String MODE_MULTIMODAL = "multimodal";
    /** text 端点：OpenAI 兼容 /embeddings，支持真批量 */
    public static final String MODE_TEXT = "text";

    private static final int MAX_RETRIES = 3;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String mode;
    /** 配置的预期维度（建表维度），0 表示未知 */
    private final int configuredDimension;
    /** 首次调用后缓存的实际维度 */
    private volatile Integer observedDimension;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VolcMultiModalEmbeddingModel(String baseUrl, String apiKey, String model, int dimension) {
        this(baseUrl, apiKey, model, dimension, MODE_MULTIMODAL);
    }

    public VolcMultiModalEmbeddingModel(String baseUrl, String apiKey, String model,
                                        int dimension, String mode) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.configuredDimension = dimension;
        this.mode = mode == null || mode.isBlank() ? MODE_MULTIMODAL : mode.toLowerCase();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected Integer knownDimension() {
        if (observedDimension != null) {
            return observedDimension;
        }
        return configuredDimension > 0 ? configuredDimension : null;
    }

    /** 实际维度（已观察优先，否则配置值） */
    public int dimension() {
        Integer known = knownDimension();
        return known != null ? known : 0;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(new ArrayList<>(), new TokenUsage(0, 0));
        }

        List<Embedding> embeddings;
        int totalInputTokens;
        if (MODE_TEXT.equals(mode)) {
            embeddings = embedBatchText(segments);
            totalInputTokens = 0; // text 端点 usage 在批量响应中，见 embedBatchText 内部累计
        } else {
            embeddings = new ArrayList<>(segments.size());
            totalInputTokens = 0;
            // 多模态融合端点会把多个 input 融合成 1 个向量，必须逐条串行
            for (int idx = 0; idx < segments.size(); idx++) {
                embeddings.add(embedSingleWithRetry(segments.get(idx), idx));
            }
        }

        if (embeddings.size() != segments.size()) {
            throw new RuntimeException("Embedding count mismatch: expected "
                    + segments.size() + " got " + embeddings.size() + " (mode=" + mode + ")");
        }
        return Response.from(embeddings, new TokenUsage(totalInputTokens, 0));
    }

    /**
     * 多模态端点单条调用，429 自动退避重试（最多 3 次：2s/4s/6s）
     * 对齐 native embedSingleWithRetry
     */
    private Embedding embedSingleWithRetry(TextSegment segment, int idx) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        ArrayNode inputArray = requestBody.putArray("input");
        ObjectNode textItem = inputArray.addObject();
        textItem.put("type", "text");
        textItem.put("text", segment.text());

        return requestWithRetry(
                () -> jsonBodyOf(requestBody),
                body -> parseSingleEmbedding(body, idx),
                idx);
    }

    /**
     * 纯文本端点 /embeddings（OpenAI 兼容）：input 为字符串数组，真批量
     * 对齐 native embedBatchText
     */
    private List<Embedding> embedBatchText(List<TextSegment> segments) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        ArrayNode inputArray = requestBody.putArray("input");
        for (TextSegment segment : segments) {
            inputArray.add(segment.text());
        }

        final List<Embedding> result = new ArrayList<>(segments.size());
        requestWithRetry(
                () -> jsonBodyOf(requestBody),
                body -> {
                    result.clear();
                    result.addAll(parseBatchEmbeddings(body));
                    return true;
                },
                0);
        return result;
    }

    /** 统一请求 + 429 退避重试骨架 */
    private <T> T requestWithRetry(java.util.function.Supplier<String> bodySupplier,
                                   java.util.function.Function<String, T> parser,
                                   int idx) {
        String jsonBody = bodySupplier.get();
        String url = MODE_TEXT.equals(mode)
                ? baseUrl + "/embeddings"
                : baseUrl + "/embeddings/multimodal";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    T parsed = parser.apply(response.body());
                    recordObservedDimension(response.body());
                    return parsed;
                }

                // 429 限流退避重试：2s / 4s / 6s
                if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                    long waitMs = 2000L * (attempt + 1);
                    log.warn("Embedding 限流 idx={}, attempt={}, 等待 {}ms, 响应: {}",
                            idx, attempt, waitMs, truncate(response.body()));
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                throw new RuntimeException("Embedding API error idx=" + idx
                        + ": HTTP " + response.statusCode() + " - " + truncate(response.body()));
            } catch (java.io.IOException e) {
                throw new RuntimeException("Embedding 请求失败 idx=" + idx, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Embedding 请求被中断 idx=" + idx, e);
            }
        }
        throw new RuntimeException("Embedding 失败（已重试 " + MAX_RETRIES + " 次）idx=" + idx);
    }

    private String jsonBodyOf(ObjectNode requestBody) {
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("Embedding 请求体序列化失败", e);
        }
    }

    /** 解析多模态端点响应：data 为对象 */
    private Embedding parseSingleEmbedding(String body, int idx) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                throw new RuntimeException("Embedding 响应缺少 data 对象 idx=" + idx + ": " + truncate(body));
            }
            JsonNode embeddingNode = data.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray() || embeddingNode.isEmpty()) {
                throw new RuntimeException("Embedding 响应缺少 data.embedding 数组 idx=" + idx + ": " + truncate(body));
            }
            return new Embedding(toFloatArray(embeddingNode));
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 响应解析失败 idx=" + idx, e);
        }
    }

    /** 解析标准端点响应：data 为数组，按 index 排序 */
    private List<Embedding> parseBatchEmbeddings(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new RuntimeException("Embedding 批量响应缺少 data 数组: " + truncate(body));
            }
            // 按 index 排序保证顺序一致
            List<JsonNode> nodes = new ArrayList<>();
            data.forEach(nodes::add);
            nodes.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));

            List<Embedding> embeddings = new ArrayList<>(nodes.size());
            for (JsonNode node : nodes) {
                JsonNode embeddingNode = node.get("embedding");
                if (embeddingNode == null || !embeddingNode.isArray()) {
                    throw new RuntimeException("Embedding 批量响应缺少 embedding 字段: " + truncate(body));
                }
                embeddings.add(new Embedding(toFloatArray(embeddingNode)));
            }
            return embeddings;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 批量响应解析失败", e);
        }
    }

    /** 从响应 usage 记录实际维度（首次成功调用后缓存） */
    private void recordObservedDimension(String body) {
        if (observedDimension != null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            JsonNode embeddingNode = null;
            if (data != null && data.isObject()) {
                embeddingNode = data.get("embedding");
            } else if (data != null && data.isArray() && !data.isEmpty()) {
                embeddingNode = data.get(0).get("embedding");
            }
            if (embeddingNode != null && embeddingNode.isArray()) {
                observedDimension = embeddingNode.size();
                log.info("Embedding 实际维度: {} (model={})", observedDimension, model);
            }
        } catch (Exception e) {
            log.debug("记录 embedding 维度失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private float[] toFloatArray(JsonNode arrayNode) {
        float[] vector = new float[arrayNode.size()];
        for (int i = 0; i < arrayNode.size(); i++) {
            vector[i] = (float) arrayNode.get(i).asDouble();
        }
        return vector;
    }

    private String truncate(String s) {
        if (s == null) {
            return "empty";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
