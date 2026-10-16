package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Day 1: LangChain4j 同步调用
 * 
 * 官方文档: https://docs.langchain4j.dev/get-started
 * 
 * 运行方式：在 application.yml 设置 langchain4j.day1.enabled=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day1.enabled", havingValue = "true", matchIfMissing = false)
public class Day1SyncExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private Double temperature;

    @Override
    public void run(String... args) {
        log.info("========== Day 1: LangChain4j 同步调用 ==========");

        // ==================== 1. 创建模型 ====================
        // LangChain4j 1.x: 接口名是 ChatModel（不是 ChatLanguageModel）
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();

        log.info("模型创建成功: baseUrl={}, modelName={}", baseUrl, modelName);

        // ==================== 2. 简单对话 ====================
        // LangChain4j 1.x: model.chat(String) 直接返回 String
        log.info("--- 示例 1: 简单对话 ---");
        String response1 = model.chat("你好，请用一句话介绍自己");
        log.info("AI 回复: {}", response1);

        // ==================== 3. 复杂问题 ====================
        log.info("--- 示例 2: 复杂问题 ---");
        String response2 = model.chat("用 Java 写一个快速排序算法");
        log.info("AI 回复: {}", response2);

        // ==================== 4. 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j 1.x:
              ChatModel model = OpenAiChatModel.builder()
                  .apiKey(apiKey)
                  .modelName("qwen-plus")
                  .build();
              String response = model.chat("你好");
            
            Spring AI:
              ChatResponse response = chatModel.call(new Prompt(messages));
              String content = response.getResult().getOutput().getContent();
            
            核心差异:
              1. LangChain4j 直接返回 String，Spring AI 需要多层提取
              2. LangChain4j 用 Builder 模式，Spring AI 用依赖注入
              3. LangChain4j 的接口名是 ChatModel（1.x 重命名后）
            """);

        log.info("========== Day 1 完成 ==========");
    }
}
