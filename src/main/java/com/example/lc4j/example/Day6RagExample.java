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

import java.util.List;

/**
 * Day 6: RAG（检索增强生成）入门示例
 * 
 * 学习要点：
 * 1. 什么是 RAG（Retrieval-Augmented Generation）
 * 2. 如何使用 LangChain4j 的 EmbeddingStoreIngestor 构建知识库
 * 3. 如何使用 ChatMemoryStore 实现基于检索的对话
 * 4. 对比：不用 RAG vs 用 RAG 的回答差异
 * 
 * 运行方式：设置 langchain4j.day6.rag=true
 * 
 * 注意：此示例使用 InMemoryEmbeddingStore（内存版），适合学习和原型开发。
 * 生产环境应使用 Milvus、Pinecone、Weaviate 等持久化向量数据库。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day6.rag", havingValue = "true", matchIfMissing = false)
public class Day6RagExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) {
        log.info("========== Day 6: RAG（检索增强生成）入门 ==========");

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .build();

        // ==================== 知识库文档 ====================
        List<String> documents = List.of(
                "LangChain4j 是一个 Java 语言的 AI 应用开发框架，灵感来源于 Python 的 LangChain。" +
                        "它提供了统一的 AI 模型调用接口、AI Services、Tool Calling、Chat Memory、RAG 等功能。" +
                        "LangChain4j 支持多种 AI 模型提供商，包括 OpenAI、Azure OpenAI、Ollama、本地部署的模型等。",

                "LangChain4j 的核心组件包括：ChatModel（聊天模型）、AiServices（AI 服务接口）、" +
                        "ChatMemory（对话记忆）、Tool Calling（工具调用）、Embedding Model（嵌入模型）、" +
                        "Embedding Store（向量存储）、Document Loader（文档加载器）、Document Splitter（文档分割器）。",

                "AiServices 是 LangChain4j 最重要的高级 API，采用接口驱动模式。" +
                        "开发者只需定义 Java 接口，LangChain4j 会自动生成实现类。" +
                        "支持注解：@SystemMessage（系统提示词）、@UserMessage（用户消息）、@V（模板变量）、" +
                        "@MemoryId（会话标识）、@Tool（工具调用）。返回类型支持 String、TokenStream、POJO、List<T> 等。",

                "LangChain4j 的 Chat Memory 用于管理多轮对话的上下文。" +
                        "主要实现类有：MessageWindowChatMemory（消息窗口，按消息数量限制）、" +
                        "TokenWindowChatMemory（Token 窗口，按 Token 数量限制）。" +
                        "支持多用户会话隔离，通过 chatMemoryProvider 为每个用户创建独立的 Memory。",

                "Tool Calling（工具调用）允许 AI 模型在对话过程中调用外部工具。" +
                        "LangChain4j 使用 @Tool 注解标记工具方法，@P 注解描述参数。" +
                        "AI 模型会根据用户输入自动判断是否需要调用工具，以及调用哪个工具。" +
                        "支持多工具注册和编排，通过 .tools() 方法链式添加。"
        );

        log.info("知识库文档数量: {}", documents.size());

        // ==================== 不使用 RAG 的回答 ====================
        log.info("\n--- 不使用 RAG 的回答 ---");
        interface SimpleQA {
            @SystemMessage("你是一位 AI 知识专家。")
            String ask(@UserMessage String question);
        }
        SimpleQA simpleQA = AiServices.create(SimpleQA.class, model);
        String withoutRag = simpleQA.ask("LangChain4j 的 AiServices 是什么？有什么核心功能？");
        log.info("无 RAG 回答:\n{}", withoutRag);

        // ==================== 使用 RAG 的回答 ====================
        log.info("\n--- 使用 RAG 的回答 ---");

        // 注意：LangChain4j 的 RAG 需要 EmbeddingModel 支持。
        // 由于百炼 API 的 Embedding 模型需要单独配置，这里使用简化版 RAG 演示：
        // 1. 手动检索相关文档（关键词匹配模拟向量检索）
        // 2. 将检索到的文档注入 Prompt
        // 3. 调用 AI 生成回答

        String question = "LangChain4j 的 AiServices 是什么？有什么核心功能？";

        // 步骤 1: 模拟检索（实际项目中用 EmbeddingModel + EmbeddingStore）
        String retrievedContext = retrieveRelevantDocuments(question, documents);
        log.info("检索到的上下文:\n{}", retrievedContext);

        // 步骤 2: 构建 RAG Prompt
        interface RagAssistant {
            @SystemMessage("""
                    你是一位 AI 知识专家。请基于以下参考资料回答用户问题。
                    如果参考资料中没有相关信息，请说明"根据现有资料无法回答"。
                    
                    参考资料：
                    {{context}}
                    """)
            String ask(@UserMessage String question, @V("context") String context);
        }

        RagAssistant ragAssistant = AiServices.create(RagAssistant.class, model);

        String withRag = ragAssistant.ask(question, retrievedContext);
        log.info("有 RAG 回答:\n{}", withRag);

        // ==================== 对比总结 ====================
        log.info("\n========== RAG 核心概念总结 ==========");
        log.info("""
            什么是 RAG（检索增强生成）?
              1. 用户提供问题
              2. 系统从知识库中检索相关文档
              3. 将检索结果注入 Prompt
              4. AI 基于检索结果生成回答
              
            RAG 的优势:
              - 减少幻觉（Hallucination）
              - 回答基于真实文档
              - 可以追溯信息来源
              - 适用于企业知识库、客服、FAQ 等场景
            
            LangChain4j RAG 完整流程（生产环境）:
              1. DocumentLoader 加载文档（PDF/TXT/HTML/Word）
              2. DocumentSplitter 分割文档为小块
              3. EmbeddingModel 将文本转为向量
              4. EmbeddingStore 存储向量（Milvus/Chroma/Pinecone）
              5. 用户提问 → EmbeddingModel 转为向量 → EmbeddingStore 检索相似文档
              6. 检索结果注入 Prompt → ChatModel 生成回答
            
            本示例简化:
              - 使用内存文档列表代替 EmbeddingStore
              - 使用关键词匹配代替向量相似度搜索
              - 生产环境应使用 dev.langchain4j.rag 包下的完整 RAG 组件
            """);

        log.info("========== Day 6 RAG 入门完成 ==========");
    }

    /**
     * 简化版文档检索（关键词匹配模拟向量检索）
     * 实际项目中应使用 EmbeddingModel + EmbeddingStore 做向量相似度搜索
     */
    private String retrieveRelevantDocuments(String question, List<String> documents) {
        String[] keywords = question.toLowerCase().split("\\s+");
        StringBuilder context = new StringBuilder();
        int docIndex = 1;

        for (String doc : documents) {
            String docLower = doc.toLowerCase();
            // 简单关键词匹配（至少包含 2 个关键词）
            int matchCount = 0;
            for (String keyword : keywords) {
                if (keyword.length() > 2 && docLower.contains(keyword)) {
                    matchCount++;
                }
            }
            if (matchCount >= 2) {
                context.append("[").append(docIndex++).append("] ").append(doc).append("\n\n");
            }
        }

        return context.length() > 0 ? context.toString() : "暂无相关参考资料";
    }
}
