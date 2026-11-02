package com.gewu.module.agent.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.module.agent.tools.SandboxPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * V2.1.0 B2/T2.1+T2.2+T2.3+T2.4：沙箱运行器——把工具调用转到独立子 JVM 执行
 *
 * <p>classpath 自适应两种模式（判据：worker 类 CodeSource URL 协议）：</p>
 * <ul>
 *   <li><b>dev 模式</b>（target/classes / IDE，file URL 指向 .class）：直接复用
 *       {@code java.class.path} 启动子进程。</li>
 *   <li><b>fat-jar 模式</b>（java -jar，CodeSource 指向 .jar）：worker 类在 BOOT-INF
 *       嵌套结构内，子进程拿不到 → 首次使用时把 sandbox 包 class 与 Nashorn/ASM 相关
 *       jar 物化到临时目录（缓存复用），以物化后的完整 classpath 启动子进程。</li>
 * </ul>
 *
 * <p>协议（纯 JDK，与 {@link SandboxWorker} 对齐）：stdin 单行 {@code type\tcode}，
 * stdout 单行 {@code status\tpayload}，字段内换行/制表已由 Worker 的静态转义方法处理。</p>
 *
 * <p>资源与熔断：子 JVM 设 {@code -Xmx}（可配）；超时 {@code destroyForcibly()} 强杀；
 * 应用就绪时 round-trip 自检（1+1=2），成功才置 sandboxAvailable=true——
 * B1 的 fail-closed 语义不变：不可用即拒绝，不静默直执行。</p>
 */
@Slf4j
@Component
public class SandboxRunner {

    @Autowired
    private SandboxPolicy sandboxPolicy;

    /** 沙箱总开关（false 时 sandboxAvailable 恒 false，走 fail-closed） */
    @Value("${gewu.sandbox.runner.enabled:true}")
    private boolean enabled;

    /** 单次调用超时（毫秒），超时强杀子进程 */
    @Value("${gewu.sandbox.runner.timeout-ms:5000}")
    private long timeoutMs;

    /** 子 JVM 内存上限 */
    @Value("${gewu.sandbox.runner.xmx:256m}")
    private String xmx;

    private volatile String lastHealthNote = "未自检";

    /** fat-jar 模式下物化出的完整子进程 classpath（惰性构建，缓存复用） */
    private volatile String childClasspath;

    // ==================== 对外 API ====================

    /**
     * 执行一段 JS（子进程隔离）。
     * 返回 {status: ok|timeout|crash|error, result?, reason?, elapsedMs, exitCode?}
     */
    public Map<String, Object> runJs(String code) {
        Map<String, Object> out = rawRun("js", code == null ? "" : code);
        log.info("[Sandbox] 执行完成：status={}, elapsedMs={}ms", out.get("status"), out.get("elapsedMs"));
        return out;
    }

    /**
     * 按工具名路由沙箱任务（首版仅 calculator——DYNAMIC 且为 JS eval）。
     * 无适配器返回 error（调用方 fail-closed）。
     */
    public Map<String, Object> runTool(String toolName, String argumentsJson) {
        if ("calculator".equals(toolName)) {
            String expression = extractExpression(argumentsJson);
            if (expression == null || expression.isBlank()) {
            // V2.1.0 B3/T3.1：受限表达式校验（与直执行路径同源防护）
            String vErr = validateExpression(expression);
            if (vErr != null) {
                return fail(vErr);
            }
                return fail("calculator 入参缺失（expression）");
            }
            // 与 CalculatorTool 同语义：^ → **
            return rawRun("js", expression.replaceAll("\\^", "**"));
        }
        return fail("工具 '" + toolName + "' 暂无沙箱适配器（首版仅 calculator）");
    }

    public boolean isHealthy() {
        return sandboxPolicy.isSandboxAvailable();
    }

    public String getLastHealthNote() {
        return lastHealthNote;
    }

    // ==================== 自检 ====================

