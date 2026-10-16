package com.example.lc4j.example;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.example.lc4j.config.VolcMultiModalEmbeddingModel;

/**
 * Day 8: RAG 生产实战 - PostgreSQL pgvector 向量数据库
 * 
 * 学习要点：
 * 1. 使用 pgvector 作为持久化向量存储（生产级方案）
 * 2. 文档加载 → 分割 → 向量化 → 存储 → 检索 → 生成
 * 3. EmbeddingModel 将文本转为向量，pgvector 存储和检索
 * 4. ContentRetriever 实现检索增强生成
 * 
 * 前置条件：
 * - PostgreSQL 17 已安装并运行（D:\softawre-kaifa\PostgreSQL）
 * - pgvector 扩展已启用（CREATE EXTENSION vector）
 * - 数据库 langchain4j_rag 已创建
 * 
 * 运行方式：设置 langchain4j.day8.rag-prod=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day8.rag-prod", havingValue = "true", matchIfMissing = false)
public class Day8RagProdExample implements CommandLineRunner {

    @Value("${langchain4j.models.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.models.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.models.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${langchain4j.embedding.model:text-embedding-v3}")
    private String embeddingModelName;

    @Value("${langchain4j.rag.docs-dir:}")
    private String docsDir;

    @Override
    public void run(String... args) throws Exception {
        log.info("========== Day 8: RAG 生产实战 - PostgreSQL pgvector ==========");

        // ==================== 1. 初始化模型 ====================
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();

        // Embedding 使用独立配置（多模态端点）
        String embBaseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        String embApiKey = System.getenv("VOLC_API_KEY");
        if (embApiKey == null || embApiKey.isEmpty()) {
            embApiKey = "REDACTED-VOLC-KEY";
        }
        
        EmbeddingModel embeddingModel = new VolcMultiModalEmbeddingModel(
                embBaseUrl, embApiKey,
                "ep-20260609173940-d5d4l", 2048);
        log.info("Embedding base URL: {}", embBaseUrl);

        log.info("Chat 模型: {}", modelName);
        log.info("Embedding 模型: {}", embeddingModelName);

        // ==================== 2. 初始化 pgvector 存储 ====================
        PgVectorEmbeddingStore embeddingStore = PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("langchain4j_rag")
                .user("postgres")
                .password("postgres")
                .table("knowledge_base")
                .dimension(2048)  // 火山 DoubEmbedding 默认 2048 维，实际以模型为准
                .createTable(true)  // 自动创建表
                .build();

        log.info("✅ pgvector 向量存储初始化成功 (localhost:5432/langchain4j_rag/knowledge_base)");

        // ==================== 3. 加载文档 ====================
        List<TextSegment> segments = loadDocuments();
        log.info("📄 加载了 {} 个文档片段", segments.size());

        // ==================== 4. 向量化并存储 ====================
        log.info("🔄 开始向量化并存储到 pgvector...");
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
            log.debug("  [{}/{}] 向量化完成: {}", i + 1, segments.size(),
                    segment.text().substring(0, Math.min(50, segment.text().length())));
        }
        log.info("✅ 全部 {} 个文档片段已向量化并存储到 pgvector", segments.size());

        // ==================== 5. 测试向量检索 ====================
        log.info("\n========== 测试 1: 向量检索 ==========");
        String testQuery = "LangChain4j 的 AI Services 是什么？";
        Embedding queryEmbedding = embeddingModel.embed(testQuery).content();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(3)
                        .build()
        );

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        log.info("查询: {}", testQuery);
        log.info("检索到 {} 个相关文档片段:", matches.size());
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            log.info("  [{}] score: {} | 内容: {}...",
                    i + 1, String.format("%.4f", match.score()),
                    match.embedded().text().substring(0, Math.min(100, match.embedded().text().length())));
        }

        // ==================== 6. 构建 RAG 问答系统 ====================
        log.info("\n========== 测试 2: RAG 问答系统 ==========");

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .build();

        interface RagAssistant {
            @SystemMessage("""
                    你是一位 AI 技术专家。请基于参考资料回答用户问题。
                    回答要求：
                    - 基于参考资料回答，不要编造信息
                    - 如果资料中没有相关信息，请明确说明
                    - 回答要简洁、专业、有条理
                    """)
            String ask(@UserMessage String question);
        }

        RagAssistant assistant = AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .contentRetriever(contentRetriever)
                .build();

        // 测试问题 1
        String q1 = "LangChain4j 的 AiServices 有什么核心功能？";
        log.info("👤 问题: {}", q1);
        String a1 = assistant.ask(q1);
        log.info("🤖 回答:\n{}", a1);

        // 测试问题 2
        String q2 = "Tool Calling 是怎么工作的？";
        log.info("\n👤 问题: {}", q2);
        String a2 = assistant.ask(q2);
        log.info("🤖 回答:\n{}", a2);

        // 测试问题 3（超出知识库范围）
        String q3 = "Python 的 Django 框架怎么用？";
        log.info("\n👤 问题: {}", q3);
        String a3 = assistant.ask(q3);
        log.info("🤖 回答:\n{}", a3);

        // ==================== 7. 对比测试 ====================
        log.info("\n========== 测试 3: RAG vs 非 RAG 对比 ==========");

        String compareQuestion = "LangChain4j 的 Chat Memory 有哪几种实现？各有什么特点？";

        // 不用 RAG - 增加超时时间
        ChatModel simpleChatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();

        interface SimpleQA {
            @SystemMessage("你是一位 AI 技术专家。")
            String ask(@UserMessage String question);
        }
        SimpleQA simpleQA = AiServices.create(SimpleQA.class, simpleChatModel);

        log.info("问题: {}", compareQuestion);
        log.info("\n--- 不用 RAG ---");
        String withoutRag = simpleQA.ask(compareQuestion);
        log.info("回答:\n{}", withoutRag);

        log.info("\n--- 用 RAG（pgvector 检索）---");
        String withRag = assistant.ask(compareQuestion);
        log.info("回答:\n{}", withRag);

        // ==================== 总结 ====================
        log.info("\n🎓 ========== Day 8 总结 ==========");
        log.info("""
                ✅ 生产级 RAG 流程已跑通:
                  1. Document → 文档加载（文本/HTML/PDF/Word）
                  2. DocumentSplitter → 文档分割（按 Token 数/段落）
                  3. EmbeddingModel → 文本向量化（百炼 text-embedding-v3）
                  4. PgVectorEmbeddingStore → 持久化向量存储（PostgreSQL + pgvector）
                  5. ContentRetriever → 语义检索（向量相似度搜索）
                  6. AiServices + @SystemMessage → RAG 问答生成

                ✅ pgvector 向量数据库:
                  - PostgreSQL 17 + pgvector 0.8.2
                  - 自动建表，支持增量更新
                  - 适合生产环境（支持索引、事务、备份）
                  - 比 Milvus/Chroma 更轻量，一个数据库搞定

                ✅ 关键配置:
                  - 向量维度: 1024（text-embedding-v3 默认）
                  - 检索参数: maxResults=3, minScore=0.6
                  - 数据库: localhost:5432/langchain4j_rag/knowledge_base
                """);

        log.info("🎉 ========== Day 8: RAG 生产实战完成 ==========");
    }

    /**
     * 加载文档并分割为 TextSegment
     */
    private List<TextSegment> loadDocuments() throws Exception {
        // 如果配置了文档目录，从文件加载
        if (docsDir != null && !docsDir.isEmpty()) {
            Path dirPath = Paths.get(docsDir);
            if (Files.isDirectory(dirPath)) {
                log.info("📂 从目录加载文档: {}", docsDir);
                List<Document> docs = new ArrayList<>();
                try (var stream = Files.list(dirPath)) {
                    stream.filter(p -> p.toString().endsWith(".txt"))
                            .forEach(p -> docs.add(loadTextFile(p)));
                }
                DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
                return splitter.splitAll(docs);
            }
        }

        // 否则使用内置知识库文档
        log.info("📂 使用内置知识库文档");
        List<String> knowledgeBase = List.of(
                "LangChain4j 是一个 Java 语言的 AI 应用开发框架，灵感来源于 Python 的 LangChain。"
                        + "它提供了统一的 AI 模型调用接口、AI Services、Tool Calling、Chat Memory、RAG 等功能。"
                        + "LangChain4j 支持多种 AI 模型提供商，包括 OpenAI、Azure OpenAI、Ollama、本地部署的模型等。"
                        + "LangChain4j 还支持 Quarkus、Helidon、Micronaut 等框架。",

                "AiServices 是 LangChain4j 最重要的高级 API，采用接口驱动模式。"
                        + "开发者只需定义 Java 接口，LangChain4j 会自动生成实现类，无需手动编写调用逻辑。"
                        + "核心注解包括：@SystemMessage（系统提示词）、@UserMessage（用户消息）、@V（模板变量）、"
                        + "@MemoryId（会话标识）、@Tool（工具调用）。"
                        + "返回类型支持：String、TokenStream（流式）、POJO（自动 JSON 解析）、List<T> 等。"
                        + "AiServices 还支持 chatMemoryProvider 实现多轮对话记忆。",

                "LangChain4j 的 Chat Memory 用于管理多轮对话的上下文历史。"
                        + "主要实现类有三种："
                        + "1. MessageWindowChatMemory - 按消息数量限制上下文窗口，最简单的实现"
                        + "2. TokenWindowChatMemory - 按 Token 数量限制上下文窗口，更精确控制消耗"
                        + "3. MessageBufferChatMemory - 基于消息缓冲区的实现"
                        + "支持多用户会话隔离，通过 chatMemoryProvider 为每个用户创建独立的 Memory 实例。"
                        + "使用 @MemoryId 注解可以在 AI Service 接口中指定会话标识。",

                "Tool Calling（工具调用）允许 AI 模型在对话过程中调用外部工具/函数。"
                        + "LangChain4j 使用 @Tool 注解标记工具方法，@P 注解描述参数含义。"
                        + "AI 模型会根据用户输入自动判断是否需要调用工具，以及调用哪个工具。"
                        + "支持多工具注册和编排，通过 .tools() 方法链式添加多个工具类。"
                        + "工具方法可以是任何 Java 方法，支持各种参数类型和返回类型。",

                "RAG（Retrieval-Augmented Generation）是检索增强生成技术。"
                        + "核心流程：用户提问 → 向量化检索相关文档 → 将检索结果注入 Prompt → AI 生成回答。"
                        + "LangChain4j RAG 组件包括："
                        + "- DocumentLoader：加载文档（PDF/TXT/HTML/Word）"
                        + "- DocumentSplitter：分割文档为小块（按 Token 数或段落）"
                        + "- EmbeddingModel：将文本转为向量"
                        + "- EmbeddingStore：存储向量（支持 pgvector、Milvus、Chroma、Pinecone 等）"
                        + "- ContentRetriever：语义检索，返回最相关的文档片段"
                        + "- AiServices：将检索结果注入 Prompt 并生成回答",

                "LangChain4j 的 EmbeddingModel 支持多种提供商："
                        + "- OpenAI（text-embedding-ada-002, text-embedding-3-small, text-embedding-3-large）"
                        + "- 百炼/DashScope（text-embedding-v3）"
                        + "- Ollama（本地部署的 embedding 模型）"
                        + "- HuggingFace（Sentence Transformers）"
                        + "向量维度因模型而异：OpenAI ada-002 是 1536 维，text-embedding-v3 支持 1024/512/256 维。"
                        + "选择 EmbeddingStore 时需要设置正确的 dimension 参数。",

                "Spring AI 与 LangChain4j 的核心差异："
                        + "- API 风格：Spring AI 用 ChatClient 链式调用，LangChain4j 用 AiServices 接口驱动"
                        + "- 配置方式：Spring AI 用 @Autowired 自动装配，LangChain4j 手动配置 Bean"
                        + "- 对话记忆：Spring AI 手动管理 List<Message>，LangChain4j 用 ChatMemory 自动管理"
                        + "- 工具调用：Spring AI 用 ToolCallback 接口，LangChain4j 用 @Tool 注解更直观"
                        + "- 流式输出：Spring AI 用 Flux<String>，LangChain4j 用 TokenStream 或回调模式"
                        + "- 结构化输出：Spring AI 用 .entity(Class)，LangChain4j 直接返回 POJO 类型"
        );

        List<Document> documents = new ArrayList<>();
        for (String text : knowledgeBase) {
            documents.add(Document.from(text));
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.splitAll(documents);
        log.info("📄 内置知识库: {} 条文档 → 分割为 {} 个片段", knowledgeBase.size(), segments.size());
        return segments;
    }

    /**
     * 加载单个文本文件
     */
    private Document loadTextFile(Path path) {
        try {
            String content = Files.readString(path);
            return Document.from(content);
        } catch (Exception e) {
            log.warn("加载文档失败: {}", path, e);
            return Document.from("");
        }
    }
}
