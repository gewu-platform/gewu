package com.gewu.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库 Embedding 模型配置（对齐 java-ai-native）
 *
 * 提供 VolcMultiModalEmbeddingModel 单例 Bean，供知识库索引/检索注入使用。
 * 配置来源：yml 的 langchain4j.embedding 段（base-url / api-key / model / dimension / mode）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.embedding")
public class EmbeddingModelConfig {

    /**
     * Embedding API Base URL（火山为 https://ark.cn-beijing.volces.com/api/v3）
     */
    private String baseUrl;

    /**
     * API Key（环境变量占位）
     */
    private String apiKey;

    /**
     * 模型/端点 ID（如 ep-xxx）
     */
    private String model;

    /**
     * 向量维度（建表维度，实际以模型输出为准）
     */
    private int dimension = 2048;

    /**
     * multimodal: 多模态端点逐条串行 | text: 标准 /embeddings 真批量
     */
    private String mode = "multimodal";

    @Bean
    public VolcMultiModalEmbeddingModel embeddingModel() {
        return new VolcMultiModalEmbeddingModel(baseUrl, apiKey, model, dimension, mode);
    }
}
