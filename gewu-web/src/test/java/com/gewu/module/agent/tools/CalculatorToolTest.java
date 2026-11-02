package com.gewu.module.agent.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B1/B3：CalculatorTool 直执行路径的表达式收敛测试
 *
 * <p>固化 V2.1.0 B3 语义：直执行（DYNAMIC/direct）与沙箱路径共用同一套
 * 白名单规则——这里通过反射调用 private validateExpression，覆盖
 * 「合法算术 + 引擎真实求值」与「注入 payload 被拒」。</p>
 */
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    private static String validate(String expr) {
        try {
            Method m = CalculatorTool.class.getDeclaredMethod("validateExpression", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, expr);
        } catch (Exception e) {
            throw new IllegalStateException("反射调用失败", e);
        }
    }

    @Test
    void 合法算术_引擎求值正确() {
        assertThat(tool.calculate("1234*5678")).isEqualTo("7006652");
        assertThat(tool.calculate("2^10")).isEqualTo("1024"); // V2.3.0：Nashorn 幂运算缺陷回归
        // JS 浮点：75*0.8 = 60.00000000000001，用前缀断言避免二进制浮点陷阱
        assertThat(tool.calculate("(100 - 25) * 0.8")).startsWith("60");
        assertThat(tool.calculate("1+1")).isEqualTo("2");
    }

    @Test
    void 白名单校验_合法算术放行() {
        assertThat(validate("1234*5678")).isNull();
        assertThat(validate("2^10")).isNull();
    }

    @Test
    void 白名单校验_空表达式拒绝() {
        assertThat(validate(null)).isNotNull();
        assertThat(validate("")).isNotNull();
    }

    @Test
    void 白名单校验_Java逃逸payload被拒() {
        assertThat(validate("Java.type(\"java.lang.Runtime\")")).isNotNull();
        assertThat(validate("java.lang.Runtime")).isNotNull();
    }

    @Test
    void 白名单校验_标识符与多语句被拒() {
        assertThat(validate("eval('1')")).isNotNull();
        assertThat(validate("1;2")).isNotNull();
    }

    @Test
    void calculate_实际调用注入payload被拒_而非引擎执行() {
        // 端到端：即使引擎能解析，白名单也应先拦下
        String out = tool.calculate("Java.type(\"java.lang.Runtime\")");
        assertThat(out).contains("计算失败");
        assertThat(out).doesNotContain("Runtime");
    }

    @Test
    void calculate_引擎原生错误保持既有文案() {
        String out = tool.calculate("1/0");
        // JS 里 1/0 = Infinity（不抛错），换个会抛错的：非法括号
        String out2 = tool.calculate("((1+2");
        assertThat(out2).startsWith("计算失败");
    }
}
