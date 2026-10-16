package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Day 2: Prompt Template
 *
 * 官方文档: https://docs.langchain4j.dev/tutorials/ai-services#systemmessage
 *
 * 运行方式：设置 langchain4j.day2.template=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day2.template", havingValue = "true", matchIfMissing = false)
public class Day2PromptTemplateExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 2: Prompt Template ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .build();

        // ==================== 1. @SystemMessage + @UserMessage 模板 ====================
        log.info("--- 示例 1: @SystemMessage + @UserMessage ---");

        interface RoleAssistant {
            @SystemMessage("你是一位{{role}}，请用{{style}}风格回答用户问题。")
            String answer(@UserMessage String question,
                          @V("role") String role,
                          @V("style") String style);
        }

        RoleAssistant assistant = AiServices.create(RoleAssistant.class, model);

        String response1 = assistant.answer("什么是多态？", "Java 老师", "通俗易懂");
        log.info("AI: {}", response1);

        // ==================== 2. 复杂模板 ====================
        log.info("--- 示例 2: 复杂代码审查模板 ---");

        interface CodeReviewer {
            @SystemMessage("你是一位资深代码审查专家，关注代码质量、性能和安全性。")
            @UserMessage("""
                    请审查以下 {{language}} 代码：
                    {{code}}
                    请从代码质量、性能、安全性三个方面评价。
                    """)
            String review(@V("language") String language,
                          @V("code") String code);
        }

        CodeReviewer reviewer = AiServices.create(CodeReviewer.class, model);

        String code = """
                public class UserService {
                    public User getUser(int id) {
                        String sql = "SELECT * FROM users WHERE id = " + id;
                        Statement stmt = conn.createStatement();
                        return stmt.executeQuery(sql);
                    }
                }
                """;

        String review = reviewer.review("java", code);
        log.info("代码审查结果:\n{}", review);

        // ==================== 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j:
              interface Assistant {
                  @SystemMessage("你是{{role}}")
                  String chat(@UserMessage String msg, @V("role") String role);
              }
              Assistant assistant = AiServices.create(Assistant.class, model);
              语法: Mustache ({{variable}})

            Spring AI:
              ChatResponse response = chatClient.prompt()
                  .system("你是" + role)
                  .user(msg)
                  .call()
                  .content();
              语法: 需要手动拼接或使用 PromptTemplate

            结论: LangChain4j 的注解模板更优雅，Spring AI 需要手动管理 Prompt 拼接
            """);

        log.info("========== Day 2 Prompt Template 完成 ==========");
    }
}
