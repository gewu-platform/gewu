package com.gewu.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * JWT认证入口点（对齐 java-ai-native JwtAuthenticationEntryPoint）
 * 当未认证用户尝试访问受保护资源时返回401错误
 * 返回格式与 GlobalExceptionHandler 保持一致：R&lt;Void&gt;
 */
@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {

        log.error("未经授权访问受保护资源 - URI: {}", request.getRequestURI());

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        // 统一 R 格式: {code: 401, message: "...", data: null}
        String json = "{\"code\":401,\"message\":\"访问被拒绝：请先登录\",\"data\":null}";
        response.getWriter().write(json);
    }
}
