package com.example.lc4j.service;

import com.example.lc4j.entity.ChatMessage;
import com.example.lc4j.entity.ChatSession;
import com.example.lc4j.mapper.ChatMessageMapper;
import com.example.lc4j.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 聊天会话服务
 * 
 * 提供会话创建、删除、消息管理等功能
 * 与 java-ai-native 项目的 ChatSessionService / ChatMessageService 保持一致的设计
 *
 * @author Matilda
 * @since 2026-05-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    // ==================== 会话管理 ====================

    /**
     * 创建新会话
     */
    @Transactional
    public ChatSession createSession(String userId, String model, String systemPrompt) {
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId != null ? Long.parseLong(userId) : null);
        session.setModel(model);
        session.setSystemPrompt(systemPrompt);
        session.setMode("text");
        session.setMessageCount(0);
        session.setInputTokens(0L);
        session.setOutputTokens(0L);
        // create_time/update_time 由数据库 DEFAULT CURRENT_TIMESTAMP 维护
        session.setIsDeleted(0);

        sessionMapper.insert(session);
        log.info("创建会话: id={}, model={}, userId={}", session.getId(), model, session.getUserId());
        return session;
    }

    /**
     * 删除会话（软删除）
     */
    @Transactional
    public void deleteSession(String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在: " + sessionId);
        }
        sessionMapper.deleteById(sessionId);
        log.info("删除会话: id={}", sessionId);
    }

    /**
     * 清空会话消息
     */
    @Transactional
    public void clearMessages(String sessionId) {
        messageMapper.deleteBySessionId(sessionId);
        sessionMapper.updateMessageCount(sessionId, 0);
        log.info("清空会话消息: id={}", sessionId);
    }

    /**
     * 查询活跃会话列表（按更新时间倒序）
     */
    public List<ChatSession> listActiveSessions() {
        return sessionMapper.selectActive();
    }

    /**
     * 根据 ID 查询会话详情
     */
    public ChatSession getSession(String sessionId) {
        return sessionMapper.selectById(sessionId);
    }

    // ==================== 消息管理 ====================

    /**
     * 保存消息
     */
    @Transactional
    public void saveMessage(String sessionId, String role, String content, String model,
                            Integer inputTokens, Integer outputTokens, String finishReason, String requestId) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setContentType("text");
        message.setModel(model);
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        message.setFinishReason(finishReason);
        message.setRequestId(requestId);
        // create_time 由数据库 DEFAULT CURRENT_TIMESTAMP 维护

        messageMapper.insert(message);

        // 更新会话统计
        int messageCount = messageMapper.countBySessionId(sessionId);
        sessionMapper.updateMessageCount(sessionId, messageCount);

        if (inputTokens != null || outputTokens != null) {
            sessionMapper.updateTokenStats(sessionId,
                    inputTokens != null ? inputTokens.longValue() : 0L,
                    outputTokens != null ? outputTokens.longValue() : 0L);
        }

        log.debug("保存消息: sessionId={}, role={}, tokens={}", sessionId, role,
                inputTokens != null ? inputTokens : 0);
    }

    /**
     * 根据会话 ID 查询消息列表（按时间升序）
     */
    public List<ChatMessage> getMessages(String sessionId) {
        return messageMapper.selectBySessionId(sessionId);
    }

    /**
     * 根据会话 ID 查询最新消息（限制数量）
     */
    public List<ChatMessage> getRecentMessages(String sessionId, int limit) {
        return messageMapper.selectBySessionIdLimit(sessionId, limit);
    }
}
