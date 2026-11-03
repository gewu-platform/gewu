package com.gewu.infrastructure.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * V2.2.0 A2/T2.2：traceId 贯穿
 *
 * <p>每个请求生成或透传 traceId，写入 SLF4J MDC（键 `traceId`），
 * 日志格式已配置为 `%X{traceId:--}`，因此同一次请求的所有日志可 grep 串联。</p>
 *
 * <p>透传规则：优先取上游 `X-Trace-Id` 请求头（便于网关/前端串联），
 * 缺失则生成短 UUID（16 位）。响应头回写 `X-Trace-Id`，便于前端/调用方排查。</p>
 *
 * <p>置于最高优先级（{@code Ordered.HIGHEST_PRECEDENCE}），确保认证、业务、
 * 异常处理日志都带 traceId。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键名（与 application.yml 日志 pattern 中的 %X{traceId} 对应） */
    public static final String MDC_KEY = "traceId";

    /** 请求/响应头名称 */
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? sanitize(incoming)
                : generate();

        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清理：线程池复用线程，残留会污染下一个请求
            MDC.remove(MDC_KEY);
        }
    }

    /** 生成 16 位短 traceId（够用且日志可读） */
    private String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 上游 traceId 清洗：仅保留字母数字与连字符，限长 64，防日志注入 */
    private String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9-]", "");
        if (s.isEmpty()) {
            return generate();
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** 供非 HTTP 上下文（如沙箱子进程、定时任务）设置 traceId */
    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, traceId);
        }
    }

    /** 读取当前线程 traceId（无则返回 null） */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }
}
