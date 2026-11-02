package com.gewu.module.agent.sandbox;

import com.gewu.module.agent.tools.ToolRisk;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.4.0 C1/T1.1：datetime 的沙箱适配器（demo 第二个适配器，验证「新增工具零改 Runner」）
 *
 * <p>datetime 是纯只读工具（取当前时间），无外部副作用；在沙箱内以 JS 任务形式执行
 * java.time 格式化——注意：沙箱 worker 只加载 Nashorn，此处的时间值由<b>父进程</b>
 * 计算后作为常量注入表达式，避免子进程依赖 java.time 语义与宿主时区差异。</p>
 *
 * <p>设计取舍：若让子进程自己取时间，会出现父子时区/时钟偏差；父进程取好后注入常量
 * 可保证与直执行路径结果语义一致（都是「调用时刻」）。</p>
 */
@Component
public class DateTimeSandboxAdapter implements SandboxAdapter {

    @Override
    public String toolName() {
        return "datetime";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.SAFE;
    }

    @Override
    public boolean normalized() {
        return true;
    }

    @Override
    public Map<String, String> prepare(String argumentsJson) {
        // 父进程取时间（与直执行路径一致的「调用时刻」语义），注入为字符串常量
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, String> task = new LinkedHashMap<>();
        task.put("type", "js");
        // 用 JS 字符串原样返回：表达式本身是带引号的字符串字面量
        task.put("code", "'" + now.replace("'", "\\'") + "'");
        return task;
    }
}
