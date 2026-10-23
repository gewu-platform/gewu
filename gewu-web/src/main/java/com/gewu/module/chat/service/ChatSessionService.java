package com.gewu.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.chat.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天会话服务（对齐 java-ai-native ChatSessionService）
 * 数据库持久化会话；时间戳由数据库 DEFAULT CURRENT_TIMESTAMP 维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService extends ServiceImpl<ChatSessionMapper, ChatSession> {

    private final ChatSessionMapper chatSessionMapper;

    /**
     * 创建新会话
     */
    @Transactional
    public void createSession(String sessionId, String mode, String model, String systemPrompt, Long userId) {
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setMode(mode != null ? mode : "text");
        session.setModel(model);
        session.setSystemPrompt(systemPrompt);
        session.setMessageCount(0);
        session.setInputTokens(0L);
        session.setOutputTokens(0L);
        session.setIsDeleted(0);
        // create_time/update_time 由数据库 DEFAULT CURRENT_TIMESTAMP 维护

        chatSessionMapper.insert(session);
        log.info("创建会话成功：sessionId={}, userId={}, mode={}, model={}", sessionId, userId, mode, model);
    }

    /**
     * 获取会话（排除软删除）
     */
    public ChatSession getSession(String sessionId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getIsDeleted, 0));
    }

    /**
     * 更新会话
     */
    @Transactional
    public void updateSession(ChatSession session) {
        // update_time 由数据库在 update 语句里维护（这里显式赋值保证有值）
        chatSessionMapper.updateById(session);
    }

    /**
     * 增加消息计数
     */
    @Transactional
    public void incrementMessageCount(String sessionId) {
        ChatSession session = baseMapper.selectById(sessionId);
        if (session != null) {
            int newCount = (session.getMessageCount() != null ? session.getMessageCount() : 0) + 1;
            chatSessionMapper.updateMessageCount(sessionId, newCount);
        }
    }

    /**
     * 更新 Token 统计
     */
    @Transactional
    public void updateTokenStats(String sessionId, int inputTokens, int outputTokens) {
        chatSessionMapper.updateTokenStats(sessionId, (long) inputTokens, (long) outputTokens);
    }

    /**
     * 删除会话（软删除，@TableLogic 生效）
     */
    @Transactional
    public void deleteSession(String sessionId) {
        chatSessionMapper.deleteById(sessionId);
        log.info("删除会话：sessionId={}", sessionId);
    }

    /**
     * 根据用户 ID 获取会话列表
     */
    public List<ChatSession> getSessionsByUserId(Long userId) {
        return baseMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getIsDeleted, 0)
                .orderByDesc(ChatSession::getUpdateTime));
    }

    /**
     * 分页获取会话（未删除的）
     */
    public List<ChatSession> getSessionsByPage(Integer page, Integer size) {
        int offset = (page - 1) * size;
        return baseMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getIsDeleted, 0)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 统计会话数量
     */
    public long getSessionCount() {
        return baseMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getIsDeleted, 0));
    }

    /**
     * 获取或创建会话（用于会话复用，对齐 native）
     * 如果存在 24 小时内活跃的会话，则返回该会话；否则创建新会话
     */
    @Transactional
    public String getOrCreateSession(Long userId) {
        ChatSession activeSession = chatSessionMapper.selectActiveRecent();
        if (activeSession != null && (userId == null || userId.equals(activeSession.getUserId()))) {
            log.info("复用活跃会话：sessionId={}, userId={}, lastUpdate={}",
                    activeSession.getId(), userId, activeSession.getUpdateTime());
            return activeSession.getId();
        }

        String sessionId = java.util.UUID.randomUUID().toString();
        createSession(sessionId, "text", null, null, userId);
        log.info("创建新会话（无活跃会话）：sessionId={}, userId={}", sessionId, userId);
        return sessionId;
    }
}
