package com.gewu.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.auth.entity.SysToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Token Mapper
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
     * P19：仅失效「同用户 + 同设备」的有效 token（多设备并行在线）
     */
    @Update("UPDATE sys_token SET status = 0 WHERE user_id = #{userId} AND device_id = #{deviceId} AND status = 1")
    int invalidateByUserAndDevice(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    /**
     * P19：按最近活跃时间升序返回该用户的活跃设备标识（用于设备数上限淘汰）
     */
    @Select("SELECT device_id FROM sys_token WHERE user_id = #{userId} AND status = 1 AND device_id IS NOT NULL GROUP BY device_id ORDER BY MAX(update_time) ASC")
    java.util.List<String> selectActiveDeviceIdsByLeastRecent(@Param("userId") Long userId);

    /**
     * P19：按 refresh token 反查所属设备标识（刷新流程继承原设备）
     */
    @Select("SELECT device_id FROM sys_token WHERE refresh_token = #{refreshToken} LIMIT 1")
    String selectDeviceIdByRefreshToken(@Param("refreshToken") String refreshToken);

    /**
     * 手动使指定 refresh token 失效（登出/刷新时调用）
     */
    @Update("UPDATE sys_token SET status = 0 WHERE refresh_token = #{refreshToken} AND status = 1")
    int invalidateByRefreshToken(@Param("refreshToken") String refreshToken);
}
