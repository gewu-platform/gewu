package com.gewu.module.auth.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper（对齐 java-ai-native UserMapper）
 *
 * <p>注：User 实体未配置 @TableLogic，查询时需手动过滤 deleted=0。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户（未删除）
     */
    default User findByUsername(String username) {
        return selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getDeleted, 0));
    }

    /**
     * 根据邮箱查询用户（未删除）
     */
    default User findByEmail(String email) {
        return selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .eq(User::getDeleted, 0));
    }
}
