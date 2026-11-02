package com.gewu.module.agent.sandbox;

import com.gewu.module.agent.tools.CalculatorTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.4.0 C1/T1.1：calculator 的沙箱适配器
 *
 * <p>把 V2.1.0~V2.3.0 在 {@link SandboxRunner#runTool} 内的硬编码分支迁到此处，
 * 逻辑不变：入参提取 → 缺参失败 → 白名单校验 → 幂运算改写 → 数值归一。</p>
 *
 * <p>实现类标注 {@code @Component} 即被 {@link SandboxRunner} 自动收集（构造注入），
 * 新增工具适配器无需改动 Runner 核心（C1.3）。</p>
 */
@Component
public class CalculatorSandboxAdapter implements SandboxAdapter {

    @Override
    public String toolName() {
        return "calculator";
    }

    @Override
    public boolean normalized() {
        return true;
    }

    @Override
    public Map<String, String> prepare(String argumentsJson) {
        String expression = extractExpression(argumentsJson);
        // 顺序：先判缺参（V2.4.0 C1 修复——原实现校验跑在缺参分支内，非法式会绕过校验）
        if (expression == null || expression.isBlank()) {
            return null;
        }
        // V2.1.0 B3：受限表达式校验（与直执行路径同源）
        String vErr = SandboxRunner.validateExpressionPublic(expression);
        if (vErr != null) {
            // 校验失败：以错误任务形式回传，让 Runner 走 fail 语义
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put("type", "error");
            bad.put("code", vErr);
            return bad;
        }
        // V2.3.0 B1：Nashorn 不支持 **，复用 CalculatorTool 的 Math.pow 改写
        String rewritten = CalculatorTool.rewritePowerOps(expression);
        if (rewritten.startsWith("计算失败：")) {
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put("type", "error");
            bad.put("code", rewritten);
            return bad;
        }
        Map<String, String> task = new LinkedHashMap<>();
        task.put("type", "js");
        task.put("code", CalculatorTool.stripTrailingDotZero(rewritten));
        return task;
    }

    /** 入参提取：优先 expression 键，兜底第一个值 */
    private String extractExpression(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<?, ?> m = om.readValue(argumentsJson, Map.class);
            Object v = m.get("expression");
            if (v == null && !m.isEmpty()) {
                v = m.values().iterator().next();
            }
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}
