package com.gewu.module.chat.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gewu.module.chat.entity.ChatMessage;
import com.gewu.module.chat.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天消息服务（对齐 java-ai-native ChatMessageService）
 * 时间戳由数据库 DEFAULT CURRENT_TIMESTAMP 维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService extends ServiceImpl<ChatMessageMapper, ChatMessage> {

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 保存消息（通用方法，对齐 native saveMessage）
     */
    @Transactional
    public ChatMessage saveMessage(String sessionId, String role, String content, String contentType,
                                   String imageUrls, String model, Integer inputTokens,
                                   Integer outputTokens, String finishReason, String requestId, Long userId) {
        return saveMessage(sessionId, role, content, contentType,
                imageUrls, model, inputTokens,
                outputTokens, finishReason, requestId, userId, null);
    }

    /**
     * 保存消息（P8a 编排层扩展：智能模式带工具调用轨迹 toolCalls JSON）
     */
    @Transactional
    public ChatMessage saveMessage(String sessionId, String role, String content, String contentType,
                                   String imageUrls, String model, Integer inputTokens,
                                   Integer outputTokens, String finishReason, String requestId, Long userId,
                                   String toolCalls) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setContentType(contentType);
        message.setImageUrls(imageUrls);
        message.setModel(model);
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        message.setFinishReason(finishReason);
        message.setRequestId(requestId);
        message.setToolCalls(toolCalls);
        // create_time 由数据库 DEFAULT CURRENT_TIMESTAMP 维护

        chatMessageMapper.insert(message);
        log.info("保存消息成功：sessionId={}, userId={}, role={}, contentLength={}",
                sessionId, userId, role, content != null ? content.length() : 0);
        return message;
    }

    /**
     * 根据会话 ID 获取消息列表
     */
    public List<ChatMessage> getMessagesBySessionId(String sessionId) {
        return chatMessageMapper.selectBySessionId(sessionId);
    }

    /**
     * 删除会话消息
     */
    @Transactional
    public void deleteBySessionId(String sessionId) {
        chatMessageMapper.deleteBySessionId(sessionId);
        log.info("删除会话消息：sessionId={}", sessionId);
    }
}
