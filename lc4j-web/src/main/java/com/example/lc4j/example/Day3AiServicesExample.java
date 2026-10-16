package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Day 3: AI Services（高级 API）
 *
 * 官方文档: https://docs.langchain4j.dev/tutorials/ai-services
 *
 * 运行方式：设置 langchain4j.day3.aiservices=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day3.aiservices", havingValue = "true", matchIfMissing = false)
public class Day3AiServicesExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) throws Exception {
        log.info("========== Day 3: AI Services（高级 API） ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .build();

        // ==================== 1. 最简单的 AI Service ====================
        log.info("--- 示例 1: 最简单的 AI Service ---");

        interface SimpleAssistant {
            String chat(String userMessage);
        }

        SimpleAssistant assistant = AiServices.create(SimpleAssistant.class, model);

        String reply1 = assistant.chat("用一句话解释 Java 中的接口和抽象类的区别");
        log.info("AI: {}", reply1);

        // ==================== 2. @SystemMessage + @UserMessage ====================
        log.info("--- 示例 2: @SystemMessage + @UserMessage ---");

        interface RolePlayingAssistant {
            @SystemMessage("你是一位{{role}}，用{{style}}风格回答问题。")
            String answer(@UserMessage String question,
                          @V("role") String role,
                          @V("style") String style);
        }

        RolePlayingAssistant roleAssistant = AiServices.create(RolePlayingAssistant.class, model);

        String reply2 = roleAssistant.answer("什么是微服务？", "架构师", "通俗易懂");
        log.info("AI: {}", reply2);

        // ==================== 3. 多参数复杂 Prompt ====================
        log.info("--- 示例 3: 复杂代码审查 Prompt ---");

        interface CodeReviewer {
            @SystemMessage("你是一位资深代码审查专家，关注代码质量、性能和安全性。")
            @UserMessage("""
                    请审查以下 {{language}} 代码：
                    
                    ```{{language}}
                    {{code}}
                    ```
                    
                    请从以下方面评价：
                    1. 代码质量
                    2. 潜在性能问题
                    3. 安全隐患
                    4. 改进建议
                    """)
            String reviewCode(@V("language") String language,
                              @V("code") String code);
        }

        CodeReviewer reviewer = AiServices.create(CodeReviewer.class, model);

        String sampleCode = """
                public class UserService {
                    private Connection conn;
                    
                    public User getUser(int id) {
                        String sql = "SELECT * FROM users WHERE id = " + id;
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery(sql);
                        if (rs.next()) {
                            return new User(rs.getInt("id"), rs.getString("name"));
                        }
                        return null;
                    }
                }
                """;

        String review = reviewer.reviewCode("java", sampleCode);
        log.info("代码审查结果:\n{}", review);

        // ==================== 4. AI Services + ChatMemory ====================
        log.info("--- 示例 4: AI Services + ChatMemory（带记忆） ---");

        interface ChatAssistant {
            @SystemMessage("你是一位友好的助手。")
            String chat(String userMessage);
        }

        ChatAssistant chatAssistant = AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        chatAssistant.chat("你好，我叫小明，我喜欢编程");
        log.info("第 1 轮完成");
        
        String reply4b = chatAssistant.chat("我刚才说了什么？");
        log.info("第 2 轮 AI: {}", reply4b);

        // ==================== 5. 流式 AI Service（TokenStream） ====================
        log.info("--- 示例 5: 流式 AI Service（TokenStream） ---");

        interface StreamingAssistant {
            @SystemMessage("你是一位诗人")
            TokenStream writePoem(String topic);
        }

        StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.9)
                .build();

        StreamingAssistant streamingAssistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(streamingModel)
                .build();

        log.info("开始流式输出:");
        StringBuilder poem = new StringBuilder();
        CompletableFuture<Void> poemDone = new CompletableFuture<>();

        streamingAssistant.writePoem("春天的程序员")
                .onPartialResponse(token -> {
                    System.out.print(token);
                    poem.append(token);
                })
                .onCompleteResponse(response -> {
                    System.out.println();
                    log.info("流式诗歌完成");
                    poemDone.complete(null);
                })
                .onError(error -> {
                    log.error("流式出错", error);
                    poemDone.completeExceptionally(error);
                })
                .start();

        poemDone.get(); // 等待完成

        // ==================== 6. 结构化返回（返回 POJO） ====================
        log.info("--- 示例 6: 结构化返回（返回 POJO） ---");

        record MovieRecommendation(
                String title,
                String genre,
                String reason,
                int year,
                double rating
        ) {}

        interface MovieRecommender {
            @SystemMessage("你是一位电影推荐专家")
            @UserMessage("请根据类型 {{genre}} 推荐一部电影")
            MovieRecommendation recommend(@V("genre") String genre);
        }

        MovieRecommender recommender = AiServices.create(MovieRecommender.class, model);

        MovieRecommendation movie = recommender.recommend("科幻");
        log.info("推荐电影: {}", movie);

        // ==================== 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j AI Services:
              1. 定义接口，用注解标注
              2. AiServices.create(Interface.class, model)
              3. 支持: String, TokenStream, POJO, List<T> 等返回类型
            
            Spring AI ChatClient:
              ChatClient chatClient = ChatClient.builder(chatModel).build();
              String reply = chatClient.prompt()
                  .system("你是...")
                  .user("你好")
                  .call()
                  .content();
            
            结论: 
              - LangChain4j AI Services 更像 MyBatis Mapper，接口定义 + 自动实现
              - Spring AI ChatClient 更像 WebClient，链式调用更灵活
              - LangChain4j 的 POJO 自动解析和流式 TokenStream 更优雅
            """);

        log.info("========== Day 3 AI Services 完成 ==========");
    }
}
