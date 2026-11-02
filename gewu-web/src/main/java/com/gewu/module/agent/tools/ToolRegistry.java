package com.gewu.module.agent.tools;

import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工具注册中心（对齐 java-ai-native ToolRegistry，lc4j 原生实现）
 *
 * 持有工具实例 + 反射生成的 ToolSpecification/ToolExecutor，
 * DefaultToolExecutor 与 AiServices 内部同款，行为一致。
 */
@Slf4j
@Component
public class ToolRegistry {

    @Autowired
    private ApplicationContext applicationContext;

    /** 工具实例（@Tool 注解扫描源） */
    private final Map<String, Object> toolBeans = new ConcurrentHashMap<>();

    /** 工具规格（LLM 可见） */
    private final Map<String, dev.langchain4j.agent.tool.ToolSpecification> specs = new ConcurrentHashMap<>();

    /** 工具执行器（反射调用） */
    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();

    /** V2.1.0 B1：工具风险级（工具名 → 风险级） */
    private final Map<String, ToolRisk> riskLevels = new ConcurrentHashMap<>();

    /** V2.1.0 B1/T1.2：执行策略（按风险级决定直执行/沙箱/禁用） */
    @org.springframework.beans.factory.annotation.Autowired
    private SandboxPolicy sandboxPolicy;

    /** V2.1.0 B2：沙箱运行器（可选依赖；未装配/未启用时为 null，走 fail-closed） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.gewu.module.agent.sandbox.SandboxRunner sandboxRunner;

    /**
     * T7：容器就绪后自动扫描 tools 包的 @Component/@Service Bean 并注册。
     * ApplicationReadyEvent 时机：所有 Bean 已就绪，避免 @PostConstruct 期间
     * getBeansWithAnnotation 触发 @RestController（元注解含 @Component）连带创建造成循环依赖。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoScanRegister() {
        int count = 0;
        for (Object bean : applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Component.class).values()) {
            if (bean.getClass().getName().startsWith("com.gewu.module.agent.tools.")) {
                register(bean);
                count++;
            }
        }
        log.info("工具自动扫描完成：tools 包 {} 个 Bean，当前注册 {} 个工具: {}",
                count, specs.size(), specs.keySet());
    }

    /**
     * 注册工具实例（扫描全部 @Tool 方法）
     */
    public void register(Object toolBean) {
        var specifications = ToolSpecifications.toolSpecificationsFrom(toolBean);
        if (specifications.isEmpty()) {
            log.warn("注册的工具实例没有任何 @Tool 方法：{}", toolBean.getClass().getSimpleName());
            return;
        }

        // 每个对象通常一个 @Tool 方法；多方法时逐一生成 executor
        for (dev.langchain4j.agent.tool.ToolSpecification spec : specifications) {
            specs.put(spec.name(), spec);
            executors.put(spec.name(), new DefaultToolExecutor(toolBean, findMethod(toolBean, spec.name())));
            // V2.1.0 B1：内置工具按能力标注风险级
            riskLevels.put(spec.name(), classifyBuiltin(spec.name()));
        }

        toolBeans.put(specifications.get(0).name(), toolBean);
        log.info("注册 Agent 工具：{}", specifications.stream()
                .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                .toList());
    }

    /**
     * 获取全部工具规格（发给 LLM）
     */
    public List<dev.langchain4j.agent.tool.ToolSpecification> getAllSpecifications() {
        return new ArrayList<>(specs.values());
    }

    /**
     * 按名称列表取工具规格（Agent 定义绑定工具集）
     */
    public List<dev.langchain4j.agent.tool.ToolSpecification> getSpecifications(Collection<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<dev.langchain4j.agent.tool.ToolSpecification> result = new ArrayList<>();
        for (String id : toolIds) {
            dev.langchain4j.agent.tool.ToolSpecification spec = specs.get(id);
            if (spec != null) {
                result.add(spec);
            }
        }
        return result;
    }

