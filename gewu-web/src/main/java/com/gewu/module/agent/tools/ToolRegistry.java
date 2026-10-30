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
        try {
            return executor.execute(request, null);
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
        specs.put(spec.name(), spec);
        executors.put(spec.name(), executor);
        log.info("注册外部工具：{}", spec.name());
    }

    /**
     * V1.1.0 C3/T3.3：注销外部工具（MCP server 停用/删除时）
     */
    public void unregister(String toolName) {
        specs.remove(toolName);
        executors.remove(toolName);
        toolBeans.remove(toolName);
        log.info("注销外部工具：{}", toolName);
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
