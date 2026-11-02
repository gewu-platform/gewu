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
            // V2.3.0 B1：Nashorn 不支持 ** 幂运算符（旧实现 replaceAll("^","**") 会语法报错），
            // 先把 a^b 形式改写为 Math.pow(a,b)——Math.pow 在受限白名单下可用
            String js = rewritePowerOps(expression);
            if (js.startsWith("计算失败：")) {
                // 改写阶段的结构性错误（括号不匹配/缺操作数），直接返回不进 eval
                return js;
            }
            Object result = engine.eval(js);
            // V2.3.0 B1：Math.pow 等返回浮点，整数结果去掉 .0 尾（1024.0 → 1024）
            return stripTrailingDotZero(String.valueOf(result));
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

    /**
     * V2.3.0 B1：幂运算改写——把 ^ 形式改写为 Math.pow 调用。
     * Nashorn 不支持 ** 运算符；Math.pow 是白名单内的合法标识符，
     * 因此收紧白名单时同步放行 Math.pow。
     */
    public static String rewritePowerOps(String expression) {
        // 简单配对扫描：a^b → Math.pow(a,b)，支持嵌套（从最内层开始）
        String s = expression;
        int guard = 0;
        while (s.indexOf('^') >= 0 && guard++ < 32) {
            int idx = s.indexOf('^');
            int end = s.length();
            // 左操作数：从 ^ 向左扫描
            int ls = idx - 1;
            if (ls < 0) { return "计算失败：^ 缺少左操作数"; }
            if (s.charAt(ls) == ')') {
                int depth = 0;
                while (ls >= 0) {
                    char ch = s.charAt(ls);
                    if (ch == ')') depth++;
                    else if (ch == '(') { depth--; if (depth == 0) break; }
                    ls--;
                }
                if (ls < 0) { return "计算失败：^ 左括号不匹配"; }
                // V2.3.0 B2 评测发现：左操作数为前次改写的 Math.pow(...) 时，
                // 仅捕获括号组会丢函数名前缀（产生 Math.powMath.pow(...) 非法式），
                // 需向左扩展捕获完整调用标识符
                while (ls > 0) {
                    char prev = s.charAt(ls - 1);
                    if (Character.isLetterOrDigit(prev) || prev == '.' || prev == '_') { ls--; }
                    else { break; }
                }
            } else {
                while (ls >= 0) {
                    char ch = s.charAt(ls);
                    if (Character.isDigit(ch) || ch == '.') { ls--; continue; }
                    if (ch == 'e' || ch == 'E') {
                        char prev = s.charAt(Math.max(0, ls - 1));
                        if (Character.isDigit(prev)) { ls--; continue; }
                    }
                    break;
                }
                ls++;
            }
            String left = s.substring(ls, idx).trim();
            // 右操作数：从 ^ 向右扫描
            int rs = idx + 1;
            if (rs >= end) { return "计算失败：^ 缺少右操作数"; }
            if (s.charAt(rs) == '(') {
                int depth = 0;
                int i = rs;
                while (i < end) {
                    char ch = s.charAt(i);
                    if (ch == '(') depth++;
                    else if (ch == ')') { depth--; if (depth == 0) { i++; break; } }
                    i++;
                }
                if (depth != 0) { return "计算失败：^ 右括号不匹配"; }
                rs = i;
            } else {
                while (rs < end) {
                    char ch = s.charAt(rs);
                    if (Character.isDigit(ch) || ch == '.') { rs++; continue; }
                    if (ch == 'e' || ch == 'E') {
                        char next = s.charAt(Math.min(end - 1, rs + 1));
                        if (Character.isDigit(next)) { rs++; continue; }
                    }
                    break;
                }
            }
            String right = s.substring(idx + 1, rs).trim();
            s = s.substring(0, ls) + "Math.pow(" + left + "," + right + ")" + s.substring(rs);
        }
        return s;
    }
    /**
     * V2.3.0 B1：整数浮点结果归一——尾部恰为 ".0" 时去掉（1024.0 → 1024）。
     * 仅处理精确 .0 尾，不影响 60.00000000000001 这类真实小数。
     */
    public static String stripTrailingDotZero(String s) {
        if (s == null) { return null; }
        String t = s.trim();
        // V2.3.0 B2 评测发现：Nashorn 对超 int 范围整数用科学计数法
        // （999999*999999 → 9.99998000001E11），转 plain 十进制（精确，double<2^53）
        if (t.matches("[+-]?\\d+(\\.\\d+)?[eE][+-]?\\d+")) {
            try { t = new java.math.BigDecimal(t).toPlainString(); } catch (Exception ignore) { }
        }
        // 整数浮点尾归一：1024.0 → 1024
        if (t.endsWith(".0")) { t = t.substring(0, t.length() - 2); }
        return t;
    }
}
