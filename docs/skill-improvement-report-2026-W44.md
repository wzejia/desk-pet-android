# /loop-skills-improve 偏差分析报告 — 2026-10-26（W44）

> 项目模式（E:\desk-pet-android）。距上次审计（全局 2026-09-24）32 天，本审计同时销掉
> SessionStart hook 的「知识审计超 7 天」提醒。本周主线 = S70 PIR 红外唤醒全链（调研→
> 引库→实现→评审→板测→触率修复→验收通过），全部记录通道（implementation-notes /
> DEV_LOG / CONSTRUCTION / solutions）当日闭环。

## 时间范围
2026-10-20 至 2026-10-26（W44）

## 偏差分析

### 本周新增（analyze_notes.py 实测，window 30d）

| 来源 | 计数 | 分类 |
|---|---|---|
| desk-pet-android implementation-notes.html | 本周 11 条（30d 窗口共 23） | 决策 5 / 有意偏差 4 / 观察 2 |

### 未决项账龄

| 条目 | 账龄 | 处置 |
|---|---|---|
| desk-pet-android | **open_items = 0（池干净）** | — |
| pomo-gpui 5 条（上周表，他项目） | >30 天 | 维持上周处置建议，留待该项目会话执行（本会话不越界） |

本周未决池新增 = 0；销案类动作（S32/S36 ⛔、76 板退役）全部当天带理由留痕，未产生新未决。

### 上周改动效果核对（闭环必填）

| 上周改动（2026-09-24 报告） | 结果 | 证据 / 后续 |
|---|---|---|
| analyze_notes.py 债务条目视作已决（CLOSED_HINTS） | ⏸ 无样本（顺延第 1 周） | 本周无债务类条目出现；再有 2 周无样本按降频规则处理 |
| analyze_notes.py worktree 副本去重 | ⏸ 降频维持 | 源头（adbot 树）不存在，维持「有样本才核对」 |
| loop-skills-improve SKILL.md 报告路径对齐 | ⏸ 无样本 | 本周为项目模式（走 `<project>/docs/` 路径），全局路径未被触发 |

### 观察项携带

| 观察项 | 本周 verdict | 后续 |
|---|---|---|
| vibeji/pet tr-table 老格式 age=-1 | 已关闭（09-24 定案） | 无复发，不再跟踪 |
| 子 agent 静默死亡（G6） | 关闭维持，**无复发** | 本周 7 个子智能体（2 调研+builder×3 轮+auditor+critic）全部正常返回。备注：builder 增补消息出现过一次「返回不含增补」——系 SendMessage 与报告组包的时序问题，非静默死亡，已在编排侧用文件系统核实兜住 |

### 高频偏差模式

无 ≥3 同类模式。两条单例但值得记账：
1. **「评审判无害的副作用」**（P3-5 板测证伪主凶）→ 已沉淀项目解法（见下）。
2. **MSYS export 不跨 Bash 调用**（08-06 同坑二刷）→ 已补记全局 errors.md。

## Solutions 腐烂抽查（本周点名任务）

| 抽查对象 | 结果 |
|---|---|
| 项目 solutions 全量 34 条（5 类）+ frontmatter 扫描 | ✓ 无腐烂条目；抽验 3 个代码引用全部存活（ChatViewModel.cancelCurrentTurn / ShelfMcpBridge.SYNCED_TOOLS:36 / VSM wake-ack 引用） |
| `workflow-issues/adb-run-as-prefs-write-must-verify.md` | ✓ **今日实战复用生效**（S70 板测 prefs 写入按其管道法执行+回读验证） |
| `workflow-issues/guard-comment-anchors-must-bind-tested-wiring.md`（10-21 新） | ✓ 本周 critic/builder 双向按其口径验证 S70 六锚 |

## Skill 改进清单（本周落地）

| 文件 | 改进点 | 内容 |
|---|---|---|
| `docs/solutions/logic-errors/swallowed-edge-arms-cooldown-state-change-on-dropped-paths.md` | 新增项目解法 | 被吞边沿起冷却表=丢弃路径状态变更副作用；评审判「无害」先问状态变更影响谁；advisory 是优先级不是重要性证伪；变异锚按缺陷形态设计 |
| `~/.claude/docs/errors.md`（MSYS 主题） | 复发补记 | `export MSYS2_ARG_CONV_EXCL` 不跨 Bash 工具调用延续——每次调用都要重设（08-06 同坑二刷实证） |
| docs-* 生成链六 Skill | 零触发，不动 | 本周 11 条偏差全部是工程决策/板测教训，无文档生成质量问题；notes div-card 格式被解析脚本正确计数（11/分类准确），格式健康 |

（S70 调研方法论——「先验事件通道是否存在再选集成路」双子智能体逆向分工——已由
`docs/research/2026-10-26-ir-wake/` 全文档承载，不另造 solutions 副本。）

## 下周重点

1. **验证生效**：新解法 `swallowed-edge-arms-cooldown` 的教训在下次评审场景被引用/生效即闭环。
2. **降频管理**：债务 CLOSED_HINTS 核对项再无样本 2 周即标降频。
3. **pomo-gpui 账龄池**：5 条处置仍待该项目会话（跨项目欠账，第 2 周顺延）。
4. 板 140 已成主测试机（76 退役），后续板测证据口径以 140 为准。

## 改进日志

### 2026-10-26: S70 触率根因沉淀为可迁移解法
- **改进内容**：新增 logic-errors 解法一条（丢弃路径状态变更副作用），含 M6 变异锚设计法。
- **触发原因**：板测触发率偏低根因（critic P3-5 advisory 板测证伪为主凶）。
- **预期效果**：后续评审对「丢弃/吞掉路径上的状态变更」类 finding 不再判无害了事；同类缺陷变异锚按缺陷形态设计。