    /**
     * 执行工具调用
     */
    public String execute(ToolExecutionRequest request) {
        ToolExecutor executor = executors.get(request.name());
        if (executor == null) {
            return String.format("错误：未找到名为 '%s' 的工具。可用工具：%s",
                    request.name(), specs.keySet());
        }

        // V2.1.0 B1/T1.2：按风险级决定是否允许执行（沙箱不可用且策略要求沙箱 → fail-closed 拒绝）
        ToolRisk risk = getRisk(request.name());
        if (sandboxPolicy != null && !sandboxPolicy.allowExecution(risk)) {
            log.warn("[Sandbox] 拒绝执行：tool={}, risk={}, policy={}, sandboxAvailable={}",
                    request.name(), risk.name(), sandboxPolicy.policyFor(risk), sandboxPolicy.isSandboxAvailable());
            return String.format("工具 '%s' 因风险策略被拒绝执行（风险级=%s，策略=%s，沙箱可用=%s）。",
                    request.name(), risk.label(), sandboxPolicy.policyFor(risk),
                    sandboxPolicy.isSandboxAvailable() ? "是" : "否");
        }

        // V2.1.0 B2/T2.1：策略要求沙箱 → 路由到子进程执行（SandboxRunner）
        if (sandboxPolicy != null && sandboxPolicy.requireSandbox(risk)) {
            if (sandboxRunner == null) {
                log.warn("[Sandbox] 策略要求沙箱但运行器未装配：tool={}", request.name());
                return String.format("工具 '%s' 因沙箱运行器未装配被拒绝执行（风险级=%s）。",
                        request.name(), risk.label());
            }
            Map<String, Object> sr = sandboxRunner.runTool(request.name(), request.arguments());
            String st = String.valueOf(sr.get("status"));
            if ("ok".equals(st)) {
                return String.valueOf(sr.get("result"));
            }
            log.warn("[Sandbox] 沙箱执行未成功：tool={}, status={}, reason={}",
                    request.name(), st, sr.get("reason"));
            return String.format("工具 '%s' 沙箱执行未成功（状态=%s，原因=%s）。",
                    request.name(), st, sr.get("reason"));
        }
        // V2.1.0 B4/T4.2：降级直执行——安全边界已降低，必须强告警（ERROR 级，不可静默）
        if (sandboxPolicy != null && sandboxPolicy.isDegradedDirect(risk)) {
            log.error("[Sandbox] 【安全降级】策略要求沙箱但沙箱不可用，已按 on-unavailable=allow-direct 直执行："
                    + "tool={}, risk={}, policy={}, 建议尽快恢复沙箱或改回 reject",
                    request.name(), risk.name(), sandboxPolicy.policyFor(risk));
        }
        long _t0 = System.currentTimeMillis();
        try {
            String _out = executor.execute(request, null);
            // T4.1：全量执行日志（工具名/耗时/结果长度）
            log.info("[Sandbox] 工具直执行完成：tool={}, risk={}, elapsedMs={}ms, outLen={}",
                    request.name(), risk.name(), System.currentTimeMillis() - _t0,
                    _out == null ? 0 : _out.length());
            return _out;
        } catch (Exception e) {
            log.error("工具执行异常：{}", request.name(), e);
            return String.format("工具 '%s' 执行异常：%s", request.name(), e.getMessage());
        }
    }

    /**
     * 工具是否存在
     */
    public boolean has(String name) {
        return specs.containsKey(name);
    }

    /**
     * 全部工具名
     */
    public Set<String> getToolNames() {
        return specs.keySet();
    }

    /**
     * 工具描述（审批网关展示用）
     */
    public String getDescription(String name) {
        dev.langchain4j.agent.tool.ToolSpecification spec = specs.get(name);
        return spec != null ? spec.description() : "";
    }


    /**
     * V1.1.0 C3/T3.3：直注册外部工具（MCP 桥接用——非 @Tool 注解 Bean，spec/executor 直接入表）
     */
    public void registerDirect(dev.langchain4j.agent.tool.ToolSpecification spec, ToolExecutor executor) {
        registerDirect(spec, executor, ToolRisk.EXTERNAL);
    }

    /**
     * V2.1.0 B1：直注册外部工具并可指定风险级（默认 EXTERNAL）。
     */
    public void registerDirect(dev.langchain4j.agent.tool.ToolSpecification spec, ToolExecutor executor, ToolRisk risk) {
        specs.put(spec.name(), spec);
        executors.put(spec.name(), executor);
        riskLevels.put(spec.name(), risk == null ? ToolRisk.EXTERNAL : risk);
        log.info("注册外部工具：{}（风险级={}）", spec.name(), riskLevels.get(spec.name()).label());

    }

    /**
     * V1.1.0 C3/T3.3：注销外部工具（MCP server 停用/删除时）
     */
    public void unregister(String toolName) {
        specs.remove(toolName);
        executors.remove(toolName);
        toolBeans.remove(toolName);
        riskLevels.remove(toolName);
        log.info("注销外部工具：{}", toolName);
    }

    /**
     * V2.1.0 B1：取工具风险级（未知工具按 SAFE 兜底，避免过度拦截）
     */
    public ToolRisk getRisk(String name) {
        ToolRisk r = riskLevels.get(name);
        return r == null ? ToolRisk.SAFE : r;
    }

    /** V2.1.0 B1：全部工具风险级快照（管理/排障用） */
    public Map<String, String> getAllRisks() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String n : specs.keySet()) {
            out.put(n, getRisk(n).name());
        }
        return out;
    }

    /** V2.1.0 B1：显式设置风险级（页面/配置可将某工具提级为 DANGEROUS） */
    public void setRisk(String toolName, ToolRisk risk) {
        if (risk != null && specs.containsKey(toolName)) {
            riskLevels.put(toolName, risk);
        }
    }

    /**
     * V2.1.0 B1：内置工具风险分级。
     * calculator 走脚本引擎 eval → DYNAMIC；其余内置 → SAFE。
     */
    private ToolRisk classifyBuiltin(String toolName) {
        if (toolName == null) {
            return ToolRisk.SAFE;
        }
        return "calculator".equals(toolName) ? ToolRisk.DYNAMIC : ToolRisk.SAFE;
    }

    private java.lang.reflect.Method findMethod(Object bean, String toolName) {
        for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
            dev.langchain4j.agent.tool.Tool ann = m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (ann != null) {
                // @Tool(value) 未显式指定 name 时，lc4j 用方法名作为工具名
                String name = ann.name().isEmpty() ? m.getName() : ann.name();
                if (name.equals(toolName)) {
                    return m;
                }
            }
        }
        throw new IllegalStateException("未找到 @Tool 方法：" + toolName);
    }
}
