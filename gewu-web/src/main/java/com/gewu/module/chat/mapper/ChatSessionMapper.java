package com.gewu.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 聊天会话 Mapper（对齐 java-ai-native ChatSessionMapper）
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 更新会话消息数量
     */
    @Update("UPDATE chat_session SET message_count = #{messageCount}, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateMessageCount(@Param("id") String id,
                           @Param("messageCount") int messageCount);

    /**
     * 更新会话 Token 统计（累计）
     */
    @Update("UPDATE chat_session SET input_tokens = input_tokens + #{inputTokens}, " +
            "output_tokens = output_tokens + #{outputTokens}, " +
            "update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateTokenStats(@Param("id") String id,
                         @Param("inputTokens") Long inputTokens,
                         @Param("outputTokens") Long outputTokens);

    /**
     * 查询最近 24 小时内活跃的会话（用于会话复用，对齐 native selectActiveRecent）
     */
    @Select("SELECT * FROM chat_session " +
            "WHERE is_deleted = 0 AND update_time > CURRENT_TIMESTAMP - INTERVAL '24 hours' " +
            "ORDER BY update_time DESC LIMIT 1")
    ChatSession selectActiveRecent();

    /**
     * 根据用户 ID 查询会话列表（按更新时间倒序）
     */
    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} AND is_deleted = 0 ORDER BY update_time DESC")
    List<ChatSession> selectByUserId(@Param("userId") Long userId);

    /**
     * 统计会话数量
     */
    @Select("SELECT COUNT(*) FROM chat_session WHERE is_deleted = 0")
    int count();
}
