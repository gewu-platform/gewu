package com.example.lc4j.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j Spring Boot 多模型配置类
 * 
 * 火山引擎 / 百炼等均使用 OpenAI 兼容的 /chat/completions 端点
 * OpenAiChatModel 可直接使用，无需自定义适配器
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j")
public class LangChain4jConfig {

    /**
     * 当前激活的模型 ID
     */
    private String activeModel = "volc-lite-endpoint";

    /**
     * 多模型配置 key: 模型 ID, value: 模型配置
     */
    private Map<String, ModelConfig> models = new HashMap<>();

    /**
     * ChatModel 缓存池
     */
    private final Map<String, ChatModel> chatClientCache = new ConcurrentHashMap<>();

    /**
     * StreamingChatModel 缓存池
     */
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    @Data
    public static class ModelConfig {

        /**
         * 服务商类型：volcengine / aliyun / openai
         */
        private String provider;

        /**
         * API Base URL（火山引擎为 https://ark.cn-beijing.volces.com/api/v3）
         */
        private String baseUrl;

        /**
         * API Key
         */
        private String apiKey;

        /**
         * 模型名称（火山引擎为端点 ID，如 ep-xxx）
         */
        private String modelName;

        /**
         * 随机性 (0-2)
         */
        private Double temperature = 0.7;

        /**
         * 最大生成 token 数（控制回复长度，默认 1024）
         */
        private Integer maxTokens = 1024;

        /**
         * 模型描述（用于前端展示）
         */
        private String description;
    }

    /**
     * 根据模型 ID 获取 ChatModel
     */
    public ChatModel getChatModel(String modelId) {
        return chatClientCache.computeIfAbsent(modelId, this::createChatModel);
    }

    /**
     * 根据模型 ID 获取 StreamingChatModel
     */
    public StreamingChatModel getStreamingChatModel(String modelId) {
        return streamingCache.computeIfAbsent(modelId, this::createStreamingChatModel);
    }

    /**
     * 获取当前激活的 ChatModel
     */
    public ChatModel getActiveChatModel() {
        return getChatModel(activeModel);
    }

    /**
     * 根据模型 ID 获取带 maxTokens 限制的 ChatModel
     * @param modelId 模型 ID
     * @param maxTokens 最大生成 token 数
     */
    public ChatModel getChatModel(String modelId, int maxTokens) {
        return chatClientCache.computeIfAbsent(modelId + "_max" + maxTokens, id -> createChatModel(modelId, maxTokens));
    }

    /**
     * 创建 ChatModel
     * 所有服务商均使用 OpenAI 兼容的 /chat/completions 端点
     */
    private ChatModel createChatModel(String modelId) {
        ModelConfig config = models.get(modelId);
        if (config == null) {
            throw new IllegalArgumentException("未知模型 ID: " + modelId +
                    ", 可用模型：" + models.keySet());
        }

        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(300))
                .maxRetries(2)
                .maxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 1024)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 创建 ChatModel（带自定义 maxTokens）
     */
    private ChatModel createChatModel(String modelId, int maxTokens) {
        ModelConfig config = models.get(modelId);
        if (config == null) {
            throw new IllegalArgumentException("未知模型 ID: " + modelId);
        }

        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(300))
                .maxRetries(2)
                .maxTokens(maxTokens)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 创建 StreamingChatModel
     */
    private StreamingChatModel createStreamingChatModel(String modelId) {
        ModelConfig config = models.get(modelId);
        if (config == null) {
            throw new IllegalArgumentException("未知模型 ID: " + modelId +
                    ", 可用模型：" + models.keySet());
        }

        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(300))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 清除指定模型的缓存
     */
    public void evictChatModel(String modelId) {
        chatClientCache.remove(modelId);
        streamingCache.remove(modelId);
    }

    /**
     * 清除所有缓存
     */
    public void evictAllChatModels() {
        chatClientCache.clear();
        streamingCache.clear();
    }
}
