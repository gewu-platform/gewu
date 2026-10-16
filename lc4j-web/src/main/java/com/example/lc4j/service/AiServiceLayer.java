package com.example.lc4j.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import com.example.lc4j.client.LangChain4jConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 服务层示例
 * 
 * 学习要点：
 * 1. 如何封装 LangChain4j 调用到 Service 层
 * 2. 如何管理多个 AI Service 实例
 * 3. 如何在 Service 中使用 Chat Memory
 * 4. 动态获取当前激活的 ChatModel（支持切换模型）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceLayer {

    private final LangChain4jConfig modelConfig;

    // 定义 AI Service 接口
    interface GeneralAssistant {
        @SystemMessage("你是一位专业的 AI 助手，回答要简洁、准确、有帮助。")
        String chat(@UserMessage String userMessage);
    }

    interface CodeAssistant {
        @SystemMessage("你是一位资深 Java 开发工程师，擅长代码审查、架构设计和性能优化。")
        @UserMessage("""
            请帮我{{action}}：
            
            ```java
            {{code}}
            ```
            """)
        String analyze(@V("action") String action, @V("code") String code);
    }

    interface Translator {
        @SystemMessage("你是一位专业的翻译专家，擅长中英文互译。请提供准确的翻译，必要时补充文化背景说明。")
        @UserMessage("请将以下内容翻译成{{targetLang}}：\n\n{{text}}")
        String translate(@V("text") String text, @V("targetLang") String targetLang);
    }

    interface ChatWithMemory {
        @SystemMessage("你是一位友好的助手。记住上下文，提供连贯的对话体验。")
        String chat(@UserMessage String userMessage);
    }

    // 会话管理
    private final Map<String, ChatWithMemory> sessions = new ConcurrentHashMap<>();

    /**
     * 通用问答
     */
    public String ask(String question) {
        log.debug("General question: {}", question);
        return AiServices.create(GeneralAssistant.class, modelConfig.getActiveChatModel())
                .chat(question);
    }

    /**
     * 代码分析
     */
    public String analyzeCode(String action, String code) {
        log.debug("Code analysis: action={}", action);
        return AiServices.create(CodeAssistant.class, modelConfig.getActiveChatModel())
                .analyze(action, code);
    }

    /**
     * 翻译
     */
    public String translate(String text, String targetLang) {
        log.debug("Translation: target={}", targetLang);
        return AiServices.create(Translator.class, modelConfig.getActiveChatModel())
                .translate(text, targetLang);
    }

    /**
     * 带记忆的会话对话
     */
    public String chatWithSession(String sessionId, String message) {
        ChatWithMemory assistant = sessions.computeIfAbsent(sessionId, id ->
                AiServices.builder(ChatWithMemory.class)
                        .chatModel(modelConfig.getActiveChatModel())
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                        .build()
        );
        return assistant.chat(message);
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Session cleared: {}", sessionId);
    }

    /**
     * 清除所有会话缓存（模型切换时调用，避免旧会话绑定旧 ChatModel）
     */
    public void clearAllSessions() {
        sessions.clear();
        log.info("All sessions cleared (model switch)");
    }
}
