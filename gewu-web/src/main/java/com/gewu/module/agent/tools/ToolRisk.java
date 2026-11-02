package com.gewu.module.agent.tools;

/**
 * V2.1.0 B1/T1.1：工具执行风险分级
 *
 * <p>分级决定工具「进来后怎么执行」（v2.0.1 的白名单只决定「哪些能进」，两者互补）：</p>
 * <ul>
 *   <li>{@link #SAFE}——纯计算/只读，无副作用，可直执行</li>
 *   <li>{@link #DYNAMIC}——含动态求值（如脚本引擎 eval），需收敛或沙箱</li>
 *   <li>{@link #EXTERNAL}——外部来源（MCP 桥接工具），不可控，需沙箱</li>
 *   <li>{@link #DANGEROUS}——显式标记的高危能力，需审批 + 沙箱</li>
 * </ul>
 */
public enum ToolRisk {

    /** 安全：纯只读/纯计算，无副作用（datetime / timezone） */
    SAFE,

    /** 动态求值：内部实现含脚本引擎等动态执行（calculator） */
    DYNAMIC,

    /** 外部来源：MCP 桥接进来的工具，行为不可控 */
    EXTERNAL,

    /** 危险：显式标记的高危能力（写文件、执行命令等） */
    DANGEROUS;

    /** 展示名（中文，供日志与管理接口使用） */
    public String label() {
        return switch (this) {
            case SAFE -> "安全";
            case DYNAMIC -> "动态求值";
            case EXTERNAL -> "外部来源";
            case DANGEROUS -> "高危";
        };
    }
}
