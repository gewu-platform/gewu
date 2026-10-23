package com.gewu.module.agent.dto;

import lombok.Data;

/**
 * Agent 对话请求 DTO（对齐 java-ai-native AgentChatRequest）
 *
 * P8a 编排层扩展：sessionId 非空时走「会话模式」——
 * 历史消息从 chat_message 加载注入上下文，回复+工具轨迹落库（对齐对话模块）
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

    /** 会话 ID（P8a 可选：非空时会话模式——历史注入+落库） */
    private String sessionId;

    /** 用户 ID（P8a 可选：会话模式落库归属） */
    private Long userId;

    /** 知识库文档 ID 列表（P8b 可选：非空时检索注入 RAG 上下文） */
    private java.util.List<Long> knowledgeDocumentIds;

    /** RAG 检索条数（P8b 可选，默认 4） */
    private Integer ragTopK;
}
