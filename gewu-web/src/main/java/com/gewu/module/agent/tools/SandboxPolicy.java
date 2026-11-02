package com.gewu.module.agent.tools;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.1.0 B1/T1.2：工具执行策略（按风险级决定「怎么执行」）
 *
 * <p>v2.0.1 的白名单管「哪些工具能进」，本策略管「进来后怎么执行」：</p>
 * <ul>
 *   <li>{@code direct}——直执行（当前行为）</li>
 *   <li>{@code sandbox}——必须沙箱执行（B2 未落地前按 fail-closed 拒绝，见 {@link #isSandboxAvailable}）</li>
 *   <li>{@code disabled}——禁止执行</li>
 * </ul>
 *
 * <p>沙箱运行时（B2）尚未实现时，标记为 sandbox 的风险级按 fail-closed 拒绝执行，
 * 不静默降级为直执行——避免「以为有沙箱其实没有」的假安全。</p>
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

    /** 沙箱运行时是否可用（B2 落地前恒为 false，fail-closed） */
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
     * 策略为 sandbox 但沙箱不可用时返回 false（fail-closed，不静默直执行）。
     */
    public boolean allowExecution(ToolRisk risk) {
        String p = policyFor(risk);
        if ("disabled".equals(p)) {
            return false;
        }
        if ("sandbox".equals(p) && !isSandboxAvailable()) {
            return false;
        }
        return true;
    }

    /** 需要在沙箱中执行（策略要求且沙箱可用） */
    public boolean requireSandbox(ToolRisk risk) {
        return "sandbox".equals(policyFor(risk)) && isSandboxAvailable();
    }

    /** 当前策略快照（管理接口展示） */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("safe", norm(safe));
        m.put("dynamic", norm(dynamic));
        m.put("external", norm(external));
        m.put("dangerous", norm(dangerous));
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
