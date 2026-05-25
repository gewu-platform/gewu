# LangChain4j 学习指南

> 面向已有 Spring AI 经验的 Java 开发者

## 📖 推荐学习顺序

### 第一步：理解 LangChain4j 整体架构（30 分钟）

**先读官方文档，不要急着写代码。**

打开这些页面按顺序阅读：

1. **Get Started** — https://docs.langchain4j.dev/get-started
   - 了解 LangChain4j 的定位：Java 原生 AI 应用框架
   - 理解核心概念：ChatModel、StreamingChatModel、AI Services
   - 重点：LangChain4j 不是 LangChain 的 Java 移植版，是为 Java 原生设计的

2. **AI Services** — https://docs.langchain4j.dev/tutorials/ai-services
   - 这是 LangChain4j **最重要**的特性
   - 类比 MyBatis：定义接口 → LangChain4j 自动实现
   - 掌握：@SystemMessage、@UserMessage、@V 注解

3. **Chat Memory** — https://docs.langchain4j.dev/tutorials/chat-memory
   - 理解 MessageWindowChatMemory vs TokenWindowChatMemory
   - 理解多用户会话隔离（chatMemoryProvider）

4. **Tools** — https://docs.langchain4j.dev/tutorials/tools
   - @Tool / @P 注解
   - AI 自动决定调用哪个工具

### 第二步：跑通 Day 1 示例（1 小时）

```bash
# 1. 设置 API Key
$env:DASHSCOPE_API_KEY="sk-your-key"

# 2. 修改 application.yml
langchain4j:
  day1:
    enabled: true    # 开启同步示例

# 3. 在 IDEA 中运行 Day1SyncExample
```

**学习目标：**
- 理解 `ChatModel model = OpenAiChatModel.builder()...build()` 创建模型
- 理解 `model.chat("你好")` 直接返回 String
- **对比 Spring AI**：Spring AI 需要 `ChatResponse response = chatModel.call(new Prompt(...))`，然后多层提取内容

### 第三步：跑通 Day 2 示例（1.5 小时）

```yaml
langchain4j:
  day2:
    template: true   # Prompt Template
    memory: false    # 先专注模板
```

**学习目标：**
- 理解 `@SystemMessage("你是{{role}}")` + `@V("role")` 的模板语法
- 理解 Mustache 语法（双大括号 `{{variable}}`）
- 对比 Spring AI：Spring AI 没有这种注解模板，需要手动拼接

然后开启 memory：
```yaml
langchain4j:
  day2:
    template: false
    memory: true
```

**学习目标：**
- 理解 `AiServices.builder().chatMemory(MessageWindowChatMemory.withMaxMessages(10))`
- 理解 ChatMemory 自动管理历史，不需要手动 add/remove 消息
- **对比 Spring AI**：Spring AI 需要手动管理 `List<Message>`

### 第四步：跑通 Day 3 示例（1.5 小时）

```yaml
langchain4j:
  day3:
    aiservices: true
```

**学习目标：**
- 理解 `AiServices.create(Interface.class, model)` 接口驱动
- 理解 `TokenStream` 流式输出（.onNext / .onComplete / .onError）
- 理解 POJO 自动解析（直接返回 record 类型）
- **对比 Spring AI**：Spring AI ChatClient 是链式调用，LangChain4j AI Services 是接口定义

### 第五步：跑通 Day 4 示例（1.5 小时）

```yaml
langchain4j:
  day4:
    toolcall: true
```

**学习目标：**
- 理解 `@Tool("描述")` + `@P("参数描述")` 定义工具
- 理解 AI 自动决定调用哪个工具
- 理解多工具编排（`.tools(new A()).tools(new B())`）
- **对比 Spring AI**：Spring AI 需要实现 ToolCallback 接口，LangChain4j 一个注解搞定

### 第六步：跑通 Day 5 示例（2 小时）

```yaml
langchain4j:
  day5:
    outputparsing: true
    comprehensive: true
```