    /** 应用就绪后自检：round-trip 1+1，成功才开放沙箱能力（sandboxAvailable=true） */
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        if (!enabled) {
            lastHealthNote = "沙箱已被配置禁用（gewu.sandbox.runner.enabled=false）";
            sandboxPolicy.setSandboxAvailable(false);
            log.warn("[Sandbox] {}", lastHealthNote);
            return;
        }
        try {
            long start = System.currentTimeMillis();
            Map<String, Object> r = rawRun("js", "1+1");
            boolean ok = "ok".equals(r.get("status")) && "2".equals(String.valueOf(r.get("result")));
            long cost = System.currentTimeMillis() - start;
            sandboxPolicy.setSandboxAvailable(ok);
            lastHealthNote = ok ? "自检通过（" + cost + "ms）" : "自检失败：" + r.get("reason");
            if (ok) {
                log.info("[Sandbox] 子进程自检通过：{}ms，沙箱能力已开放（timeout={}ms, xmx={}）", cost, timeoutMs, xmx);
            } else {
                log.warn("[Sandbox] 自检失败：{} → 相关风险级 fail-closed 拒绝", lastHealthNote);
            }
        } catch (Exception e) {
            sandboxPolicy.setSandboxAvailable(false);
            lastHealthNote = "自检异常：" + e.getMessage();
            log.warn("[Sandbox] 自检异常 → 沙箱不可用：{}", e.getMessage());
        }
    }

    // ==================== 核心执行 ====================

    private Map<String, Object> rawRun(String type, String code) {
        long start = System.currentTimeMillis();
        String cp;
        try {
            cp = resolveChildClasspath();
        } catch (Exception e) {
            return fail("子进程 classpath 解析失败：" + e.getMessage());
        }
        if (cp == null || cp.isBlank()) {
            return fail("子进程 classpath 为空");
        }

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaBin(), "-Xmx" + xmx, "-Dfile.encoding=UTF-8",
                    "-cp", cp, SandboxWorker.class.getName());
            pb.redirectErrorStream(false);
            proc = pb.start();

            // 写任务（单行：type\tcode，code 已转义）
            try (OutputStream os = proc.getOutputStream()) {
                os.write((type + "\t" + SandboxWorker.escape(code)).getBytes(StandardCharsets.UTF_8));
                os.write('\n');
                os.flush();
            }

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outT = drain(proc, stdout, false);
            Thread errT = drain(proc, stderr, true);

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(3, TimeUnit.SECONDS);
                log.warn("[Sandbox] 超时熔断：{}ms 强杀子进程（type={}）", timeoutMs, type);
                return result("timeout", null, "沙箱执行超过 " + timeoutMs + "ms 已熔断",
                        System.currentTimeMillis() - start, null);
            }
            outT.join(1500);
            errT.join(1500);

            int exit = proc.exitValue();
            String line = firstLine(stdout.toString());
            if (exit == 0 && line != null) {
                String[] parts = line.split("\t", 2);
                String status = parts[0];
                String payload = parts.length > 1 ? SandboxWorker.unescape(parts[1]) : "";
                return result(status,
                        "ok".equals(status) ? payload : null,
                        "ok".equals(status) ? null : payload,
                        System.currentTimeMillis() - start, exit);
            }
            String reason = line != null
                    ? SandboxWorker.unescape(line.replaceFirst("^[^\t]*\t?", ""))
                    : tail(stderr.toString(), 300);
            log.warn("[Sandbox] 子进程异常退出：exit={}, reason={}", exit, reason);
            return result(exit == 1 ? "error" : "crash", null,
                    "子进程退出码 " + exit + "：" + reason,
                    System.currentTimeMillis() - start, exit);
        } catch (Exception e) {
            if (proc != null) {
                proc.destroyForcibly();
            }
            log.warn("[Sandbox] 沙箱执行异常：{}", e.getMessage());
            return fail("沙箱执行异常：" + e.getMessage());
        }
    }

    // ==================== classpath 自适应 ====================

    /**
     * dev 模式 → java.class.path；fat-jar 模式 → 物化后的完整 classpath。
     * 判据：worker 类 CodeSource URL——以 .class 结尾的 file URL = dev；
     * 指向 .jar = fat-jar。
     */
    private String resolveChildClasspath() throws Exception {
        if (childClasspath != null) {
            return childClasspath;
        }
        String s = SandboxWorker.class.getProtectionDomain().getCodeSource().getLocation().toString();
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("file:") && !lower.endsWith(".jar") && !lower.endsWith(".zip")) {
            // dev 模式：类在目录里，直接复用主进程 classpath
            String cp = System.getProperty("java.class.path");
            log.info("[Sandbox] 子进程 classpath=dev 模式（复用主进程 java.class.path）");
            return cp;
        }
        // fat-jar / nested 模式：先从 CodeSource 提取真实 jar 路径再物化
        String jarPath = extractJarPath(s);
        if (jarPath == null || !Files.isRegularFile(Paths.get(jarPath))) {
            throw new IllegalStateException("无法从 CodeSource 定位 fat jar：" + s);
        }
        String cp = materialize(jarPath);
        childClasspath = cp;
        return cp;
    }

    /**
     * 从 CodeSource 字符串提取真实 jar 路径（嵌套 jar 的 URI 不透明，不能直接 new File(URI)）。
     * 兼容：jar:nested:/D:/x.jar/!BOOT-INF/classes!/、jar:file:/D:/x.jar!/...、file:/D:/x.jar。
     */
    private String extractJarPath(String s) {
        String t = s;
        // Spring Boot 3.2 nested URL：jar:nested:/D:/x.jar/!BOOT-INF/classes!/
        // jar 路径结束标记是第一个 "/!"（.jar 后接 /!），不是 "!/"（那会匹配到内部路径末尾）
        int bang = t.indexOf("/!");
        if (bang >= 0) {
            t = t.substring(0, bang);
        }
        if (t.startsWith("jar:")) {
            t = t.substring(4);
        }
        if (t.startsWith("nested:")) {
            t = t.substring(7);
            if (t.startsWith("file:")) {
                t = t.substring(5);
            }
        } else if (t.startsWith("file:")) {
            try {
                t = new File(new URI(t)).getAbsolutePath();
            } catch (Exception e) {
                t = t.substring(5);
            }
        }
        // Windows 盘符路径可能带前导斜杠（/D:/x.jar）
        if (t.matches("^/[A-Za-z]:/.*")) {
            t = t.substring(1);
        }
        // nested URI 在 .jar/ 后接 !，剥离后残留尾斜杠 → isRegularFile 会判 false
        while (t.endsWith("/") || t.endsWith("\\")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 从当前 fat jar 提取 sandbox 包 class 与 nashorn/asm 相关 jar 到临时目录，
     * 返回「物化 classes 目录 + 各 jar」拼成的完整 classpath。已存在则复用。
     */
    private synchronized String materialize(String jarPath) throws Exception {
        Path base = Paths.get(System.getProperty("java.io.tmpdir"),
                "gewu-sandbox-" + Integer.toHexString(jarPath.hashCode()));
        Path marker = base.resolve("classpath.txt");
        if (Files.isRegularFile(marker)) {
            String cached = Files.readString(marker, StandardCharsets.UTF_8);
            if (cached != null && !cached.isBlank()) {
                log.info("[Sandbox] 复用已物化沙箱目录：{}", base);
                return cached;
            }
        }
        Path classesDir = base.resolve("classes");
        Path libDir = base.resolve("lib");
        Files.createDirectories(classesDir);
        Files.createDirectories(libDir);

        String prefix = "BOOT-INF/classes/" + SandboxWorker.class.getPackageName().replace('.', '/') + "/";
        int classes = 0;
        int libs = 0;
        try (ZipFile zf = new ZipFile(jarPath)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith(prefix) && n.endsWith(".class")) {
                    String rel = n.substring("BOOT-INF/classes/".length());
                    Path target = classesDir.resolve(rel);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    classes++;
                } else if (n.startsWith("BOOT-INF/lib/")) {
                    String lower = n.toLowerCase(Locale.ROOT);
                    if (lower.contains("nashorn") || lower.contains("asm")) {
                        String name = n.substring("BOOT-INF/lib/".length());
                        try (InputStream in = zf.getInputStream(e)) {
                            Files.copy(in, libDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        }
                        libs++;
                    }
                }
            }
        }
        if (classes == 0) {
            throw new IllegalStateException("fat jar 内未找到 sandbox worker class：" + jarPath);
        }
        StringBuilder cp = new StringBuilder(classesDir.toString());
        try (var list = Files.list(libDir)) {
            list.sorted().forEach(p -> cp.append(File.pathSeparator).append(p));
        }
        String result = cp.toString();
        Files.writeString(marker, result, StandardCharsets.UTF_8);
        log.info("[Sandbox] 沙箱物化完成：base={}, worker classes={}, nashorn/asm jars={}", base, classes, libs);
        return result;
    }

    // ==================== 工具方法 ====================

    private String javaBin() {
        String bin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? bin + ".exe" : bin;
    }

    /** 后台线程排水 stdout/stderr，防子进程管道写满阻塞 */
    private Thread drain(Process proc, StringBuilder sink, boolean stderr) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    stderr ? proc.getErrorStream() : proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (Exception ignore) {
                // 强杀时读流中断属预期
            }
        }, stderr ? "sandbox-drain-err" : "sandbox-drain-out");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** calculator 入参提取：优先 expression 键，兜底第一个值 */
    private String extractExpression(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            ObjectMapper om = new ObjectMapper();
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

    private String firstLine(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        for (String line : stdout.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return null;
    }

    private String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(t.length() - max);
    }

    private Map<String, Object> result(String status, Object result, String reason,
                                       long elapsedMs, Integer exitCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (result != null) {
            m.put("result", result);
        }
        if (reason != null) {
            m.put("reason", reason);
        }
        m.put("elapsedMs", elapsedMs);
        if (exitCode != null) {
            m.put("exitCode", exitCode);
        }
        return m;
    }

    private Map<String, Object> fail(String reason) {
        return result("error", null, reason, 0, null);
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
