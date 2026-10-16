package com.example.lc4j.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.lc4j.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 聊天会话 Mapper
 * 
 * @author Matilda
 * @since 2026-05-25
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
     * 更新会话 Token 统计（累加）
     */
    @Update("UPDATE chat_session SET input_tokens = input_tokens + #{inputTokens}, " +
            "output_tokens = output_tokens + #{outputTokens}, " +
            "update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateTokenStats(@Param("id") String id,
                         @Param("inputTokens") Long inputTokens,
                         @Param("outputTokens") Long outputTokens);

    /**
     * 查询未删除的会话（按更新时间倒序）
     */
    List<ChatSession> selectActive();

    /**
     * 统计会话数量
     */
    int count();
}
