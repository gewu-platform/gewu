package com.gewu.module.agent.sandbox;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B1/B3：计算表达式白名单收敛测试（固化 V2.1.0 B3 语义）
 *
 * <p>validateExpression 是 SandboxRunner 的 private static 方法，这里通过反射调用，
 * 固化两路径同源防护的边界：合法算术放行、Java.type/标识符/分号注入被拒、
 * 长度上限 200。</p>
 *
 * <p>注意：CalculatorTool 直执行路径的同名校验在 CalculatorToolTest 覆盖。</p>
 */
class SandboxExpressionGuardTest {

    private static String validate(String expr) {
        try {
            Method m = SandboxRunner.class.getDeclaredMethod("validateExpression", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, expr);
        } catch (Exception e) {
            throw new IllegalStateException("反射调用失败", e);
        }
    }

    @Test
    void 合法算术表达式放行() {
        assertThat(validate("1+1")).isNull();
        assertThat(validate("1234*5678")).isNull();
        assertThat(validate("(100 - 25) * 0.8")).isNull();
        assertThat(validate("2^10")).isNull();
        assertThat(validate("1.5e3 + 2")).isNull();
        assertThat(validate(" 1 + 2 ")).isNull(); // 空白允许
    }

    @Test
    void 空表达式拒绝() {
        assertThat(validate(null)).isNotNull();
        assertThat(validate("")).isNotNull();
        assertThat(validate("   ")).isNotNull();
    }

    @Test
    void Java逃逸payload被拒() {
        assertThat(validate("Java.type(\"java.lang.Runtime\")")).isNotNull();
        assertThat(validate("java.lang.Runtime.getRuntime()")).isNotNull();
        assertThat(validate("Runtime")).isNotNull();
    }

    @Test
    void 标识符与函数调用被拒() {
        assertThat(validate("eval('1+1')")).isNotNull();
        assertThat(validate("Math.floor(1.5)")).isNotNull();
        assertThat(validate("process")).isNotNull();
    }

    @Test
    void 多语句注入被拒() {
        assertThat(validate("1+1; while(true){}")).isNotNull();
        assertThat(validate("1;2")).isNotNull();
    }

    @Test
    void 特殊字符被拒() {
        assertThat(validate("1+1\\n;evil()")).isNotNull();
        assertThat(validate("' OR '1'='1")).isNotNull();
        assertThat(validate("<script>alert(1)</script>")).isNotNull();
        assertThat(validate("${payload}")).isNotNull();
    }

    @Test
    void 超长表达式拒绝_上限200() {
        // 201 个数字字符
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 201; i++) sb.append('1');
        assertThat(validate(sb.toString())).isNotNull();
        // 恰好 200 个：放行
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 200; i++) ok.append('1');
        assertThat(validate(ok.toString())).isNull();
    }
}
