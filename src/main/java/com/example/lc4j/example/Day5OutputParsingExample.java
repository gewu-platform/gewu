package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Day 5: Output Parsing + 综合练习
 *
 * 官方文档: https://docs.langchain4j.dev/tutorials/ai-services#structured-outputs
 *
 * 运行方式：设置 langchain4j.day5.outputparsing=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day5.outputparsing", havingValue = "true", matchIfMissing = false)
public class Day5OutputParsingExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 5: Output Parsing ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3) // 结构化输出需要低温度
                .build();

        // ==================== 示例 1: 返回单个 POJO ====================
        log.info("--- 示例 1: 返回 POJO ---");

        record ProductInfo(
                String name,
                String category,
                double price,
                String description,
                List<String> features
        ) {}

        interface ProductExtractor {
            @SystemMessage("你是一个产品信息提取专家，从用户描述中提取结构化的产品信息。")
            ProductInfo extract(@UserMessage String productDescription);
        }

        ProductExtractor extractor = AiServices.create(ProductExtractor.class, model);

        ProductInfo product = extractor.extract(
                "这是一款 Apple iPhone 15 Pro，属于智能手机类别，售价 7999 元。" +
                        "它采用 A17 Pro 芯片，钛金属机身，支持 USB-C 接口，4800 万像素摄像头。"
        );
        log.info("提取的产品信息: {}", product);

        // ==================== 示例 2: 返回 List ====================
        log.info("--- 示例 2: 返回 List<T> ---");

        record TaskItem(
                String title,
                String priority,
                String dueDate,
                String description
        ) {}

        interface TaskParser {
            @SystemMessage("你是一个任务分析专家，从用户描述中提取所有任务项。")
            List<TaskItem> parseTasks(@UserMessage String taskDescription);
        }

        TaskParser taskParser = AiServices.create(TaskParser.class, model);

        List<TaskItem> tasks = taskParser.parseTasks(
                "本周需要完成以下工作：1) 完成用户登录模块的 API 开发，周三前交付，高优先级；" +
                        "2) 修复订单列表的 Bug，周五前完成，中优先级；" +
                        "3) 编写单元测试覆盖率要达到 80%，下周一前完成，低优先级。"
        );
        log.info("提取的任务列表:");
        for (TaskItem task : tasks) {
            log.info("  - [{}] {} (截止: {}): {}", task.priority(), task.title(), task.dueDate(), task.description());
        }

        // ==================== 示例 3: 复杂嵌套对象 ====================
        log.info("--- 示例 3: 复杂嵌套 POJO ---");

        record ContactInfo(String email, String phone, String address) {}
        record EmployeeProfile(
                String name,
                int age,
                String department,
                String position,
                ContactInfo contact,
                List<String> skills
        ) {}

        interface ProfileExtractor {
            @SystemMessage("你是一个简历信息提取专家。")
            EmployeeProfile extract(@UserMessage String resumeText);
        }

        ProfileExtractor profileExtractor = AiServices.create(ProfileExtractor.class, model);

        EmployeeProfile profile = profileExtractor.extract(
                "张三，32 岁，在阿里巴巴担任高级 Java 开发工程师。" +
                        "联系方式：zhangsan@example.com，手机 138-0000-1234，地址：杭州市余杭区。" +
                        "擅长：Java、Spring Boot、微服务架构、分布式系统、MySQL。"
        );
        log.info("员工档案: {}", profile);

        // ==================== 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j Output Parsing:
              1. 直接返回 record/class 类型，自动 JSON 解析
              2. 支持: POJO, List<T>, Map, String, boolean, int, double
              3. 无需额外配置，内置 JSON Schema 生成
            
            Spring AI 对比:
              ChatResponse response = chatClient.prompt()
                  .user("...")
                  .call()
                  .entity(ProductInfo.class);
            
            结论: LangChain4j 直接在接口返回类型上声明，更简洁自然；
                  Spring AI 需要 .entity(Class) 链式调用
            """);

        log.info("========== Day 5 Output Parsing 完成 ==========");
    }
}
