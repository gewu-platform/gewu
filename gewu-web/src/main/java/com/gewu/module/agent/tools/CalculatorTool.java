package com.gewu.module.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * 计算器工具（对齐 java-ai-native CalculatorTool）
 *
 * LangChain4j 原生 @Tool 注解：ToolSpecifications.toolSpecificationsFrom(object)
 * 自动扫描生成 ToolSpecification，DefaultToolExecutor 负责反射调用。
 */
@Slf4j
@Component
public class CalculatorTool {

    private final ScriptEngine engine;

    public CalculatorTool() {
        this.engine = new ScriptEngineManager().getEngineByName("JavaScript");
    }

    @Tool(name = "calculator", value = "执行数学表达式计算，支持加减乘除、括号、幂运算（^ 表示幂）。当用户询问数学计算相关问题时使用。")
    public String calculate(
            @P("要计算的数学表达式，例如 '2 + 2'、'(100 - 25) * 0.8'、'2^10'")
            String expression) {
        log.debug("calculator 执行：{}", expression);
        // V2.1.0 B3/T3.1：受限表达式——仅数字与算术字符，阻断 Java.type/标识符/多语句注入
        String vErr = validateExpression(expression);
        if (vErr != null) {
            return vErr;
        }
        try {
            // 幂运算：JS 的 ** 与 ^ 兼容处理（LLM 常输出 ^）
            String js = expression.replaceAll("\\^", "**");
            Object result = engine.eval(js);
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算失败：" + e.getMessage();
        }
    }

    /**
     * V2.1.0 B3/T3.1：受限表达式校验。
     * 白名单字符：数字、算术运算符、括号、幂(^)、小数点、空白、科学计数 e/E；
     * 阻断字母标识符（Java.type/Runtime 逃逸）、分号（多语句注入）、反引号等；
     * T3.2：长度上限 200 字符。返回 null=通过，否则返回错误文案。
     */
    private static String validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "计算失败：表达式为空";
        }
        if (expression.length() > 200) {
            return "计算失败：表达式过长（上限 200 字符）";
        }
        String allowed = "0123456789+-*/().%^ \t\r\neE";
        for (char c : expression.toCharArray()) {
            if (allowed.indexOf(c) < 0) {
                return "计算失败：表达式含不允许的字符（仅支持数字与算术运算符）";
            }
        }
        return null;
    }
}
