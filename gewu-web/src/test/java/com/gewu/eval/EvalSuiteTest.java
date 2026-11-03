package com.gewu.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 B2 + V2.5.0 E1：LLM 评测最小闭环（确定性可复现）
 *
 * <p>评测集：src/test/resources/eval/*.yaml，格式见 calculator-basic.yaml。</p>
 *
 * <p><b>E1 评测确定性约束</b>（借鉴 DeepInfer deterministic sampling）：</p>
 * <ul>
 *   <li>E1.1 固定采样参数：评测统一 {@code temperature=0} + 固定 {@code seed}，
 *       可用 {@code EVAL_TEMPERATURE} / {@code EVAL_SEED} 覆盖</li>
 *   <li>E1.2 可复现元信息：报告中输出 seed / temperature / model</li>
 *   <li>E1.3 基线文件化：{@code EVAL_BASELINE_OUT} 落基线；
 *       {@code BASELINE_FILE} 指向基线时逐条对比，回退即失败</li>
 *   <li>E1.4 说明文档：见 eval/README.md</li>
 * </ul>
 *
 * <p><b>实现要点</b>：所有用例在 {@code @TestFactory} 方法内**先求值**，
 * 再构造 DynamicTest 只做断言。因为 DynamicTest 在工厂返回后才执行，
 * 若把求值放在 lambda 里，基线写出/对比时结果集仍为空（曾踩此坑）。</p>
 */
class EvalSuiteTest {

    // ==================== E1：确定性参数 ====================

    /** 评测采样温度：默认 0（确定性），可用 EVAL_TEMPERATURE 覆盖 */
    private static final double EVAL_TEMPERATURE = parseDouble(System.getenv("EVAL_TEMPERATURE"), 0.0);

    /** 评测采样种子：默认固定 42，可用 EVAL_SEED 覆盖 */
    private static final long EVAL_SEED = parseLong(System.getenv("EVAL_SEED"), 42L);

    /** 评测所用模型标识（当前为工具路径；未来接 LLM 时为真实模型 ID） */
    private static final String EVAL_MODEL =
            System.getenv().getOrDefault("EVAL_MODEL", "calculator-tool");

    private final CalculatorLike calc = new CalculatorLike();

    /** 被测执行器：复用 CalculatorTool 公开路径（构造即可用，无 Spring 依赖） */
    static final class CalculatorLike {
        private final com.gewu.module.agent.tools.CalculatorTool tool =
                new com.gewu.module.agent.tools.CalculatorTool();

        String run(String input) {
            return tool.calculate(input);
        }
    }

    // ==================== 评测集加载 ====================

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> loadSuites() throws Exception {
        Map<String, List<Map<String, Object>>> suites = new LinkedHashMap<>();
        Path dir = Path.of("gewu-web/src/test/resources/eval");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("src/test/resources/eval");
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "评测目录不存在（尝试了 gewu-web/src/test/resources/eval 与 src/test/resources/eval）");
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

    @SuppressWarnings("unchecked")
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

    // ==================== 动态评测（先求值，再断言）====================

    @TestFactory
    List<DynamicTest> 评测集逐条执行() throws Exception {
        Map<String, List<Map<String, Object>>> suites = loadSuites();

        // E1.2：可复现元信息
        System.out.printf("%n[EVAL-ENV] temperature=%s seed=%d model=%s%n",
                EVAL_TEMPERATURE, EVAL_SEED, EVAL_MODEL);

        // 先求值（关键：不能在 DynamicTest lambda 里求值）
        Map<String, String> current = new TreeMap<>();          // key → actual
        Map<String, String> failureReasons = new LinkedHashMap<>(); // key → 失败原因
        List<DynamicTest> tests = new ArrayList<>();
        int total = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : suites.entrySet()) {
            String suite = entry.getKey();
            for (Map<String, Object> c : entry.getValue()) {
                String id = String.valueOf(c.get("id"));
                String input = String.valueOf(c.get("input"));
                String key = suite + "/" + id;
                total++;

                String actual = calc.run(input);
                current.put(key, actual);
                String reason = check(c, actual);
                if (reason != null) {
                    failureReasons.put(key, reason);
                    System.out.printf("[EVAL-FAIL] %s input=%s%n  %s%n  actual=%s%n",
                            key, input, reason, actual);
                }

                final String expectedReason = reason;
                tests.add(DynamicTest.dynamicTest(key, () ->
                        assertThat(expectedReason).as(key + " 断言").isNull()));
            }
        }

