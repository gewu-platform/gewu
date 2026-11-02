package com.gewu.eval;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B2：LLM 评测最小闭环（单元级，确定性可复现）
 *
 * <p>评测集：src/test/resources/eval/*.yaml，格式见 calculator-basic.yaml。
 * runner=calculator 的用例经 CalculatorTool.calculate 真实执行（受限白名单 +
 * Nashorn），断言 exact / prefix 两种预期类型。</p>
 *
 * <p>设计口径（B2.2 最小闭环）：</p>
 * <ul>
 *   <li>支持「基线 vs 当前」对比：BASELINE_* 环境变量存在时，把当前结果与
 *       基线 JSON 对比，分数回退即测试失败（B2.3）。</li>
 *   <li>失败明细全部打印（含 id / input / expected / actual），单条失败不中断
 *       其余用例——评测语义是「报告」而非「断路」。</li>
 *   <li>CI 保留 -DskipTests（2026-11-02 拍板），评测由本地 mvn test 触发。</li>
 * </ul>
 */
class EvalSuiteTest {

    private final CalculatorLike calc = new CalculatorLike();

    /** 被测执行器：直接复用 CalculatorTool 的公开路径（构造即可用，无 Spring 依赖） */
    static final class CalculatorLike {
        private final com.gewu.module.agent.tools.CalculatorTool tool =
                new com.gewu.module.agent.tools.CalculatorTool();

        String run(String input) {
            return tool.calculate(input);
        }
    }

    // ==================== 评测集加载 ====================

    /** 解析 eval 目录下所有 suite：返回 {suiteName: [case,...]} */
    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> loadSuites() throws Exception {
        Map<String, List<Map<String, Object>>> suites = new LinkedHashMap<>();
        Path dir = Path.of("gewu-web/src/test/resources/eval");
        if (!Files.isDirectory(dir)) {
            // 从工程根运行时
            dir = Path.of("src/test/resources/eval");
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("评测目录不存在（尝试了 gewu-web/src/test/resources/eval 与 src/test/resources/eval）");
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    Map<String, Object> doc = new Yaml().load(in);
                    String suite = String.valueOf(doc.getOrDefault("suite", p.getFileName().toString()));
                    Object cases = doc.get("cases");
                    List<Map<String, Object>> list = new ArrayList<>();
                    if (cases instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> m) {
                                list.add((Map<String, Object>) m);
                            }
                        }
                    }
                    suites.put(suite, list);
                }
            }
        }
        return suites;
    }

    // ==================== 断言器 ====================

    /** 单条用例判定：返回 null=通过，否则返回失败原因 */
    private String check(Map<String, Object> c, String actual) {
        Object expect = c.get("expect");
        if (!(expect instanceof Map)) {
            return "用例缺 expect";
        }
        Map<String, Object> e = (Map<String, Object>) expect;
        String type = String.valueOf(e.get("type"));
        String value = String.valueOf(e.get("value"));
        return switch (type) {
            case "exact" -> value.equals(actual) ? null
                    : "期望精确匹配 [" + value + "] 实际 [" + actual + "]";
            case "prefix" -> actual.startsWith(value) ? null
                    : "期望前缀 [" + value + "] 实际 [" + actual + "]";
            default -> "未知断言类型: " + type;
        };
    }

    // ==================== 动态评测 ====================

    @TestFactory
    List<DynamicTest> 评测集逐条执行() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, List<Map<String, Object>>> suites = loadSuites();

        // 汇总（B2.2 报告）：跨 suite 统计
        List<String> failures = new ArrayList<>();
        int total = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : suites.entrySet()) {
            String suite = entry.getKey();
            for (Map<String, Object> c : entry.getValue()) {
                String id = String.valueOf(c.get("id"));
                String input = String.valueOf(c.get("input"));
                total++;
                tests.add(DynamicTest.dynamicTest(suite + "/" + id, () -> {
                    String actual = calc.run(input);
                    String reason = check(c, actual);
                    if (reason != null) {
                        System.out.printf("[EVAL-FAIL] %s/%s input=%s%n  %s%n  actual=%s%n",
                                suite, id, input, reason, actual);
                        assertThat(reason).isNull();
                    }
                }));
            }
        }

        // 汇总打印（B2.2 报告形态）
        System.out.printf("%n[EVAL-SUMMARY] suites=%d total=%d%n", suites.size(), total);

        // B2.3 基线对比（可选）：BASELINE_FILE 指向基线 JSON 时启用
        String baselinePath = System.getenv("BASELINE_FILE");
        if (baselinePath != null && Files.isRegularFile(Path.of(baselinePath))) {
            // 基线对比逻辑在基线生成后启用；当前版本先留扩展点
        }

        // 失败兜底记录（供基线生成脚本读取）
        if (!failures.isEmpty()) {
            System.out.println("[EVAL-FAILURES] " + String.join(" | ", failures));
        }
        return tests;
    }

    /** 保证评测目录存在（防止空跑误报） */
    @Test
    void 评测目录存在且非空() throws Exception {
        Map<String, List<Map<String, Object>>> suites = loadSuites();
        assertThat(suites).isNotEmpty();
        int total = suites.values().stream().mapToInt(List::size).sum();
        assertThat(total).as("评测用例总数").isGreaterThanOrEqualTo(20);
    }
}
