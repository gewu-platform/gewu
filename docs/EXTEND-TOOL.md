# Gewu 工具扩展指南（T7）

> 面向二次开发者：如何给 Gewu Agent 写一个新工具。全程 Spring Bean + LangChain4j `@Tool` 注解，无需改注册中心代码。

## 快速开始（3 步）

### 1. 写工具类

在 `gewu-web/src/main/java/com/gewu/module/agent/tools/` 下新建类，加 `@Component`（会被自动扫描注册），方法加 `@Tool`：

```java
package com.gewu.module.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WeatherTool {

    @Tool(name = "weather_query",
          value = "查询指定城市当前天气。当用户询问天气、气温、是否下雨等问题时使用。")
    public String query(@P("城市名，例如：上海") String city) {
        log.debug("weather_query 执行: city={}", city);
        // 你的业务逻辑：HTTP 调用 / DB 查询 / 内部服务……
        return "上海 晴 26°C";
    }
}
```

**注解要点**：

| 注解 | 作用 | 二开注意 |
|---|---|---|
| `@Tool(name, value)` | `name` 是 LLM 看到的工具名；`value` 是工具描述 | `value` 写清楚「什么时候用」，直接决定模型是否会选中它 |
| `@P("说明")` | 参数描述 | 参数类型支持 String/int/long/double/boolean/枚举；复杂对象用 JSON 字符串参数自行解析 |
| 返回值 | `String` 直接成为工具观察结果（`tool_result` 事件） | 返回内容是给 LLM 看的，控制在几百字符内，超长先摘要 |

### 2. 重启（或热重载）

启动时 `ToolRegistry` 自动扫描并注册全部 `@Tool` 方法。日志出现：

```
注册 Agent 工具：[weather_query, datetime, ...]
```

定义级绑定的工具集变更后，调用 `POST /api/agent/definitions/reload` 热重载（无需重启）。

### 3. 验证

- `GET /api/agent/tools` 能看到新工具规格；
- 对话中触发：「现在上海天气怎么样」→ SSE 流应依次出现 `action(toolName=weather_query)` → `tool_result` → `done`。

---

## 注册机制（原理）

`ToolRegistry`（`module/agent/tools/ToolRegistry.java`）持有三张表：

- `toolBeans`：工具实例（`@Tool` 扫描源）
- `specs`：`ToolSpecification`（LLM 可见的工具规格）
- `executors`：`DefaultToolExecutor`（反射调用，与 lc4j AiServices 内部同款行为）

`register(Object toolBean)` 用 `ToolSpecifications.toolSpecificationsFrom(bean)` 反射提取全部 `@Tool` 方法——**一个 Bean 可以有多个 @Tool 方法**（每个方法独立注册）。

Agent 运行时按定义的 `toolIds` 取规格（`getSpecifications(ids)`）；未指定 agentId 的对话带全量工具（`getAllSpecifications()`）。

## 现有内置工具（参考实现）

| 工具 | 类 | 说明 |
|---|---|---|
| `datetime` | `DateTimeTool` | 当前日期时间（最简模板：无参 @Tool） |
| `calculator` | `CalculatorTool` | 计算器 |
| `search` | `SearchTool` | 搜索 |
| `timezone` | `TimeZoneTool` | 时区换算 |

> **沙箱适配现状（V2.4.0）**：`calculator` 与 `datetime` 已提供 `SandboxAdapter`，
> 可被提级为 `DYNAMIC`/`EXTERNAL` 后在沙箱子进程内执行；其余内置工具未提供适配器，
> 若被提级到需沙箱的级别会 fail-closed 拒绝（提示「暂无沙箱适配器」）。

建议从 `DateTimeTool` 抄起——20 行就是一个可用工具。

## 让工具支持沙箱（V2.4.0）

工具默认走**直执行**。若工具含动态求值或来自外部，会被归为需沙箱的级别；
此时需要提供 `SandboxAdapter`，否则调用会 fail-closed 拒绝。

### 两步接入

**第 1 步：实现 `SandboxAdapter`**（放 `module/agent/sandbox/`）

```java
@Component
public class MyToolSandboxAdapter implements SandboxAdapter {

    @Override
    public String toolName() {
        return "my_tool";              // 与 @Tool 的 name 一致
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.DYNAMIC;       // 该适配器承载的风险级（文档/自检用）
    }

    @Override
    public Map<String, String> prepare(String argumentsJson) {
        // 入参 → 沙箱任务；返回 null 表示不可执行（Runner 会 fail-closed）
        // 返回 {"type":"error","code":"原因"} 可携带自定义错误文案
        return Map.of("type", "js", "code", "1+1");
    }

    @Override
    public boolean normalized() {
        return false;                  // 是否需要数值归一（整数浮点尾/科学计数法）
    }
}
```

**第 2 步：无需改任何核心类**——`SandboxRunner` 构造时注入全部 `SandboxAdapter` Bean，
按 `toolName()` 建注册表并查表派发。启动日志会打印注册情况：

