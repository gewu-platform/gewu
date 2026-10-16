package com.example.lc4j.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.lc4j.module.auth.entity.SysToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Token Mapper（对齐 java-ai-native SysTokenMapper）
 * 用于数据库维护 token 状态（替代 Redis）
 */
@Mapper
public interface SysTokenMapper extends BaseMapper<SysToken> {

    /**
     * 使指定用户的旧 token 失效（登录时调用）
     */
    @Update("UPDATE sys_token SET status = 0 WHERE user_id = #{userId} AND status = 1")
    int invalidateByUserId(@Param("userId") Long userId);

    /**
     * 手动使指定 refresh token 失效（登出/刷新时调用）
     */
    @Update("UPDATE sys_token SET status = 0 WHERE refresh_token = #{refreshToken} AND status = 1")
    int invalidateByRefreshToken(@Param("refreshToken") String refreshToken);
}
