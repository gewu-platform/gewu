package com.gewu.infrastructure.security;

import java.util.List;

/**
 * 管理员账号名单（P8b 权限管控唯一事实源）
 *
 * <p>使用方：CustomUserDetailsService（授 ROLE_ADMIN）、AuthController（/me 与 login 返回 admin 标记）。
 * 对齐项目当前体量：单表无角色体系，用名单硬编码起步；后续多角色需求再升级角色表。</p>
 */
public final class AdminAccounts {

    /** 管理员用户名名单 */
    public static final List<String> USERNAMES = List.of("admin");

    private AdminAccounts() {
    }

    /** 判断给定用户名是否管理员（null 安全） */
    public static boolean isAdmin(String username) {
        return username != null && USERNAMES.contains(username);
    }
}
