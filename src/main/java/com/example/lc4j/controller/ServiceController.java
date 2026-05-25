package com.example.lc4j.controller;

import com.example.lc4j.service.AiServiceLayer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Service 层 API 控制器 - 演示 LangChain4j 在 Service 层的应用
 * 
 * 学习要点：
 * 1. 如何通过 Service 层封装 LangChain4j 调用
 * 2. 如何在 Controller 中使用封装好的 AI 服务
 * 3. 对比直接注入 ChatModel vs 使用封装的 Service
 * 
 * 测试方式：
 * - POST http://localhost:8081/api/service/ask -H "Content-Type: application/json" -d '{"question":"什么是微服务"}'
 * - POST http://localhost:8081/api/service/translate -H "Content-Type: application/json" -d '{"text":"Hello World","targetLang":"中文"}'
 * - POST http://localhost:8081/api/service/analyze -H "Content-Type: application/json" -d '{"action":"审查代码","code":"public void test() {}"}'
 * - POST http://localhost:8081/api/service/session -H "Content-Type: application/json" -d '{"sessionId":"user1","message":"你好"}'
 */
@RestController
@RequestMapping("/api/service")
public class ServiceController {

    @Autowired
    private AiServiceLayer aiServiceLayer;

    /**
     * 通用问答
     */
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> request) {
        String response = aiServiceLayer.ask(request.get("question"));
        return Map.of("question", request.get("question"), "response", response);
    }

    /**
     * 翻译
     */
    @PostMapping("/translate")
    public Map<String, String> translate(@RequestBody Map<String, String> request) {
        String response = aiServiceLayer.translate(request.get("text"), request.get("targetLang"));
        return Map.of("text", request.get("text"), "response", response);
    }

    /**
     * 代码分析
     */
    @PostMapping("/analyze")
    public Map<String, String> analyze(@RequestBody Map<String, String> request) {
        String response = aiServiceLayer.analyzeCode(request.get("action"), request.get("code"));
        return Map.of("action", request.get("action"), "response", response);
    }

    /**
     * 带记忆的会话对话
     */
    @PostMapping("/session")
    public Map<String, Object> session(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String message = request.get("message");
        String response = aiServiceLayer.chatWithSession(sessionId, message);
        return Map.of("sessionId", sessionId, "message", message, "response", response);
    }

    /**
     * 清除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, String> clearSession(@PathVariable String sessionId) {
        aiServiceLayer.clearSession(sessionId);
        return Map.of("message", "Session cleared: " + sessionId);
    }
}
