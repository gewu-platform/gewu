# AGENTS.md - Gewu（格物）项目约定

> 面向在本仓库工作的 AI 代理与开发者。此处只放本项目约定；个人工作区通用规则不放在这里。

## 文档用语规范

- 规划与决策类文档（`docs/plan/`、`docs/decisions/`、ROADMAP 等）使用中性专业表述：
  - 允许：「需求来源」「评估与取舍」「决策记录」（附日期与结论）
  - 禁止：「竞品取舍」「老板需求」「老板裁定」等口语化、指向具体个人的表述
- 新内容一律遵守；修改旧段落时顺带修正存量违规。

## 提交前检查（文档同步）

每次提交前核对 `/docs` 相关文档是否已同步，文档滞后视为提交不合格：

| 变更类型 | 同步目标 |
|---|---|
| 新增/变更接口 | `docs/API.md`（保证与代码双向一致） |
| 新增组件或分层 | `docs/ARCHITECTURE.md` |
| 新增配置项/环境变量/部署依赖 | `docs/DEPLOY.md` |
| 新增扩展机制 | `docs/EXTEND-TOOL.md` |
| 版本规划 / 专题决策 | `docs/plan/` / `docs/decisions/` |

## 提交规范

- Commit / tag message 使用中文；通过 UTF-8 无 BOM 文件 + `git commit -F` 写入
  （规避控制台编码问题），以 `git cat-file` 字节级核验为准
- 提交前两轮复核：第一轮核 diff 与实现（改动范围、关键逻辑、边界）；
  第二轮核风险（敏感残留、异常路径、与既有语义冲突、可回滚）
- 改动完成后不自动提交，经确认后再提交

## 构建与测试

- JDK 17 + Maven 3.9+；`mvn -o test` 为回归安全网（当前 77 用例，须保持全绿）
- 数据库变更走 `database/migrations/`（Flyway 增量），并同步 `database/gewu-init.sql`
  全量快照——契约详见 `docs/ARCHITECTURE.md`「双轨契约」
- 前端仓库（gewu-web）独立提交，注释级改动同样遵循上述提交规范
