package com.example.lc4j.controller;

import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import com.example.lc4j.service.AiServiceLayer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import com.example.lc4j.client.LangChain4jConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private LangChain4jConfig modelConfig;

    @Autowired
    private AiServiceLayer aiServiceLayer;

    // 简单会话管理
    private final Map<String, ChatWithMemory> sessions = new ConcurrentHashMap<>();

    // ==================== 1. 简单对话（无记忆） ====================

    /**
     * 简单对话 - 每次都是独立的，不带历史上下文
     */
    @GetMapping("/simple")
    public Map<String, String> simpleChat(@RequestParam String message) {
        String response = modelConfig.getActiveChatModel().chat(message);
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

        modelConfig.getStreamingChatModel(modelConfig.getActiveModel()).chat(chatRequest, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
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
        // TokenWindowChatMemory(4096)：按 token 总数控制上下文窗口，更精确控制消耗
        // 使用 OpenAiTokenCountEstimator，传入 gpt-4o 作为 tokenizer 模型名（O200K_BASE 编码通用）
        TokenCountEstimator tokenEstimator = new OpenAiTokenCountEstimator("gpt-4o");

        ChatWithMemory assistant = sessions.computeIfAbsent(sessionId, id ->
                AiServices.builder(ChatWithMemory.class)
                        .chatModel(modelConfig.getActiveChatModel())
                        .chatMemory(TokenWindowChatMemory.builder()
                                .id(sessionId)
                                .maxTokens(4096, tokenEstimator)
                                .build())
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

        RoleAssistant assistant = AiServices.create(RoleAssistant.class, modelConfig.getActiveChatModel());
        String response = assistant.chat(message, role, style);

        return Map.of(
                "role", role,
                "style", style,
                "message", message,
                "response", response
        );
    }

    // ==================== 5. 模型管理 ====================

    /**
     * 获取可用模型列表
     * GET /api/chat/models
     */
    @GetMapping("/models")
    public Map<String, Object> listModels() {
        Map<String, LangChain4jConfig.ModelConfig> models = modelConfig.getModels();
        Set<Map.Entry<String, LangChain4jConfig.ModelConfig>> entries = models.entrySet();
        List<Map<String, String>> modelList = entries.stream().map(e -> {
            LangChain4jConfig.ModelConfig c = e.getValue();
            return Map.of(
                    "id", e.getKey(),
                    "provider", c.getProvider() != null ? c.getProvider() : "unknown",
                    "modelName", c.getModelName() != null ? c.getModelName() : "",
                    "description", c.getDescription() != null ? c.getDescription() : ""
            );
        }).collect(Collectors.toList());
        return Map.of(
                "activeModel", modelConfig.getActiveModel(),
                "models", modelList
        );
    }

    /**
     * 切换当前激活模型
     * POST /api/chat/model
     * Body: {"modelId": "volc-doubao-pro"}
     */
    @PostMapping("/model")
    public Map<String, Object> switchModel(@RequestBody Map<String, String> request) {
        String modelId = request.get("modelId");
        LangChain4jConfig.ModelConfig config = modelConfig.getModels().get(modelId);
        if (config == null) {
            return Map.of("error", "未知模型 ID: " + modelId,
                    "availableModels", modelConfig.getModels().keySet());
        }
        modelConfig.setActiveModel(modelId);
        modelConfig.evictAllChatModels();
        // 清除会话缓存，避免旧会话绑定旧 ChatModel
        sessions.clear();
        aiServiceLayer.clearAllSessions();
        return Map.of(
                "message", "模型已切换",
                "activeModel", modelId,
                "modelName", config.getModelName(),
                "provider", config.getProvider()
        );
    }

    /**
     * 查看当前模型状态
     * GET /api/chat/model
     */
    @GetMapping("/model")
    public Map<String, Object> modelStatus() {
        LangChain4jConfig.ModelConfig config = modelConfig.getModels().get(modelConfig.getActiveModel());
        return Map.of(
                "activeModel", modelConfig.getActiveModel(),
                "provider", config != null ? config.getProvider() : "unknown",
                "modelName", config != null ? config.getModelName() : "unknown"
        );
    }
}
