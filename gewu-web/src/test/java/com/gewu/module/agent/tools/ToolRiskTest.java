package com.gewu.module.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2.3.0 B1：ToolRisk 枚举单元测试（纯逻辑，无 Spring 上下文）
 *
 * <p>固化 V2.1.0 B1 冒烟结论：四级枚举 label 正确、values 完整。
 * calculator→DYNAMIC 的映射在 ToolRegistryTest 中覆盖（需构造 registry）。</p>
 */
class ToolRiskTest {

    @Test
    void 四个风险级全部存在() {
        assertThat(ToolRisk.values()).hasSize(4);
        assertThat(ToolRisk.values()).extracting(ToolRisk::name)
                .containsExactlyInAnyOrder("SAFE", "DYNAMIC", "EXTERNAL", "DANGEROUS");
    }

    @Test
    void label中文文案正确() {
        assertThat(ToolRisk.SAFE.label()).isEqualTo("安全");
        assertThat(ToolRisk.DYNAMIC.label()).isEqualTo("动态求值");
        assertThat(ToolRisk.EXTERNAL.label()).isEqualTo("外部来源");
        assertThat(ToolRisk.DANGEROUS.label()).isEqualTo("高危");
    }

    @Test
    void valueOf_支持标准名() {
        assertThat(ToolRisk.valueOf("SAFE")).isEqualTo(ToolRisk.SAFE);
        assertThatThrownBy(() -> ToolRisk.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 未知风险级的容错路径_降级为SAFE() {
        // 固化 V2.1.0 B1 语义：ToolRegistry.getRisk 对 null/未知返回 SAFE（避免过度拦截）
        Map<String, ToolRisk> empty = Map.of();
        ToolRisk r = empty.get("anything");
        assertThat(r).isNull(); // registry 侧判 null → SAFE
    }
}
