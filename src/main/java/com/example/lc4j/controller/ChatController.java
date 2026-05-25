package com.example.lc4j.controller;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web API 控制器 - 演示 LangChain4j 在 Web 项目中的实际应用
 *
 * 学习要点：
 * 1. 如何将 LangChain4j 集成到 Spring MVC 控制器
 * 2. 如何使用 @Autowired 注入 ChatModel Bean
 * 3. 如何实现 SSE 流式输出（Server-Sent Events）
 * 4. 如何实现多轮对话（带会话管理）
 * 5. 如何使用 AI Services 接口驱动模式
 *
 * 测试方式：
 * - GET  http://localhost:8081/api/chat/simple?message=你好
 * - GET  http://localhost:8081/api/chat/stream?message=写一首诗
 * - POST http://localhost:8081/api/chat/session -H "Content-Type: application/json" -d '{"sessionId":"user1","message":"你好"}'
 * - GET  http://localhost:8081/api/chat/role?message=什么是多态&role=Java老师&style=通俗易懂
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    // 简单会话管理（内存版，生产环境应用 Redis 等）
    private final Map<String, dev.langchain4j.service.Assistant> sessions = new ConcurrentHashMap<>();

    // ==================== 1. 简单对话（无记忆） ====================

    /**
     * 简单对话 - 每次都是独立的，不带历史上下文
     */
    @GetMapping("/simple")
    public Map<String, String> simpleChat(@RequestParam String message) {
        String response = chatModel.chat(message);
        return Map.of("message", message, "response", response);
    }

    // ==================== 2. 流式输出（SSE） ====================

    /**
     * 流式对话 - 使用 Server-Sent Events 实现打字机效果
     * 浏览器端：new EventSource('/api/chat/stream?message=xxx')
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam String message) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        dev.langchain4j.model.chat.request.ChatRequest chatRequest =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(dev.langchain4j.data.message.UserMessage.from(message))
                        .build();

        streamingChatModel.chat(chatRequest, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }

    // ==================== 3. 带记忆的会话对话 ====================

    interface ChatWithMemory {
        @SystemMessage("你是一位友好的 AI 助手。记住用户的信息和偏好，提供个性化的回复。")
        String chat(@UserMessage String userMessage);
    }

    /**
     * 多轮对话 - 带会话记忆
     * POST /api/chat/session
     * Body: {"sessionId": "user1", "message": "你好，我是张三"}
     */
    @PostMapping("/session")
    public Map<String, Object> sessionChat(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String message = request.get("message");

        // 获取或创建会话（每个用户独立的 ChatMemory）
        ChatWithMemory assistant = sessions.computeIfAbsent(sessionId, id ->
                AiServices.builder(ChatWithMemory.class)
                        .chatModel(chatModel)
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                        .build()
        );

        String response = assistant.chat(message);

        return Map.of(
                "sessionId", sessionId,
                "message", message,
                "response", response
        );
    }

    /**
     * 清除指定会话的记忆
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, String> clearSession(@PathVariable String sessionId) {
        sessions.remove(sessionId);
        return Map.of("message", "会话 " + sessionId + " 已清除");
    }

    // ==================== 4. 角色 Prompt 模板 ====================

    interface RoleAssistant {
        @SystemMessage("你是一位{{role}}，用{{style}}风格回答问题。回答要专业且易懂。")
        String chat(@UserMessage String userMessage,
                    @V("role") String role,
                    @V("style") String style);
    }

    /**
     * 角色扮演对话 - 使用 Prompt 模板
     * GET /api/chat/role?message=什么是多态&role=Java老师&style=通俗易懂
     */
    @GetMapping("/role")
    public Map<String, String> roleChat(
            @RequestParam String message,
            @RequestParam(defaultValue = "AI助手") String role,
            @RequestParam(defaultValue = "专业") String style) {

        RoleAssistant assistant = AiServices.create(RoleAssistant.class, chatModel);
        String response = assistant.chat(message, role, style);

        return Map.of(
                "role", role,
                "style", style,
                "message", message,
                "response", response
        );
    }
}
