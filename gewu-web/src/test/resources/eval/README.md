# 评测集使用说明（LLM 评测最小闭环）

> 版本：V2.5.0 E1（评测确定性约束）
> 关联：`EvalSuiteTest` · `docs/plan/V2.5.0-PLAN.md`

## 一、为什么需要「确定性」

评测的价值在于**回归**：改 prompt、换模型、调 RAG topK 之后，能判断结果变好还是变坏。

这要求**同样的输入必须得到同样的输出**。一旦评测涉及 LLM 生成（工具选择、格式合规、
答案质量），采样随机性会让分数每次不同——此时「基线」失去意义，回归对比变成噪声。

因此本评测框架强制：

| 参数 | 默认值 | 覆盖方式 | 说明 |
|---|---|---|---|
| `temperature` | `0` | `EVAL_TEMPERATURE` | 0 = 贪心解码，输出确定 |
| `seed` | `42` | `EVAL_SEED` | 固定采样种子（厂商 API 支持时生效） |
| `model` | `calculator-tool` | `EVAL_MODEL` | 评测所用执行器/模型标识 |

> 当前评测用例走 `CalculatorTool`（工具路径，本身确定）。这三个参数是为**未来的
> LLM 生成质量评测**预留的——那类评测若不确定，回归基线毫无意义。

## 二、评测集格式

放 `src/test/resources/eval/*.yaml`，一个文件一个 suite：

```yaml
suite: calculator-basic
runner: calculator
description: 计算器答案正确性（20 条固定任务，确定性可复现）
cases:
  - id: calc-001
    category: arithmetic
    input: "1234*5678"
    expect: { type: exact, value: "7006652" }
  - id: calc-004
    category: float
    input: "(100 - 25) * 0.8"
    expect: { type: prefix, value: "60" }
    note: 75*0.8=60.00000000000001 是二进制浮点真相，用前缀断言
```

断言类型：

| type | 语义 |
|---|---|
| `exact` | 结果必须完全相等 |
| `prefix` | 结果必须以给定值开头（适合浮点、带后缀的自然语言回答） |

## 三、常用命令

```bash
# 常规执行（打印 EVAL-ENV / EVAL-SUMMARY）
mvn test -pl gewu-web -Dtest=EvalSuiteTest

# 写出基线（供后续对比）
EVAL_BASELINE_OUT=./eval-baseline.json mvn test -pl gewu-web -Dtest=EvalSuiteTest

# 用基线对比：结果不一致即失败（回退会被抓出来）
BASELINE_FILE=./eval-baseline.json mvn test -pl gewu-web -Dtest=EvalSuiteTest

# 覆盖确定性参数（谨慎：破坏可复现性，仅用于探查模型波动）
EVAL_TEMPERATURE=0.7 EVAL_SEED=7 mvn test -pl gewu-web -Dtest=EvalSuiteTest
```

Windows PowerShell 里设置环境变量：`$env:BASELINE_FILE='...'` 后再跑 `mvn`。

## 四、报告字段

| 输出标记 | 含义 |
|---|---|
| `[EVAL-ENV]` | 本次运行的确定性元信息（temperature / seed / model） |
| `[EVAL-FAIL]` | 单条失败明细（id / input / 期望 / 实际） |
| `[EVAL-SUMMARY]` | 汇总（suite 数 / 总数 / 通过 / 失败） |
| `[EVAL-BASELINE-OUT]` | 基线已写出及其路径与条数 |
| `[EVAL-BASELINE]` | 基线对比结果（对比条数 + 元信息） |
| `[EVAL-BASELINE-REGRESS]` | 发现回退的条目清单 |

## 五、设计口径

1. **报告型而非断路型**：单条用例失败不中断其余用例——评测的第一职责是「看清」，
   其次才是「拦住」。
2. **空跑必须失败**：评测目录为空或缺失时立即报错，防止「0 条用例全绿」的假阳性。
3. **基线对比抓回退**：条目缺失（基线有、当前未执行）和结果变化都算回退。
4. **CI 不跑评测**：CI 保留 `-DskipTests`（2026-11-02 拍板），评测由本地手动触发。
   原因是评测依赖真实模型调用，成本与耗时不适合每次 PR 都跑。

## 六、新增评测集检查清单

- [ ] `suite` 名称唯一，`cases[].id` 在 suite 内唯一且递增
- [ ] 每条 case 有 `expect.type` 与 `expect.value`
- [ ] 浮点/带后缀结果用 `prefix`，精确字符串用 `exact`
- [ ] 用例总数 ≥ 20（`评测目录存在且非空` 测试会校验）
- [ ] 取材优先复用 `llm_call_log` 的真实样本（贴近真实使用）
- [ ] 若断言值与直觉不符（如 `60.00000000000001`），在 `note` 里写明原因
