package com.gewu.module.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B1：SandboxPolicy 单元测试（纯逻辑，无 Spring 上下文，直接 setter 注入）
 *
 * <p>固化 V2.1.0 B1/B4 冒烟语义：三级策略、fail-closed 默认、降级开关两模式、
 * snapshot 完整性。calculate→DYNAMIC 的默认策略在 ToolRegistryTest 覆盖。</p>
 */
class SandboxPolicyTest {

    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SandboxPolicy();
        // @Value 默认值不会注入（非 Spring 环境），显式对齐 yml 默认
        policy.setSafe("direct");
        policy.setDynamic("direct");
        policy.setExternal("sandbox");
        policy.setDangerous("sandbox");
        policy.setOnUnavailable("reject");
    }

    // ==================== 策略映射 ====================

    @Test
    void 策略映射_与yml默认一致() {
        assertThat(policy.policyFor(ToolRisk.SAFE)).isEqualTo("direct");
        assertThat(policy.policyFor(ToolRisk.DYNAMIC)).isEqualTo("direct");
        assertThat(policy.policyFor(ToolRisk.EXTERNAL)).isEqualTo("sandbox");
        assertThat(policy.policyFor(ToolRisk.DANGEROUS)).isEqualTo("sandbox");
    }

    @Test
    void 未知风险级按SAFE处理() {
        assertThat(policy.policyFor(null)).isEqualTo("direct");
    }

    // ==================== fail-closed（B1 核心）====================

    @Test
    void 沙箱不可用时sandbox策略被拒() {
        policy.setSandboxAvailable(false);
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isFalse();
        assertThat(policy.allowExecution(ToolRisk.DANGEROUS)).isFalse();
        // safe/dynamic 为 direct，不受沙箱影响
        assertThat(policy.allowExecution(ToolRisk.SAFE)).isTrue();
        assertThat(policy.allowExecution(ToolRisk.DYNAMIC)).isTrue();
    }

    @Test
    void 沙箱可用时sandbox策略放行() {
        policy.setSandboxAvailable(true);
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isTrue();
        assertThat(policy.requireSandbox(ToolRisk.EXTERNAL)).isTrue();
    }

    @Test
    void disabled策略无条件拒绝() {
        policy.setExternal("disabled");
        policy.setSandboxAvailable(true); // 即使沙箱可用也拒
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isFalse();
    }

    @Test
    void requireSandbox_沙箱不可用时为false() {
        policy.setSandboxAvailable(false);
        assertThat(policy.requireSandbox(ToolRisk.EXTERNAL)).isFalse();
        policy.setSandboxAvailable(true);
        assertThat(policy.requireSandbox(ToolRisk.EXTERNAL)).isTrue();
    }

    // ==================== B4/T4.2 降级开关 ====================

    @Test
    void 降级默认reject_沙箱不可用时不放行() {
        policy.setOnUnavailable("reject");
        policy.setSandboxAvailable(false);
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isFalse();
        assertThat(policy.isDegradedDirect(ToolRisk.EXTERNAL)).isFalse();
    }

    @Test
    void allowDirect降级_沙箱不可用时放行且判定为降级态() {
        policy.setOnUnavailable("allow-direct");
        policy.setSandboxAvailable(false);
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isTrue();
        assertThat(policy.isDegradedDirect(ToolRisk.EXTERNAL)).isTrue();
    }

    @Test
    void allowDirect_沙箱可用时不属于降级态() {
        policy.setOnUnavailable("allow-direct");
        policy.setSandboxAvailable(true);
        assertThat(policy.allowExecution(ToolRisk.EXTERNAL)).isTrue();
        assertThat(policy.isDegradedDirect(ToolRisk.EXTERNAL)).isFalse();
    }

    @Test
    void 未知降级值兜底为reject() {
        policy.setOnUnavailable("whatever");
        assertThat(policy.normOnUnavailable()).isEqualTo("reject");
        policy.setOnUnavailable(null);
        assertThat(policy.normOnUnavailable()).isEqualTo("reject");
    }

    @Test
    void 策略值归一化_未知值回落direct() {
        policy.setSafe("nonsense");
        assertThat(policy.policyFor(ToolRisk.SAFE)).isEqualTo("direct");
        policy.setSafe("");
        assertThat(policy.policyFor(ToolRisk.SAFE)).isEqualTo("direct");
    }

    // ==================== snapshot ====================

    @Test
    void snapshot_包含全部键且值正确() {
        policy.setSandboxAvailable(true);
        Map<String, Object> snap = policy.snapshot();
        assertThat(snap).containsKeys("safe", "dynamic", "external", "dangerous",
                "onUnavailable", "sandboxAvailable");
        assertThat(snap.get("external")).isEqualTo("sandbox");
        assertThat(snap.get("onUnavailable")).isEqualTo("reject");
        assertThat(snap.get("sandboxAvailable")).isEqualTo(true);
    }
}
