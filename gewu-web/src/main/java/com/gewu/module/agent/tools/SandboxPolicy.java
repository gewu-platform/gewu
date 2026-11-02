package com.gewu.module.agent.tools;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.1.0 B1/T1.2 + B4/T4.2：工具执行策略（按风险级决定「怎么执行」）
 *
 * <p>v2.0.1 的白名单管「哪些工具能进」，本策略管「进来后怎么执行」：</p>
 * <ul>
 *   <li>{@code direct}——直执行（当前行为）</li>
 *   <li>{@code sandbox}——必须沙箱执行</li>
 *   <li>{@code disabled}——禁止执行</li>
 * </ul>
 *
 * <p><b>降级策略（B4/T4.2）</b>：当策略要求 sandbox 但沙箱不可用时，
 * 由 {@code gewu.sandbox.policy.on-unavailable} 决定：</p>
 * <ul>
 *   <li>{@code reject}（默认）——拒绝执行，不静默降级（fail-closed）</li>
 *   <li>{@code allow-direct}——允许直执行，但由调用方记录 ERROR 级强告警，
 *       并在返回文案与日志中明示「安全边界已降低」</li>
 * </ul>
 */
@Data
@Component
public class SandboxPolicy {

    /** 安全级工具执行方式 */
    @Value("${gewu.sandbox.policy.safe:direct}")
    private String safe;

    /** 动态求值级执行方式 */
    @Value("${gewu.sandbox.policy.dynamic:direct}")
    private String dynamic;

    /** 外部来源级执行方式 */
    @Value("${gewu.sandbox.policy.external:sandbox}")
    private String external;

    /** 高危级执行方式 */
    @Value("${gewu.sandbox.policy.dangerous:sandbox}")
    private String dangerous;

    /**
     * V2.1.0 B4/T4.2：沙箱不可用时怎么办。
     * reject=拒绝执行（默认，fail-closed）/ allow-direct=允许直执行但强告警。
     */
    @Value("${gewu.sandbox.policy.on-unavailable:reject}")
    private String onUnavailable;

    /** 沙箱运行时是否可用（由 SandboxRunner 自检结果写入） */
    private volatile boolean sandboxAvailable = false;

    /** 按风险级取执行方式（未知级按 safe 处理） */
    public String policyFor(ToolRisk risk) {
        if (risk == null) {
            return norm(safe);
        }
        return switch (risk) {
            case SAFE -> norm(safe);
            case DYNAMIC -> norm(dynamic);
            case EXTERNAL -> norm(external);
            case DANGEROUS -> norm(dangerous);
        };
    }

    /**
     * 是否允许执行。
     * 策略为 sandbox 且沙箱不可用时：默认拒绝；若 on-unavailable=allow-direct 则放行（降级）。
     */
    public boolean allowExecution(ToolRisk risk) {
        String p = policyFor(risk);
        if ("disabled".equals(p)) {
            return false;
        }
        if ("sandbox".equals(p)) {
            if (isSandboxAvailable()) {
                return true;
            }
            return isDegradedDirect(risk);
        }
        return true;
    }

    /** 需要在沙箱中执行（策略要求且沙箱可用） */
    public boolean requireSandbox(ToolRisk risk) {
        return "sandbox".equals(policyFor(risk)) && isSandboxAvailable();
    }

    /**
     * V2.1.0 B4/T4.2：当前调用是否处于「降级直执行」状态——
     * 策略要求沙箱、沙箱不可用、且配置允许降级。调用方据此打 ERROR 强告警。
     */
    public boolean isDegradedDirect(ToolRisk risk) {
        return "sandbox".equals(policyFor(risk))
                && !isSandboxAvailable()
                && "allow-direct".equals(normOnUnavailable());
    }

    /** 降级策略归一化（仅 allow-direct 生效，其余按 reject） */
    public String normOnUnavailable() {
        if (onUnavailable == null) {
            return "reject";
        }
        String s = onUnavailable.trim().toLowerCase();
        return "allow-direct".equals(s) ? "allow-direct" : "reject";
    }

    /** 当前策略快照（管理接口展示） */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("safe", norm(safe));
        m.put("dynamic", norm(dynamic));
        m.put("external", norm(external));
        m.put("dangerous", norm(dangerous));
        m.put("onUnavailable", normOnUnavailable());
        m.put("sandboxAvailable", sandboxAvailable);
        return m;
    }

    private String norm(String v) {
        if (v == null || v.isBlank()) {
            return "direct";
        }
        String s = v.trim().toLowerCase();
        return ("sandbox".equals(s) || "disabled".equals(s)) ? s : "direct";
    }
}
