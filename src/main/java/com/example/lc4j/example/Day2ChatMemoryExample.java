package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Day 2: Chat Memory（对话记忆）
 *
 * 官方文档: https://docs.langchain4j.dev/tutorials/chat-memory
 *
 * 运行方式：设置 langchain4j.day2.memory=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day2.memory", havingValue = "true", matchIfMissing = false)
public class Day2ChatMemoryExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 2: Chat Memory ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .build();

        // ==================== 示例 1: AI Services + ChatMemory ====================
        log.info("--- 示例 1: AI Services + ChatMemory（自动管理） ---");

        interface ChatWithMemory {
            @SystemMessage("你是一位友好的助手。记住用户的信息和偏好。")
            String chat(@UserMessage String userMessage);
        }

        ChatWithMemory chatBot = AiServices.builder(ChatWithMemory.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        // 多轮对话，ChatMemory 自动管理历史
        String r1 = chatBot.chat("你好，我是张三，25 岁，程序员");
        log.info("用户: 你好，我是张三，25 岁，程序员");
        log.info("AI: {}", r1);

        String r2 = chatBot.chat("我刚才介绍了自己的背景，你还记得吗？");
        log.info("用户: 我刚才介绍了自己的背景，你还记得吗？");
        log.info("AI: {}", r2);

        String r3 = chatBot.chat("我今年多大？做什么工作？");
        log.info("用户: 我今年多大？做什么工作？");
        log.info("AI: {}", r3);

        // ==================== 示例 2: 多用户会话（MemoryId） ====================
        log.info("--- 示例 2: 多用户会话（@MemoryId） ---");

        interface MultiUserChat {
            @SystemMessage("你是友好的助手")
            String chat(@dev.langchain4j.service.MemoryId int userId, @UserMessage String userMessage);
        }

        MultiUserChat multiUserChat = AiServices.builder(MultiUserChat.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();

        // 用户 1
        log.info("[用户 1] 开始对话");
        String u1r1 = multiUserChat.chat(1, "你好，我是 Alice");
        log.info("用户 1: {}", u1r1);

        // 用户 2
        log.info("[用户 2] 开始对话");
        String u2r1 = multiUserChat.chat(2, "你好，我是 Bob");
        log.info("用户 2: {}", u2r1);

        // 用户 1 再次对话 - 应该记得 Alice
        String u1r2 = multiUserChat.chat(1, "我叫什么名字？");
        log.info("用户 1 再次: 我叫什么名字？");
        log.info("AI 回复: {}", u1r2);

        boolean aliceRemembered = u1r2.contains("Alice") || u1r2.contains("alice");
        log.info("验证: 用户 1 的记忆是否包含 'Alice'? {}", aliceRemembered);

        // ==================== 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j Chat Memory:
              ChatWithMemory chatBot = AiServices.builder(ChatWithMemory.class)
                  .chatModel(model)
                  .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                  .build();
              自动管理历史，开发者无需手动 add/remove 消息

            Spring AI:
              手动管理 List<Message> messages
              需要自己实现窗口逻辑（subList、trim）
              多用户需要自己管理 Map<String, List<Message>>

            结论: LangChain4j 的记忆管理更成熟，开箱即用；
                  Spring AI 需要手动实现，但灵活性更高
            """);

        log.info("========== Day 2 Chat Memory 完成 ==========");
    }
}
