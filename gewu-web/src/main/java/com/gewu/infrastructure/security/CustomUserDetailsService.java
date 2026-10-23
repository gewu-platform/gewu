package com.gewu.infrastructure.security;

import com.gewu.infrastructure.security.AdminAccounts;
import com.gewu.module.auth.entity.User;
import com.gewu.module.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 用户详情服务实现（对齐 java-ai-native CustomUserDetailsService）
 * 从数据库加载用户详细信息
 * 直接依赖 UserMapper 而非 UserService，避免 Spring 循环依赖
 *
 * @author Matilda
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username);

        if (user == null) {
            log.error("用户不存在: {}", username);
            throw new UsernameNotFoundException("用户不存在: " + username);
        }

        log.debug("加载用户信息: {}", username);
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.getStatus() != null && user.getStatus() == 1, // enabled
                true, // account not expired
                true, // credentials not expired
                true, // account not locked
                getAuthorities(user)
        );
    }

    /**
     * 获取用户权限（P8b：admin 账号授 ROLE_ADMIN，其余 ROLE_USER）
     */
    private Collection<? extends GrantedAuthority> getAuthorities(User user) {
        if (AdminAccounts.isAdmin(user.getUsername())) {
            return List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_USER"));
        }
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