**学习目标：**
- 理解 POJO / List\<T\> / 嵌套对象自动解析
- 跑通综合示例，把所有学到的东西串起来

## 🎯 核心概念速查

### LangChain4j vs Spring AI 对照表

| 功能 | LangChain4j | Spring AI |
|------|-------------|-----------|
| **创建模型** | `OpenAiChatModel.builder()...build()` | 依赖注入 `@Autowired ChatModel` |
| **简单调用** | `model.chat("你好")` → String | `chatModel.call(new Prompt(...))` → ChatResponse |
| **高级 API** | `AiServices.create(Interface.class, model)` | `ChatClient.builder(chatModel).build()` |
| **模板** | `@SystemMessage("{{role}}")` + `@V("role")` | 手动拼接或 PromptTemplate |
| **记忆** | `.chatMemory(MessageWindowChatMemory...)` | 手动管理 `List<Message>` |
| **工具** | `@Tool("描述")` 注解 | 实现 `ToolCallback` 接口 |
| **流式** | `TokenStream` + `.onNext()` | `Flux<String>` + `.subscribe()` |
| **结构化输出** | 直接返回 POJO | `.entity(Class)` |

### LangChain4j 核心 API 速查

```java
// 1. 创建模型
ChatModel model = OpenAiChatModel.builder()
    .baseUrl("...")
    .apiKey("...")
    .modelName("qwen-plus")
    .temperature(0.7)
    .build();

// 2. 简单调用
String response = model.chat("你好");

// 3. AI Services（接口驱动）
interface Assistant {
    @SystemMessage("你是友好的助手")
    String chat(String userMessage);
}
Assistant assistant = AiServices.create(Assistant.class, model);

// 4. AI Services + 模板
interface RoleAssistant {
    @SystemMessage("你是{{role}}")
    String chat(@UserMessage String msg, @V("role") String role);
}

// 5. AI Services + 记忆
Assistant assistant = AiServices.builder(Assistant.class)
    .chatModel(model)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
    .build();

// 6. 工具定义
class WeatherTools {
    @Tool("查询天气")
    String getWeather(@P("城市") String city) { return "..."; }
}
Assistant assistant = AiServices.builder(Assistant.class)
    .chatModel(model)
    .tools(new WeatherTools())
    .build();

// 7. 结构化输出
record Movie(String title, int year) {}
interface MovieRecommender {
    Movie recommend(@UserMessage String genre);
}
MovieRecommender recommender = AiServices.create(MovieRecommender.class, model);
```

## 📝 学习建议

1. **不要一次性全跑完** — 每个示例跑完后停下来思考：和 Spring AI 比有什么不同？
2. **写对比笔记** — 每个示例末尾都有 API 对比，认真看
3. **理解设计理念** — LangChain4j 是"接口驱动"（像 MyBatis），Spring AI 是"链式调用"（像 WebClient）
4. **动手改代码** — 试着修改示例中的 prompt、工具、模型，看看效果
5. **遇到编译错误先查版本** — LangChain4j 版本更新快，API 变化大，确保用 1.11.7

## ⚠️ 常见坑

1. **版本问题** — LangChain4j 版本要和 pom.xml 一致（当前 1.11.7）
2. **流式模型** — 同步用 `OpenAiChatModel`，流式用 `OpenAiStreamingChatModel`
3. **API Key** — 百炼 API 需要 OpenAI 兼容模式，URL 是 `https://dashscope.aliyuncs.com/compatible-mode/v1`
4. **IDEA 缓存** — 改完 pom.xml 后一定要 Maven → Reload Project
5. **Lombok** — 确保 IDEA 安装了 Lombok 插件，否则 record 和 @Slf4j 会报错
6. **WebFlux 冲突** — 本项目同时引入了 spring-boot-starter-web 和 spring-boot-starter-webflux，
   如果启动报 "Multiple DispatcherServlet" 相关错误，请在 application.yml 中添加:
   ```yaml
   spring:
     main:
       web-application-type: servlet
   ```

---

_创建时间: 2026-05-13_
