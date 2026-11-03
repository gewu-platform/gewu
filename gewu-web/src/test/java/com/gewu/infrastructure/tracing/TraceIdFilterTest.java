package com.gewu.infrastructure.tracing;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B1：TraceIdFilter 单元测试（Mock Servlet 环境，无容器）
 *
 * <p>固化 V2.2.0 A2 语义：无头生成 16 位 traceId、上游透传、清洗防日志注入、
 * 响应头回写、MDC finally 清理（线程池复用不串号）。</p>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        TraceIdFilter.setTraceId(null); // 兜底清理，防测试间串扰
    }

    @Test
    void 无头时生成16位traceId() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agent/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(req, res, (rq, rs) -> captured[0] = TraceIdFilter.currentTraceId());

        assertThat(captured[0]).isNotBlank().hasSize(16);
        assertThat(res.getHeader("X-Trace-Id")).isEqualTo(captured[0]);
    }

    @Test
    void 透传上游XTraceId() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.addHeader("X-Trace-Id", "mytrace123456");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(req, res, (rq, rs) -> captured[0] = TraceIdFilter.currentTraceId());

        assertThat(captured[0]).isEqualTo("mytrace123456");
        assertThat(res.getHeader("X-Trace-Id")).isEqualTo("mytrace123456");
    }

    @Test
    void 请求结束后MDC被清理_线程池复用不串号() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> { /* 业务空操作 */ });

        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    @Test
    void 业务异常时MDC仍被清理() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse res = new MockHttpServletResponse();

        try {
            filter.doFilter(req, res, (rq, rs) -> { throw new IllegalStateException("boom"); });
        } catch (Exception expected) {
            // 预期异常
        }
        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    @Test
    void 清洗_剔除非法字符只留字母数字与连字符() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.addHeader("X-Trace-Id", "abc<script>alert(1)</script>def");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(req, res, (rq, rs) -> captured[0] = TraceIdFilter.currentTraceId());

        assertThat(captured[0]).isEqualTo("abcscriptalert1scriptdef");
    }

    @Test
    void 清洗_超64位截断() throws Exception {
        String long64Plus = "x".repeat(80);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.addHeader("X-Trace-Id", long64Plus);
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(req, res, (rq, rs) -> captured[0] = TraceIdFilter.currentTraceId());

        assertThat(captured[0]).hasSize(64);
    }

    @Test
    void 清洗_全非法字符时重新生成() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.addHeader("X-Trace-Id", "###$$$%%%");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(req, res, (rq, rs) -> captured[0] = TraceIdFilter.currentTraceId());

        assertThat(captured[0]).isNotBlank().hasSize(16).doesNotContain("#").doesNotContain("$");
    }

    @Test
    void 静态方法可手动设置与清除() {
        TraceIdFilter.setTraceId("manual123456");
        assertThat(TraceIdFilter.currentTraceId()).isEqualTo("manual123456");
        TraceIdFilter.setTraceId(null);
        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }
}
