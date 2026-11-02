package com.gewu.module.agent.sandbox;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * V2.1.0 B2/T2.1：沙箱工作进程（独立 JVM，与主服务隔离）
 *
 * <p>由 {@link SandboxRunner} 以子进程方式启动；采用<b>纯 JDK 协议</b>（不依赖 Jackson，
 * 以便最小化 classpath）：stdin 首行为任务、stdout 首行为结果。</p>
 *
 * <p>任务格式（单行）：{@code <type>\t<code>}，code 内换行转义为 {@code \n}、制表符转义为 {@code \\t}。</p>
 * <p>结果格式（单行）：{@code ok\t<result>} / {@code error\t<reason>}（字段内换行同样转义）。</p>
 *
 * <p>安全边界：只在子进程运行；无 Spring、无密钥、无 DB、无网络出口配置；
 * 死循环由父进程 destroyForcibly 强杀（本进程不自救）。</p>
 */
public final class SandboxWorker {

    private SandboxWorker() {
    }

    public static void main(String[] args) {
        String line;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            line = in.readLine();
        } catch (Exception e) {
            emit("error", "读取任务失败: " + safe(e.getMessage()));
            System.exit(2);
            return;
        }
        if (line == null || line.isBlank()) {
            emit("error", "空任务");
            System.exit(2);
            return;
        }

        String[] parts = line.split("\t", 2);
        String type = parts[0];
        String code = parts.length > 1 ? unescape(parts[1]) : "";

        if (!"js".equals(type)) {
            emit("error", "不支持的任务类型: " + type);
            System.exit(2);
            return;
        }

        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
            if (engine == null) {
                emit("error", "JS 引擎不可用(Nashorn 未加载)");
                System.exit(3);
                return;
            }
            Object result = engine.eval(code);
            emit("ok", result == null ? "" : String.valueOf(result));
            System.exit(0);
        } catch (Throwable t) {
            emit("error", safe(t.getMessage()));
            System.exit(1);
        }
    }

    private static void emit(String status, String payload) {
        System.out.println(status + "\t" + escape(payload == null ? "" : payload));
        System.out.flush();
    }

    /** 单行转义：换行→\n、回车→\r、制表→\t、反斜杠→\\ */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    default -> {
                        sb.append('\\').append(n);
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
