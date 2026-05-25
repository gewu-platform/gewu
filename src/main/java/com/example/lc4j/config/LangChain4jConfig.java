package com.example.lc4j.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j Spring Boot 配置类
 * 
 * 演示如何在 Spring Boot 中将 LangChain4j 的 ChatModel 注册为 Bean，
 * 实现依赖注入，与 Spring AI 的用法体验一致。
 * 
 * 对比：
 * - Spring AI：自动配置，直接 @Autowired ChatModel 即可
 * - LangChain4j：需要手动创建 Bean，但更灵活可控
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
public class LangChain4jConfig {

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Double temperature;

    /**
     * 注册 ChatModel Bean
     * 其他组件可以直接 @Autowired 注入使用
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }

    /**
     * 注册 StreamingChatModel Bean
     * 用于流式输出场景
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }
}