        // E1.3-①：写基线
        String baselineOut = System.getenv("EVAL_BASELINE_OUT");
        if (baselineOut != null && !baselineOut.isBlank()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("temperature", EVAL_TEMPERATURE);
            payload.put("seed", EVAL_SEED);
            payload.put("model", EVAL_MODEL);
            payload.put("total", total);
            payload.put("results", current);
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(Path.of(baselineOut).toFile(), payload);
            System.out.printf("[EVAL-BASELINE-OUT] 已写出基线：%s（%d 条）%n",
                    baselineOut, current.size());
        }

        // E1.3-②：基线对比（回退即失败）
        String baselineFile = System.getenv("BASELINE_FILE");
        if (baselineFile != null && !baselineFile.isBlank()) {
            if (Files.isRegularFile(Path.of(baselineFile))) {
                compareBaseline(Path.of(baselineFile), current);
            } else {
                System.out.printf("[EVAL-BASELINE] 基线文件不存在，跳过对比：%s%n", baselineFile);
            }
        }

        // 汇总
        int passed = total - failureReasons.size();
        System.out.printf("%n[EVAL-SUMMARY] suites=%d total=%d passed=%d failed=%d%n",
                suites.size(), total, passed, failureReasons.size());

        return tests;
    }

    /**
     * E1.3：基线对比——结果不一致即判定回退（条目缺失/结果变化都算）。
     */
    @SuppressWarnings("unchecked")
    private void compareBaseline(Path baselineFile, Map<String, String> current) throws Exception {
        Map<String, Object> base = new ObjectMapper().readValue(baselineFile.toFile(), Map.class);
        Map<String, Object> results = (Map<String, Object>) base.get("results");
        if (results == null || results.isEmpty()) {
            System.out.printf("[EVAL-BASELINE] 基线无 results 字段，跳过对比：%s%n", baselineFile);
            return;
        }
        int compared = 0;
        List<String> regressions = new ArrayList<>();
        for (Map.Entry<String, Object> e : results.entrySet()) {
            String key = e.getKey();
            String expect = String.valueOf(e.getValue());
            String actual = current.get(key);
            compared++;
            if (actual == null) {
                regressions.add(key + " 缺失（基线有、当前未执行）");
            } else if (!expect.equals(actual)) {
                regressions.add(key + " 变化（基线 [" + expect + "] 当前 [" + actual + "]）");
            }
        }
        System.out.printf("[EVAL-BASELINE] 对比 %d 条（基线 temperature=%s seed=%s model=%s）%n",
                compared, base.get("temperature"), base.get("seed"), base.get("model"));
        if (!regressions.isEmpty()) {
            System.out.println("[EVAL-BASELINE-REGRESS] " + String.join(" | ", regressions));
            assertThat(regressions).as("评测基线回退").isEmpty();
        } else {
            System.out.println("[EVAL-BASELINE] 无回退");
        }
    }

    /** 评测目录存在且非空（防「空跑误报通过」） */
    @Test
    void 评测目录存在且非空() throws Exception {
        Map<String, List<Map<String, Object>>> suites = loadSuites();
        assertThat(suites).isNotEmpty();
        int total = suites.values().stream().mapToInt(List::size).sum();
        assertThat(total).as("评测用例总数").isGreaterThanOrEqualTo(20);
    }

    /** E1.1：确定性参数已固定 */
    @Test
    void 确定性参数已固定() {
        assertThat(EVAL_TEMPERATURE).as("评测温度默认必须为 0").isEqualTo(0.0);
        assertThat(EVAL_SEED).as("评测种子必须非零且固定").isNotZero();
        assertThat(EVAL_MODEL).as("评测模型标识不可为空").isNotBlank();
    }

    // ==================== 工具方法 ====================

    private static double parseDouble(String v, double def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
