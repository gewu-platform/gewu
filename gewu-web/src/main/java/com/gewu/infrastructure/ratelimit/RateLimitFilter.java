package com.gewu.infrastructure.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * V2.2.0 A3/T3.1+T3.2：接口限流过滤器
 *
 * <p>与 {@code TraceIdFilter} 的分工：TraceId 最先生效（HIGHEST_PRECEDENCE），
 * 本过滤器在 Spring Security 之后（{@code @Order(0)} > Security 默认 -100），
 * 因此能拿到已认证的 SecurityContext，按 userId 而非 IP 限流。</p>
 *
 * <p>作用域：只拦截对话类接口（LLM 边际成本最高的入口）。
 * 探针（/actuator/**）、认证（/api/auth/**）、其余读接口不受此限制——
 * 避免健康检查与普通查询被误伤。</p>
 *
 * <p>超限响应：HTTP 429 + {@code Retry-After} 头 + 与项目一致的
 * {@code {code,message,data}} JSON 体。</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    /** 对话类受限路径前缀（含流式子路径，如 /api/agent/chat/stream/session） */
    private static final String[] CHAT_PREFIXES = {
            "/api/chat",
            "/api/agent/chat"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!rateLimitService.isEnabled() || !isChatScope(path)) {
            chain.doFilter(request, response);
            return;
        }

        String who = resolveSubject(request);
        RateLimitService.Decision d = rateLimitService.tryAcquire("chat:" + who, true);
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // 超限：429 + Retry-After，不消耗每日配额（限流先于配额判定）
        long retryAfter = d.retryAfterSeconds();
        log.warn("[RateLimit] 限流拒绝：subject={}, path={}, retryAfter={}s", who, path, retryAfter);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json;charset=UTF-8");
        String body = String.format(
                "{\"code\":429,\"message\":\"请求过于频繁，请 %d 秒后重试\",\"data\":null}", retryAfter);
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    /** 仅对话类路径受限 */
    private boolean isChatScope(String path) {
        if (path == null) {
            return false;
        }
        for (String p : CHAT_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 限流主体：已认证用户用 userId（principal 仅含 username，足够区分用户且无需查库），
     * 未认证回落到客户端 IP（防未登录刷接口）。优先取 X-Forwarded-For 首个地址。
     */
    private String resolveSubject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal instanceof UserDetails ud && ud.getUsername() != null) {
                return "u:" + ud.getUsername();
            }
            if (principal instanceof String s && !"anonymousUser".equals(s)) {
                return "u:" + s;
            }
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return "ip:" + xff.split(",")[0].trim();
        }
        return "ip:" + (request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr());
    }
}
