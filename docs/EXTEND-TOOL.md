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

建议从 `DateTimeTool` 抄起——20 行就是一个可用工具。

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
