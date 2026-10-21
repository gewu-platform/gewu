package com.example.lc4j.module.agent.dto;

import lombok.Data;

/**
 * Agent 对话请求 DTO（对齐 java-ai-native AgentChatRequest）
 */
@Data
public class AgentChatRequest {

    /** 用户消息（必填） */
    private String message;

    /** Agent ID（可选，指定使用哪个自定义 Agent） */
    private String agentId;

    /** 额外系统提示词（可选） */
    private String systemPrompt;

    /** 指定模型 ID（可选，为空则使用当前激活模型） */
    private String modelId;
}