```
[Sandbox] 适配器注册表初始化：2 个 → [calculator, datetime]
```

### 接口契约

| 方法 | 必须 | 说明 |
|---|---|---|
| `toolName()` | 是 | 与 `@Tool(name=...)` 一致 |
| `prepare(argumentsJson)` | 是 | 入参 → 沙箱任务；必须自行做入参校验（与直执行路径同源） |
| `risk()` | 否 | 默认 `DYNAMIC` |
| `normalized()` | 否 | 默认 false；计算类返回 true 可归一 `.0` 尾与科学计数法 |

### 任务协议

`prepare` 返回的 Map 必须含 `type` 与 `code`：

| type | 含义 |
|---|---|
| `js` | 交给子进程 Nashorn 求值（受表达式白名单约束） |
| `error` | 直接作为错误返回（`code` 即错误文案） |

> 当前沙箱只支持 `js` 任务类型。若你的工具需要非 JS 语义，可在 `prepare` 内
> 把计算前置到父进程完成，再把结果作为字面量注入 JS 表达式（参考 `DateTimeSandboxAdapter`：
> 时间由父进程取好后作为字符串常量注入，避免子进程时区差异）。

### 安全要求

1. **入参必须校验**：沙箱路径与直执行路径必须同源防护，不能只在一侧校验
2. **不要绕过白名单**：把用户输入直接拼进 `code` 前，必须过 `validateExpression`
3. **不传密钥进沙箱**：子进程不该拿到凭据；确需外部调用时把结果在父进程取好后传入

---

## 工具风险分级（V2.1.0 / V2.4.0）

每个工具都有风险级，决定「进来后怎么执行」。

| 级别 | 何时使用 | 默认执行策略 |
|---|---|---|
| `SAFE` | 纯只读、纯计算、无副作用 | `direct` |
| `DYNAMIC` | 含动态求值（脚本引擎、模板渲染） | `direct` |
| `EXTERNAL` | 来自外部服务（MCP 桥接） | `direct`（V2.2.0 起，有界执行） |
| `DANGEROUS` | 写文件、执行命令等高危能力 | `sandbox` + 无条件需审批 |

### 内置工具如何标注

`ToolRegistry.classifyBuiltin(toolName)` 决定内置工具风险级：

```java
private ToolRisk classifyBuiltin(String toolName) {
    if (toolName == null) {
        return ToolRisk.SAFE;
    }
    return "calculator".equals(toolName) ? ToolRisk.DYNAMIC : ToolRisk.SAFE;
}
```

**新增工具若含动态求值，必须在此处补一行**，否则会被误标为 `SAFE`。

### 运行时调整（无需改代码）

```bash
# 查看全部工具风险级与当前策略
GET /api/settings/tool-risks

# 提级某工具（ADMIN）
POST /api/settings/tool-risks/my_tool
{"risk": "DANGEROUS"}
```

**提级后的行为取决于策略**（`gewu.sandbox.policy.*`）：

- 策略 `sandbox` + 沙箱可用 → 走沙箱子进程
- 策略 `sandbox` + 沙箱不可用 → **默认拒绝**（fail-closed），除非配 `on-unavailable=allow-direct`
- 策略 `direct` → 直执行
- 策略 `disabled` → 禁止调用

详见 [ARCHITECTURE.md](./ARCHITECTURE.md)「沙箱与风险分级」与 [DEPLOY.md](./DEPLOY.md) `GEWU_SANDBOX_*`。

---
## 审批管控（P8b）

工具默认可能触发审批流。两级策略：

- **工具级默认**：工具执行的审批行为由编排层默认策略控制；
- **定义级覆盖**：Agent 定义 `config.requireApproval` 三态：`null`=继承工具级默认 / `true`=全部工具需审批 / `false`=免审批。

需要审批时 SSE 流发 `approval_required`（带 `toolCallId`），前端弹审批卡，管理员走 `POST /api/agent/approval/approve|reject`；等待期服务端每 15s 发 `ping` 心跳保活。

## 最佳实践

1. **幂等与超时**：工具在 LLM 循环线程同步执行，外部调用务必设超时（推荐 10s 内），慢任务改为「提交任务 + 查询状态」两个工具。
2. **异常即文案**：抛异常会中断迭代循环；预期内失败（如查无此城市）直接 `return "未查询到..."`，让模型自行向用户解释。
3. **注入 Spring Bean**：工具类就是普通 `@Component`，直接构造器注入你的 Service/Mapper。
4. **命名空间**：工具名全局唯一（`specs` 以 name 为 key），二开工具建议加前缀（如 `acme_`）避免与内置冲突。
5. **测试**：单测直接 new 实例调方法；链路验证用 `/api/agent/chat/simple` 带上问题观察 `tool_result`。

## 下一步

- API 全表：`docs/API.md`
- 架构总览：`docs/ARCHITECTURE.md`
- MCP 接入：`docs/MCP.md`
- 插件仓库：`docs/PLUGIN.md`
