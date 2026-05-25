package com.example.lc4j.example;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Day 7: 综合实战项目 - 电商智能客服系统
 * 
 * 学习要点：
 * 1. 将 Week 3 所有知识点整合到一个实战项目
 * 2. 实现多轮对话、工具调用、Prompt 模板、结构化输出
 * 3. 模拟真实电商客服场景（订单查询、物流跟踪、售后处理）
 * 
 * 运行方式：设置 langchain4j.day7.comprehensive=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day7.comprehensive", havingValue = "true", matchIfMissing = false)
public class Day7ComprehensiveExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 7: 电商智能客服系统 ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .build();

        // ==================== 工具集：电商业务 ====================

        // 1. 订单查询工具
        class OrderTools {
            private final Map<String, Order> orders = Map.of(
                    "ORD2026001", new Order("ORD2026001", "iPhone 15 Pro 256G", 7999.0, "已发货", "2026-05-18", "SF1234567890"),
                    "ORD2026002", new Order("ORD2026002", "MacBook Air M2", 8999.0, "处理中", "2026-05-19", ""),
                    "ORD2026003", new Order("ORD2026003", "AirPods Pro 2", 1899.0, "已完成", "2026-05-15", "YTO9876543210")
            );

            @Tool("根据订单号查询订单详细信息（状态、商品、金额、物流单号等）")
            String queryOrder(@P("订单号，例如：ORD2026001") String orderId) {
                log.info("[订单工具] 查询订单: {}", orderId);
                Order order = orders.get(orderId);
                return order != null ? order.toString() : "{\"error\": \"订单不存在\", \"orderId\": \"" + orderId + "\"}";
            }

            @Tool("根据物流单号查询物流状态和轨迹")
            String queryLogistics(@P("物流单号") String trackingNumber) {
                log.info("[物流工具] 查询物流: {}", trackingNumber);
                return switch (trackingNumber) {
                    case "SF1234567890" -> "顺丰快递 SF1234567890：已发货 → 杭州转运中心 → 派件中";
                    case "YTO9876543210" -> "圆通快递 YTO9876543210：已签收，收件人已确认";
                    default -> "暂无物流单号 " + trackingNumber + " 的轨迹信息";
                };
            }
        }

        // 2. 用户服务工具
        class UserTools {
            private final Map<String, User> users = Map.of(
                    "张三", new User("张三", "VIP3", 12580, 3),
                    "李四", new User("李四", "VIP2", 5200, 1),
                    "王五", new User("王五", "VIP1", 1500, 0)
            );

            @Tool("查询用户的积分、等级和优惠券数量")
            String queryUserPoints(@P("用户名") String username) {
                log.info("[用户工具] 查询用户: {}", username);
                User user = users.get(username);
                return user != null ? user.toString() : "{\"error\": \"用户不存在\", \"username\": \"" + username + "\"}";
            }

            @Tool("查询用户的退换货记录")
            String queryReturnHistory(@P("用户名") String username) {
                log.info("[退换工具] 查询退换记录: {}", username);
                return switch (username) {
                    case "张三" -> "近 30 天无退换货记录";
                    case "李四" -> "近 30 天有 1 次退换货：订单 ORD2026002（颜色不符，已退款）";
                    default -> "暂无退换货记录";
                };
            }
        }

        // 3. 系统工具
        class SystemTools {
            @Tool("获取当前系统日期和时间")
            String getCurrentTime() {
                return LocalDateTime.now().toString();
            }
        }

        // ==================== AI Service 接口 ====================

        interface EcommerceAssistant {
            @SystemMessage("""
                    你是「智能商城」的专属客服小智，负责帮助用户解决以下问题：
                    - 查询订单状态和物流信息
                    - 查询用户积分、等级和优惠券
                    - 查询退换货记录
                    - 回答常见购物问题（发货时间、支付方式、售后政策等）
                    
                    使用规则：
                    1. 请优先使用工具获取准确信息，不要编造数据
                    2. 如果用户没有提供订单号或用户名，请礼貌地请用户补充
                    3. 回答要专业、友善、简洁
                    4. 如果信息不足以回答，请坦诚告知并提供帮助建议
                    
                    售后政策：
                    - 7 天无理由退货（未使用、包装完好）
                    - 15 天质量问题换货
                    - 电子产品 1 年质保
                    """)
            String chat(@UserMessage String userMessage);
        }

        // 结构化输出：订单摘要
        record OrderSummary(
                String orderId,
                String productName,
                Double price,
                String status,
                String logistics
        ) {}

        interface OrderAnalyzer {
            @SystemMessage("你是一个订单数据分析助手，从原始订单数据中提取结构化信息。如果字段缺失，使用 null。")
            OrderSummary analyze(@UserMessage String orderData);
        }

        // ==================== 构建客服系统 ====================

        EcommerceAssistant assistant = AiServices.builder(EcommerceAssistant.class)
                .chatModel(model)
                .tools(new OrderTools())
                .tools(new UserTools())
                .tools(new SystemTools())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        OrderAnalyzer analyzer = AiServices.create(OrderAnalyzer.class, model);

        // ==================== 场景演示 ====================

        log.info("\n========== 场景 1: 订单查询 ==========");
        String r1 = assistant.chat("你好，帮我查一下订单 ORD2026001 的状态");
        log.info("👤 用户: 帮我查一下订单 ORD2026001 的状态");
        log.info("🤖 AI: {}", r1);

        log.info("\n========== 场景 2: 物流追问 ==========");
        String r2 = assistant.chat("物流到哪了？");
        log.info("👤 用户: 物流到哪了？");
        log.info("🤖 AI: {}", r2);

        log.info("\n========== 场景 3: 查询积分 ==========");
        String r3 = assistant.chat("我叫张三，我的积分是多少？");
        log.info("👤 用户: 我叫张三，我的积分是多少？");
        log.info("🤖 AI: {}", r3);

        log.info("\n========== 场景 4: 退换货咨询 ==========");
        String r4 = assistant.chat("我想退货，需要什么条件？");
        log.info("👤 用户: 我想退货，需要什么条件？");
        log.info("🤖 AI: {}", r4);

        log.info("\n========== 场景 5: 结构化数据提取 ==========");
        OrderSummary summary = analyzer.analyze(r1);
        log.info("📊 提取的结构化订单信息: {}", summary);

        log.info("\n========== 场景 6: 多工具组合调用 ==========");
        String r6 = assistant.chat("李四的订单 ORD2026002 现在是什么状态？他之前有退换货记录吗？");
        log.info("👤 用户: 李四的订单 ORD2026002 现在是什么状态？他之前有退换货记录吗？");
        log.info("🤖 AI: {}", r6);

        log.info("\n========== 场景 7: 错误处理 ==========");
        String r7 = assistant.chat("帮我查一下订单 INVALID999");
        log.info("👤 用户: 帮我查一下订单 INVALID999");
        log.info("🤖 AI: {}", r7);

        // ==================== 第 3 周最终总结 ====================
        log.info("\n🎓 ========== 第 3 周 LangChain4j 核心学习 - 最终总结 ==========");
        log.info("""
            ✅ Day 1: 同步调用 + 流式调用
               - ChatModel / StreamingChatModel
               - model.chat(String) 直接返回 String
               - StreamingChatResponseHandler 回调模式
            
            ✅ Day 2: Prompt Template + Chat Memory
               - @SystemMessage / @UserMessage / @V 注解模板
               - MessageWindowChatMemory 自动管理历史
               - @MemoryId 多用户会话隔离
            
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
               - 完整智能助手（Services + Memory + Tools + Parsing）
            
            ✅ Day 6: RAG 入门
               - RAG 概念和工作流程
               - 文档检索 → Prompt 注入 → AI 回答
               - 生产环境使用 EmbeddingModel + EmbeddingStore
            
            ✅ Day 7: 综合实战
               - 电商智能客服系统
               - 多工具组合调用（订单/物流/用户）
               - 结构化输出 + 错误处理
               - Spring Boot 集成（@Bean / @Service / @Controller）
            
            📊 Spring AI vs LangChain4j 核心差异:
               ┌─────────────────────┬─────────────────────────────┐
               │ Spring AI           │ LangChain4j                  │
               ├─────────────────────┼─────────────────────────────┤
               │ ChatClient 链式调用  │ AiServices 接口驱动          │
               │ 自动配置 @Autowired │ 手动配置 Bean               │
               │ 手动管理 List<Message> │ ChatMemory 自动管理       │
               │ ToolCallback 接口    │ @Tool 注解                  │
               │ Flux<String> 流式   │ TokenStream / 回调模式      │
               │ .entity(Class)      │ 直接返回 POJO               │
               └─────────────────────┴─────────────────────────────┘
            
            🎯 适用场景推荐:
               - Spring Boot 项目快速集成 → Spring AI
               - 复杂 Agent / 多工具调用 → LangChain4j
               - 已有 Spring 技术栈 → Spring AI
               - 需要 Quarkus/Micronaut 支持 → LangChain4j
            """);

        log.info("🎉 ========== 第 3 周学习完成！准备进入第 4 周：RAG 进阶 ========== ");
    }

    // ==================== 内部数据模型 ====================
    record Order(String orderId, String productName, Double price, String status, String orderDate, String trackingNumber) {
        @Override
        public String toString() {
            return "{\"orderId\":\"" + orderId + "\",\"productName\":\"" + productName +
                    "\",\"price\":" + price + ",\"status\":\"" + status +
                    "\",\"orderDate\":\"" + orderDate + "\",\"trackingNumber\":\"" + trackingNumber + "\"}";
        }
    }

    record User(String username, String level, int points, int coupons) {
        @Override
        public String toString() {
            return "{\"username\":\"" + username + "\",\"level\":\"" + level +
                    "\",\"points\":" + points + ",\"coupons\":" + coupons + "}";
        }
    }
}
