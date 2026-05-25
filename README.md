# LangChain4j Learning

_LangChain4j 学习笔记项目 | 基于 1.11.7 | Spring Boot 3.2.4_

## 📦 技术栈

- Java 17
- Spring Boot 3.2.4
- LangChain4j 1.11.7
- 百炼 API（OpenAI 兼容模式）
- Spring WebFlux（SSE 流式输出）
- Lombok

## 🚀 快速开始

### 1. 设置 API Key

```bash
# Windows PowerShell
$env:DASHSCOPE_API_KEY="sk-your-key"

# 或在 application.yml 中直接填写
```

### 2. 刷新依赖

在 IDEA 中右键 `pom.xml` → **Maven → Reload Project**（等待下载完成）。

### 3. 运行示例

#### 方式 A：CommandLineRunner 示例

通过 `application.yml` 中的开关控制：

```yaml
langchain4j:
  day1:
    enabled: true    # 开启 Day1SyncExample
    streaming: false
```

#### 方式 B：启动 Web 服务

直接运行 `LangChain4jLearningApplication`，访问 Web API：

```bash
# 简单对话
GET http://localhost:8081/api/chat/simple?message=你好

# 流式输出（SSE）
GET http://localhost:8081/api/chat/stream?message=写一首诗

# 带记忆的会话对话
POST http://localhost:8081/api/chat/session
Body: {"sessionId": "user1", "message": "你好，我是张三"}

# 角色扮演（Prompt 模板）
GET http://localhost:8081/api/chat/role?message=什么是多态&role=Java老师&style=通俗易懂

# Service 层封装 API
POST http://localhost:8081/api/service/ask
Body: {"question": "什么是微服务"}
```

## 📚 学习计划

### Week 3: LangChain4j 核心

#### Day 1: 同步 + 流式调用 ✅

| 文件 | 内容 |
|------|------|
| `Day1SyncExample.java` | ChatModel 同步调用，model.chat(String) 返回 String |
| `Day1StreamingExample.java` | StreamingChatModel + StreamingChatResponseHandler 回调 |

#### Day 2: Prompt Template + Chat Memory ✅

| 文件 | 内容 |
|------|------|
| `Day2PromptTemplateExample.java` | @SystemMessage/@UserMessage/@V 注解模板（Mustache 语法） |
| `Day2ChatMemoryExample.java` | MessageWindowChatMemory + 多用户会话隔离 |

#### Day 3: AI Services ✅

| 文件 | 内容 |
|------|------|
| `Day3AiServicesExample.java` | AiServices.create() + TokenStream 流式 + POJO 自动解析 |

#### Day 4: Tool Calling ✅

| 文件 | 内容 |
|------|------|
| `Day4ToolCallingExample.java` | @Tool/@P 注解 + 多工具编排 |

#### Day 5: Output Parsing + 综合 ✅

| 文件 | 内容 |
|------|------|
| `Day5OutputParsingExample.java` | POJO / List\<T\> / 嵌套对象自动解析 |
| `Day5ComprehensiveExample.java` | 完整智能助手（Services + Memory + Tools + Parsing） |

#### Day 6: RAG 入门 ✅

| 文件 | 内容 |
|------|------|
| `Day6RagExample.java` | RAG 概念 + 简化版文档检索 + 对比无 RAG 回答 |

#### Day 7: 综合实战 ✅

| 文件 | 内容 |
|------|------|
| `Day7ComprehensiveExample.java` | 电商智能客服系统（订单/物流/用户工具 + 结构化输出） |

### Web 集成层 ✅

| 文件 | 内容 |
|------|------|
| `config/LangChain4jConfig.java` | Spring Bean 配置（@Bean ChatModel / StreamingChatModel） |
| `controller/ChatController.java` | Web API 控制器（简单/流式/会话/角色扮演） |
| `controller/ServiceController.java` | Service 层封装 API（问答/翻译/代码分析/会话） |
| `service/AiServiceLayer.java` | Service 层封装（通用/代码/翻译/会话服务） |

## 📁 项目结构

```
langchain4j-learning/
├── pom.xml
├── README.md
├── LEARNING_GUIDE.md
└── src/main/
    ├── java/com/example/lc4j/
    │   ├── LangChain4jLearningApplication.java
    │   ├── config/
    │   │   └── LangChain4jConfig.java          # Spring Bean 配置
    │   ├── controller/
    │   │   ├── ChatController.java             # Web API（简单/流式/会话/角色）
    │   │   └── ServiceController.java          # Service 层 API
    │   ├── service/
    │   │   └── AiServiceLayer.java             # AI 服务封装层
    │   └── example/
    │       ├── Day1SyncExample.java            # Day 1: 同步调用
    │       ├── Day1StreamingExample.java       # Day 1: 流式调用
    │       ├── Day2PromptTemplateExample.java  # Day 2: Prompt 模板
    │       ├── Day2ChatMemoryExample.java      # Day 2: Chat Memory
    │       ├── Day3AiServicesExample.java      # Day 3: AI Services
    │       ├── Day4ToolCallingExample.java     # Day 4: Tool Calling
    │       ├── Day5OutputParsingExample.java   # Day 5: 输出解析
    │       ├── Day5ComprehensiveExample.java   # Day 5: 综合练习
    │       ├── Day6RagExample.java             # Day 6: RAG 入门
    │       └── Day7ComprehensiveExample.java   # Day 7: 电商客服实战
    └── resources/
        └── application.yml
```

## 🔗 学习资源

- [官方文档](https://docs.langchain4j.dev/)
- [GitHub](https://github.com/langchain4j/langchain4j)
- [示例项目](https://github.com/langchain4j/langchain4j-examples)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)

## 📊 LangChain4j vs Spring AI 速查

| 功能 | LangChain4j | Spring AI |
|------|-------------|-----------|
| **创建模型** | `OpenAiChatModel.builder()...build()` | 依赖注入 `@Autowired ChatModel` |
| **简单调用** | `model.chat("你好")` → String | `chatModel.call(new Prompt(...))` → ChatResponse |
| **高级 API** | `AiServices.create(Interface.class, model)` | `ChatClient.builder(chatModel).build()` |
| **模板** | `@SystemMessage("{{role}}")` + `@V("role")` | 手动拼接或 PromptTemplate |
| **记忆** | `.chatMemory(MessageWindowChatMemory...)` | 手动管理 `List<Message>` |
| **工具** | `@Tool("描述")` 注解 | 实现 `ToolCallback` 接口 |
| **流式** | `TokenStream` + `.onNext()` / 回调模式 | `Flux<String>` + `.subscribe()` |
| **结构化输出** | 直接返回 POJO | `.entity(Class)` |

---

_版本: 1.11.7 | 更新: 2026-05-20_
