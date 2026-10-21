package com.example.lc4j.module.agent.tools;

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
        try {
            // 幂运算：JS 的 ** 与 ^ 兼容处理（LLM 常输出 ^）
            String js = expression.replaceAll("\\^", "**");
            Object result = engine.eval(js);
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算失败：" + e.getMessage();
        }
    }
}
