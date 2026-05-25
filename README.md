# LangChain4j Learning

LangChain4j 学习笔记项目 | 基于 1.11.7 | Spring Boot 3.2.4

## 📦 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 17 |
| **框架** | Spring Boot 3.2.4 |
| **AI 框架** | LangChain4j 1.11.7 |
| **AI 服务** | 火山引擎方舟（Coding Plan Lite，OpenAI 兼容 `/chat/completions` 端点） |
| **数据库** | MySQL + MyBatis-Plus 3.5.7（会话持久化） |
| **工具** | Lombok、Spring WebFlux（SSE 流式） |
| **前端** | langchain4j-learning-web（Vue 3 + Vite） |

## 🚀 快速开始

### 1. 设置 API Key

```powershell
$env:VOLC_API_KEY="your-volc-api-key"
```

### 2. 配置数据库（可选）

```bash
# 执行建表脚本
mysql -u root -p < database/langchain4j_learning.sql
```

> 默认数据库密码：`root/root`（用户/密码），可在 `application.yml` 中修改

### 3. 启动后端

IDEA 中右键 `pom.xml` → **Maven → Reload Project**，然后运行 `LangChain4jLearningApplication`。

后端端口：**8081**

### 4. 启动前端

```bash
cd D:\projectMenu\langchain4j-learning-web
pnpm i
pnpm run dev
```

前端端口：**3001**，自动代理到后端 8081。

## 🤖 模型管理

### 当前配置

| 模型 ID | 提供商 | 端点/模型 | 说明 |
|---------|--------|-----------|------|
| `volc-lite-endpoint` | 火山引擎 | `ep-20260525141015-h6skp` | Coding Plan Lite 推理接入点 |

### 切换模型 API

```bash
# 查看可用模型
GET http://localhost:8081/api/chat/models

# 切换模型
POST http://localhost:8081/api/chat/model
Body: {"modelId": "volc-lite-endpoint"}

# 查看当前模型
GET http://localhost:8081/api/chat/model
```

### 如何添加新模型

在 `application.yml` 的 `langchain4j.models` 下追加：

```yaml
langchain4j:
  models:
    volc-new-endpoint:
      provider: volcengine
      baseUrl: https://ark.cn-beijing.volces.com/api/v3
      apiKey: ${VOLC_API_KEY}
      modelName: 你的推理接入点ID（ep-***）
      temperature: 0.7
      max-tokens: 2048
      description: "新端点说明"
```

## 💬 Web API

### 对话接口

| 方法 | 端点 | 说明 |
|------|------|------|
| `GET` | `/api/chat/simple?message=你好` | 简单对话（无记忆） |
| `GET` | `/api/chat/stream?message=写首诗` | SSE 流式输出 |
| `POST` | `/api/chat/session` | 带记忆的会话对话 |
| `DELETE` | `/api/chat/session/{sessionId}` | 清除会话记忆 |
| `GET` | `/api/chat/role?message=xxx&role=Java老师` | 角色扮演（Prompt 模板） |

### Service 层 API

| 方法 | 端点 | 说明 |
|------|------|------|
| `POST` | `/api/service/ask` | 通用问答 |
| `POST` | `/api/service/translate` | AI 翻译 |
| `POST` | `/api/service/analyze` | 代码分析 |
| `POST` | `/api/service/session` | 带记忆的会话 |

## 🎯 Token 优化

本项目已做以下优化：

| 优化项 | 说明 |
|--------|------|
| **上下文窗口** | `TokenWindowChatMemory.withMaxTokens(4096)` 按 token 总数控制上下文 |
| **最大回复长度** | `maxTokens: 1024` 限制单次回复不超过 1024 tokens |
| **超时控制** | 300s 超时 + 2 次重试 |

## 📁 项目结构

```
langchain4j-learning/
├── pom.xml
├── README.md
├── LEARNING_GUIDE.md
├── database/
│   └── langchain4j_learning.sql          # 数据库建表脚本
└── src/main/
    ├── java/com/example/lc4j/
    │   ├── LangChain4jLearningApplication.java
    │   ├── config/
    │   │   ├── LangChain4jConfig.java          # 多模型动态配置
    │   │   └── MybatisPlusConfig.java          # MyBatis-Plus 配置
    │   ├── controller/
    │   │   ├── ChatController.java             # 对话 API（含模型管理）
    │   │   └── ServiceController.java          # Service 层 API
    │   ├── service/
    │   │   ├── AiServiceLayer.java             # AI 服务封装层
    │   │   └── ChatService.java                # 会话/消息持久化服务
    │   ├── entity/
    │   │   ├── ChatSession.java                # 会话实体
    │   │   └── ChatMessage.java                # 消息实体
    │   ├── mapper/
    │   │   ├── ChatSessionMapper.java          # 会话 Mapper
    │   │   └── ChatMessageMapper.java          # 消息 Mapper
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
        ├── application.yml
        └── mapper/
            └── ChatSessionMapper.xml

langchain4j-learning-web/                    # 配套 Web 前端
├── src/
│   ├── api/chat.js                          # API 请求封装
│   ├── stores/chat.js                       # Pinia 状态管理
│   ├── views/
│   │   ├── ChatView.vue                     # AI 对话页
│   │   ├── ExamplesView.vue                 # 学习示例页
│   │   └── ModelView.vue                    # 模型管理页
│   ├── App.vue
│   ├── router/index.js
│   └── main.js
└── package.json
```

## 📊 LangChain4j vs Spring AI 速查

| 功能 | LangChain4j | Spring AI |
|------|-------------|-----------|
| **创建模型** | `OpenAiChatModel.builder()...build()` | 依赖注入 `@Autowired ChatModel` |
| **简单调用** | `model.chat("你好")` → String | `chatModel.call(new Prompt(...))` → ChatResponse |
| **高级 API** | `AiServices.create(Interface.class, model)` | `ChatClient.builder(chatModel).build()` |
| **模板** | `@SystemMessage("{{role}}")` + `@V("role")` | 手动拼接或 PromptTemplate |
| **记忆** | `TokenWindowChatMemory.withMaxTokens(4096)` | 手动管理 `List<Message>` |
| **工具** | `@Tool("描述")` 注解 | 实现 `ToolCallback` 接口 |
| **流式** | SSE `Flux<String>` 回调模式 | `Flux<String>` + `.subscribe()` |
| **结构化输出** | 直接返回 POJO | `.entity(Class)` |

## 🔗 学习资源

- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
- [LangChain4j GitHub](https://github.com/langchain4j/langchain4j)
- [LangChain4j 示例](https://github.com/langchain4j/langchain4j-examples)
- [火山引擎方舟文档](https://www.volcengine.com/docs/82379)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)

---

_版本: 1.11.7 | 更新: 2026-05-25_
