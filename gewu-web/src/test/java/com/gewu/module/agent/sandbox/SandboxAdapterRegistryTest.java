package com.gewu.module.agent.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.4.0 C1：沙箱适配器注册机制测试（纯逻辑，无 Spring 上下文、无子进程）
 *
 * <p>验证 C1.3 核心目标：新增工具的沙箱支持只需新增实现类，不改 Runner 核心。
 * 这里手工注入适配器列表构造 Runner（绕过 Spring），断言派发行为。</p>
 */
class SandboxAdapterRegistryTest {

    /** 测试替身：模拟一个新增工具的适配器，验证"零改 Runner"可被发现 */
    private static final class EchoAdapter implements SandboxAdapter {
        @Override
        public String toolName() {
            return "echo_demo";
        }

        @Override
        public Map<String, String> prepare(String argumentsJson) {
            String v = (argumentsJson == null || argumentsJson.isBlank()) ? "" : argumentsJson;
            return Map.of("type", "js", "code", "'" + v.replace("'", "\\'") + "'");
        }
    }

    /** 构造 Runner（不触发自检/子进程） */
    private SandboxRunner newRunner(List<SandboxAdapter> adapters) {
        return new SandboxRunner(new com.gewu.module.agent.tools.SandboxPolicy(), adapters);
    }

    @Test
    void 适配器按toolName注册_可被发现() {
        SandboxRunner r = newRunner(List.of(new CalculatorSandboxAdapter(),
                new DateTimeSandboxAdapter(), new EchoAdapter()));
        // 通过派发行为间接验证注册表：echo_demo 有适配器 → 不会返回"暂无沙箱适配器"
        Map<String, Object> out = r.runTool("echo_demo", "hello");
        assertThat(out).isNotNull();
        assertThat(String.valueOf(out.get("reason"))).doesNotContain("暂无沙箱适配器");
    }

    @Test
    void 未注册工具_返回failClosed错误() {
        SandboxRunner r = newRunner(List.of(new CalculatorSandboxAdapter()));
        Map<String, Object> out = r.runTool("unknown_tool", "{}");
        assertThat(out.get("status")).isEqualTo("error");
        assertThat(String.valueOf(out.get("reason"))).contains("暂无沙箱适配器");
    }

    @Test
    void calculator适配器_入参缺失时返回可判定错误() {
        SandboxRunner r = newRunner(List.of(new CalculatorSandboxAdapter()));
        Map<String, Object> out = r.runTool("calculator", "{}");
        assertThat(out.get("status")).isEqualTo("error");
        assertThat(String.valueOf(out.get("reason"))).contains("入参缺失");
    }

    @Test
    void calculator适配器_非法表达式被白名单拦截() {
        SandboxRunner r = newRunner(List.of(new CalculatorSandboxAdapter()));
        Map<String, Object> out = r.runTool("calculator",
                "{\"expression\":\"Java.type(\\\"java.lang.Runtime\\\")\"}");
        assertThat(out.get("status")).isEqualTo("error");
        assertThat(String.valueOf(out.get("reason"))).contains("不允许的字符");
    }

    @Test
    void datetime适配器_生成js字符串任务() {
        Map<String, String> task = new DateTimeSandboxAdapter().prepare("{}");
        assertThat(task).containsKey("type").containsKey("code");
        assertThat(task.get("type")).isEqualTo("js");
        // 形如 '2026-11-02 15:40:11'
        assertThat(task.get("code")).startsWith("'").endsWith("'");
        assertThat(task.get("code")).matches("'\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}'");
    }

    @Test
    void 适配器声明的风险级正确() {
        assertThat(new CalculatorSandboxAdapter().risk().name()).isEqualTo("DYNAMIC");
        assertThat(new DateTimeSandboxAdapter().risk().name()).isEqualTo("SAFE");
    }
}
