package com.example.lc4j.example;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Day 5 综合练习：完整智能助手（AI Services + Memory + Tools + Output Parsing）
 *
 * 运行方式：设置 langchain4j.day5.comprehensive=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day5.comprehensive", havingValue = "true", matchIfMissing = false)
public class Day5ComprehensiveExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) throws Exception {
        log.info("========== Day 5: 综合练习 - 智能助手 ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .build();

        // ==================== 构建智能助手 ====================

        // 工具：订单查询
        class OrderQueryTools {
            @Tool("根据订单号查询订单详细信息")
            String queryOrder(@P("订单号") String orderId) {
                log.info("[Tool] queryOrder({})", orderId);
                Map<String, String> orders = Map.of(
                        "ORD001", "{\"status\":\"已发货\",\"product\":\"iPhone 15 Pro\",\"price\":7999,\"express\":\"顺丰 SF123456\"}",
                        "ORD002", "{\"status\":\"处理中\",\"product\":\"MacBook Air\",\"price\":8999,\"express\":\"待分配\"}",
                        "ORD003", "{\"status\":\"已完成\",\"product\":\"AirPods Pro\",\"price\":1899,\"express\":\"已签收\"}"
                );
                return orders.getOrDefault(orderId, "{\"error\":\"订单不存在\",\"orderId\":\"" + orderId + "\"}");
            }

            @Tool("查询用户的积分和等级")
            String queryUserPoints(@P("用户名") String username) {
                log.info("[Tool] queryUserPoints({})", username);
                return "{\"username\":\"" + username + "\",\"level\":\"VIP3\",\"points\":12580,\"coupons\":3}";
            }
        }

        // 工具：系统信息
        class SystemTools {
            @Tool("获取当前系统时间")
            String getCurrentTime() {
                log.info("[Tool] getCurrentTime()");
                return java.time.LocalDateTime.now().toString();
            }
        }

        // 智能助手接口
        interface SmartAssistant {
            @SystemMessage("""
                    你是一个智能电商助手，可以帮助用户：
                    - 查询订单状态和物流
                    - 查询用户积分和等级
                    - 回答常见问题
                    
                    请使用可用的工具来帮助用户。如果信息不足以回答，请坦诚告知。
                    回答要简洁、专业。
                    """)
            String chat(@UserMessage String userMessage);
        }

        // 返回结构化数据的接口
        record OrderSummary(
                String orderId,
                String status,
                String productName,
                String price,
                String logistics
        ) {}

        interface OrderAnalyzer {
            @SystemMessage("你是一个订单数据分析助手，从原始订单数据中提取结构化信息。")
            OrderSummary analyze(@UserMessage String orderData);
        }

        // 构建助手
        SmartAssistant assistant = AiServices.builder(SmartAssistant.class)
                .chatModel(model)
                .tools(new OrderQueryTools())
                .tools(new SystemTools())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        OrderAnalyzer analyzer = AiServices.create(OrderAnalyzer.class, model);

        // ==================== 场景演示 ====================

        log.info("\n=== 场景 1: 查询订单 ===");
        String r1 = assistant.chat("帮我查一下订单 ORD001 的状态");
        log.info("用户: 帮我查一下订单 ORD001 的状态");
        log.info("AI: {}", r1);

        log.info("\n=== 场景 2: 追问物流 ===");
        String r2 = assistant.chat("物流单号是多少？");
        log.info("用户: 物流单号是多少？");
        log.info("AI: {}", r2);

        log.info("\n=== 场景 3: 查询积分 ===");
        String r3 = assistant.chat("我的积分是多少？用户名：张三");
        log.info("用户: 我的积分是多少？用户名：张三");
        log.info("AI: {}", r3);

        log.info("\n=== 场景 4: 结构化数据提取 ===");
        OrderSummary summary = analyzer.analyze(r1);
        log.info("提取的结构化订单信息: {}", summary);

        log.info("\n=== 场景 5: 错误处理 ===");
        String r5 = assistant.chat("帮我查一下订单 INVALID999");
        log.info("用户: 帮我查一下订单 INVALID999");
        log.info("AI: {}", r5);

        log.info("\n=== 场景 6: 系统时间 ===");
        String r6 = assistant.chat("现在几点了？");
        log.info("用户: 现在几点了？");
        log.info("AI: {}", r6);

        // ==================== 第 3 周学习总结 ====================
        log.info("\n========== 第 3 周 LangChain4j 核心学习 总结 ==========");
        log.info("""
            ✅ Day 1: 同步调用 + 流式调用
               - ChatModel / StreamingChatModel
               - model.chat(String) 直接返回 String
               - StreamingChatResponseHandler 回调模式
            
            ✅ Day 2: Prompt Template + Chat Memory
               - @SystemMessage / @UserMessage / @V 注解模板
               - MessageWindowChatMemory 自动管理历史
               - chatMemoryProvider 多用户会话隔离
            
            ✅ Day 3: AI Services（高级 API）
               - AiServices.create(Interface.class, model) 接口驱动
               - TokenStream 流式输出（.onNext / .onComplete / .onError）
               - POJO 自动解析（直接返回 record 类型）
            
            ✅ Day 4: Tool Calling
               - @Tool / @P 注解定义工具
               - AI 自动决定调用哪个工具
               - 多工具编排（.tools(new A()).tools(new B())）
            
            ✅ Day 5: Output Parsing + 综合练习
               - POJO / List<T> / 嵌套对象自动解析
               - 完整智能助手（AI Services + Memory + Tools + Output Parsing）
            
            📊 Spring AI vs LangChain4j 核心差异:
               - AI Services 接口驱动 vs ChatClient 链式调用
               - @Tool 注解工具 vs ToolCallback 接口
               - ChatMemory 自动管理 vs 手动 List<Message>
               - Mustache 模板 vs 手动拼接
            
            🎯 适用场景:
               - 复杂 Agent → LangChain4j（AI Services + Tools 更优雅）
               - Spring 生态集成 → Spring AI（Auto Configuration）
               - Tool Calling → LangChain4j（@Tool 注解更直观）
               - 流式响应 → 两者皆可（Spring AI 用 Flux，LangChain4j 用 TokenStream）
            """);

        log.info("========== 综合练习完成 ==========");
    }
}
