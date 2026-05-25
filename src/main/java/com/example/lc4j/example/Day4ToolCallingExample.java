package com.example.lc4j.example;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Day 4: Tool Calling / Function Calling
 *
 * 官方文档: https://docs.langchain4j.dev/tutorials/tools
 *
 * 运行方式：设置 langchain4j.day4.toolcall=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day4.toolcall", havingValue = "true", matchIfMissing = false)
public class Day4ToolCallingExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 4: Tool Calling / Function Calling ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3) // 工具调用需要较低温度
                .build();

        // ==================== 示例 1: 计算器工具 ====================
        log.info("--- 示例 1: 单工具调用（计算器） ---");

        interface CalculatorAssistant {
            @SystemMessage("你是一个计算助手，可以使用提供的工具来帮助用户。")
            String chat(@UserMessage String userMessage);
        }

        // 定义工具类
        class CalculatorTools {
            @Tool("计算两个数的和")
            double add(@P("第一个数") double a, @P("第二个数") double b) {
                log.info("[Tool] add({}, {})", a, b);
                return a + b;
            }

            @Tool("计算两个数的乘积")
            double multiply(@P("第一个数") double a, @P("第二个数") double b) {
                log.info("[Tool] multiply({}, {})", a, b);
                return a * b;
            }
        }

        CalculatorAssistant calcAssistant = AiServices.builder(CalculatorAssistant.class)
                .chatModel(model)
                .tools(new CalculatorTools())
                .build();

        String calcResult = calcAssistant.chat("35 乘以 128 等于多少？");
        log.info("AI: {}", calcResult);

        // ==================== 示例 2: 多工具编排 ====================
        log.info("--- 示例 2: 多工具编排（天气 + 日期 + 计算） ---");

        class WeatherTools {
            @Tool("查询指定城市的天气信息")
            String getWeather(@P("城市名称") String city) {
                log.info("[Tool] getWeather({})", city);
                Map<String, String> weatherData = Map.of(
                        "北京", "晴天，22°C，湿度 35%",
                        "上海", "多云，18°C，湿度 60%",
                        "广州", "阵雨，28°C，湿度 85%",
                        "深圳", "晴天，30°C，湿度 70%"
                );
                return weatherData.getOrDefault(city, "暂无 " + city + " 的天气数据");
            }
        }

        class DateTimeTools {
            @Tool("获取当前日期和时间")
            String getCurrentDateTime() {
                log.info("[Tool] getCurrentDateTime()");
                return java.time.LocalDateTime.now().toString();
            }
        }

        interface SmartAssistant {
            @SystemMessage("你是一个智能助手，可以使用所有可用工具帮助用户。")
            String chat(@UserMessage String userMessage);
        }

        SmartAssistant smartAssistant = AiServices.builder(SmartAssistant.class)
                .chatModel(model)
                .tools(new WeatherTools())
                .tools(new DateTimeTools())
                .tools(new CalculatorTools())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String weatherResult = smartAssistant.chat("北京今天天气怎么样？");
        log.info("AI: {}", weatherResult);

        String timeResult = smartAssistant.chat("现在几点了？");
        log.info("AI: {}", timeResult);

        // ==================== 示例 3: 综合场景 ====================
        log.info("--- 示例 3: 工具 + 记忆的综合场景 ---");

        String multiTurn1 = smartAssistant.chat("我想知道上海的天气");
        log.info("第 1 轮 AI: {}", multiTurn1);

        String multiTurn2 = smartAssistant.chat("那北京呢？");
        log.info("第 2 轮 AI: {}", multiTurn2);

        String multiTurn3 = smartAssistant.chat("两地温差多少度？");
        log.info("第 3 轮 AI: {}", multiTurn3);

        // ==================== 对比 Spring AI ====================
        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j Tool Calling:
              1. 用 @Tool 标注方法，@P 描述参数
              2. .tools(new MyTools()) 注册
              3. AI 自动决定调用哪个工具
            
            Spring AI Tool Calling:
              1. 实现 ToolCallback 接口
              2. 定义 ToolDefinition（名称、描述、参数 schema）
              3. 需要手动注册到 ToolCallingManager
            
            结论: LangChain4j 的 @Tool 注解更直观，代码更简洁；
                  Spring AI 的 Tool 体系还在完善中
            """);

        log.info("========== Day 4 Tool Calling 完成 ==========");
    }
}
