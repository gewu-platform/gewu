package com.example.lc4j.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.lc4j.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天消息 Mapper
 * 
 * @author Matilda
 * @since 2026-05-25
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 根据会话 ID 查询消息列表（按创建时间升序）
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY create_time ASC")
    List<ChatMessage> selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * 根据会话 ID 查询最新消息（限制数量）
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<ChatMessage> selectBySessionIdLimit(@Param("sessionId") String sessionId,
                                              @Param("limit") int limit);

    /**
     * 统计会话消息数量
     */
    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") String sessionId);

    /**
     * 删除会话的所有消息
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
