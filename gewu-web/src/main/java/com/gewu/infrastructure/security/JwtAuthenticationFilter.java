package com.gewu.infrastructure.security;

import com.gewu.module.auth.service.SysTokenService;
import com.gewu.security.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT认证过滤器（对齐 java-ai-native JwtAuthenticationFilter）
 * 在请求到达控制器之前验证JWT令牌
 * 增加数据库 token 有效性校验（替代 Redis）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final SysTokenService sysTokenService;

    /** 认证动作端点（无需 JWT 解析）；/api/auth/me 不在此列，需注入 Authentication */
    private boolean isAuthAction(String uri) {
        return uri.equals("/api/auth/login") || uri.equals("/api/auth/register")
                || uri.equals("/api/auth/refresh") || uri.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String requestURI = request.getRequestURI();

        // 只跳过认证动作端点与健康检查；/api/auth/me 需要解析 token 注入认证信息（公开端点由 SecurityConfig 放行）
        if (requestURI.startsWith("/api/health/") ||
                isAuthAction(requestURI)) {
            chain.doFilter(request, response);
            return;
        }

        final String header = request.getHeader("Authorization");
        String username = null;
        String authToken = null;

        if (header != null && header.startsWith("Bearer ")) {
            authToken = header.substring(7);
            try {
                username = jwtUtil.getUsernameFromToken(authToken);
            } catch (Exception e) {
                log.warn("JWT令牌解析失败: {}", e.getMessage());
            }
        }

        // 验证令牌并设置认证信息
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 先校验 JWT 签名 + 过期
            if (!jwtUtil.validateToken(authToken, username)) {
                log.warn("JWT令牌无效: username={}", username);
                chain.doFilter(request, response);
                return;
            }
            // 再校验数据库中的 token 状态（替代 Redis）
            if (!sysTokenService.isTokenValid(authToken)) {
                log.warn("数据库中 Token 已失效: username={}", username);
                chain.doFilter(request, response);
                return;
            }

            UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
