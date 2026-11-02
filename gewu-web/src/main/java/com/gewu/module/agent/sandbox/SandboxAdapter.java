package com.gewu.module.agent.sandbox;

import com.gewu.module.agent.tools.ToolRisk;

import java.util.Map;

/**
 * V2.4.0 C1/T1.3：沙箱适配器接口
 *
 * <p>把「工具名 → 沙箱可执行的任务」的翻译逻辑从 {@link SandboxRunner} 抽出，
 * 使新增工具的沙箱支持只需新增一个实现类，不改 Runner 核心（C1.3）。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>{@link #supports(String)} 声明支持的工具名</li>
 *   <li>{@link #risk()} 声明该适配器完成后可承载的风险级（用于文档与校验）</li>
 *   <li>{@link #prepare(String)} 把工具入参翻译为沙箱任务（type + code），返回 null 表示不可执行（如入参缺失）；
 *       返回 Map 必须含 "type" 与 "code"</li>
 *   <li>{@link #normalized()} 是否对结果做整数浮点/科学计数法归一（计算器类为 true）</li>
 * </ul>
 */
public interface SandboxAdapter {

    /** 支持的工具名 */
    String toolName();

    /** 该适配器可承载的风险级（文档与自检用） */
    default ToolRisk risk() {
        return ToolRisk.DYNAMIC;
    }

    /**
     * 入参预处理：返回沙箱任务 {type, code}；null = 不可执行（Runner 会 fail-closed）。
     * 适配器负责入参校验（与直执行路径同源）。
     */
    Map<String, String> prepare(String argumentsJson);

    /** 结果是否做数值归一（整数浮点尾 / 科学计数法） */
    default boolean normalized() {
        return false;
    }
}
