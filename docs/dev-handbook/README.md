# 小朵 Android 开发手册（dev-handbook）

> 一份就够的上手指引：环境怎么搭、M1 怎么实现的、踩过哪些坑、M2-M4 要做什么。
> 整理日期：2026-07-29（M1 收口）。维护纪律：每完成一个里程碑回来更新对应文件。
> 另：`docs/MASTER-PLAN.md` 是仓库级总纲入口，与本手册互补（总纲偏决策总账，手册偏工程实操）。

## 文件导航

### 工程实操（01-05，M1 执行会话整理）

| 文件 | 内容 | 什么时候读 |
|---|---|---|
| [01-environment-build.md](01-environment-build.md) | 环境实测定案、安装步骤、构建命令、代理/路径/ColorOS 的坑 | 新机器装环境、构建失败时 |
| [02-m1-as-built.md](02-m1-as-built.md) | M1 实际建成的架构、代码地图、设计偏差 D1-D5、验收证据索引 | 改 M1 代码、写 M2 前 |
| [03-decisions-and-contracts.md](03-decisions-and-contracts.md) | KD/KTD/R1-R14/AE1-AE6 状态表、ticket 状态 | 想改方向/加需求前必查 |
| [04-roadmap-m2-m4.md](04-roadmap-m2-m4.md) | M1 增补单元缺口（U1-U6）、M2/M3/M4 技术要点、未决问题汇总 | 开工新里程碑前 |
| [05-pitfalls-and-research.md](05-pitfalls-and-research.md) | 55 条踩坑 Android 浓缩版、ROM 权限跳转表、sherpa-onnx 调研 | 写网络/语音/权限代码前 |

### 决策与计划书（06-10，grilling 会话整理）

| 文件 | 内容 | 什么时候读 |
|---|---|---|
| [06-grilling-decisions-and-deviations.md](06-grilling-decisions-and-deviations.md) | **最新用户拍板**（决策 1-10：长按菜单/拖拽播 running/窗口 208/日志方案）+ 偏差 A-D 待裁决 | M1 补齐前第一件事 |
| [07-m1-acceptance-audit.md](07-m1-acceptance-audit.md) | M1 验收 9 条对照（5 绿 4 缺口），源码结构表 | 核对 M1 还差什么 |
| [08-m2-plan.md](08-m2-plan.md) | M2 详细计划书 | M2 开工 |
| [09-m3-m4-plan.md](09-m3-m4-plan.md) | M3/M4 详细计划书 | M3/M4 开工 |
| [10-windows-env-addendum.md](10-windows-env-addendum.md) | 环境约束补遗（01/05 未覆盖项） | 环境异常时 |
| [11-grilling-2026-07-30-crosscheck.md](11-grilling-2026-07-30-crosscheck.md) | 第三场 grilling（07-30）Q&A 全录 + 三层决策冲突 C1-C4（待裁决）+ 8 项默认对账 + 复核结论 | 裁决偏差 A-D 时一并看 |

## ⚠️ 两套文件的口径关系（重要）

- **06 的 grilling 决策是 M1 交互细节的最新权威**（晚于 M1 建成）：它判定已建成 M1 有
  **偏差 A-D**（A 窗口 288→应 208 / B 缺长按菜单 / C 缺拖拽跑步动画 / D 日志缺滚动+导出），
  **✅ 2026-07-30 已全部裁决并收口**（A 改 208 实施 / B、C 随 11 的 C2-C3 确认追认关闭 / D 补做滚动+导出）。
- 02 的偏差 D1-D5 是 M1 执行时的自述偏差；其中 D2 与 06 的偏差 A 是同一件事（窗口高度），
  以 06 的裁决流程为准。
- 04 的 U1-U6 缺口与 06/07 的偏差 B/C/D 互补：U 系列是 plan 单元，偏差是 grilling 验收口径。
- 11 是第三场 grilling（07-30，信息不全下进行）的交叉检验：不改变上述层级；其 C2/C3 已获用户确认，
  直接关闭了偏差 B/C（追认现状）。

## 权威文档层级（冲突时以上层为准）

> **【2026-08-03 注】本手册为 M1 时期工程手册**。助手化（S0-S8）已全收口（2026-08-06），
> 现行层级以根目录 `CLAUDE.md` 为准（产品行为=plan 002）；下文层级为 M1 时点快照。

1. **产品行为**：`docs/plans/2026-07-29-001-feat-xiaoduo-android-app-plan.md`（用户已确认）；
   M1 交互细节以 06 的 grilling 决策 1-10 为最新补充
2. **协议/语音参数**：桌面端仓库 `docs/android-dev-brief.md`（只读；镜像在 `docs/m1-dev-kit/`）
3. **平台对接细节**：桌面端 `docs/PLATFORM_INTEGRATION.md`（镜像同上）
4. **帧动画参数**：桌面端 `pet/pet_states.json`（M1 已对账一致）
5. **本手册**：工程实践与偏差记录

## 30 秒快速开始

```bash
export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH"
cd /e/desk-pet-android
./gradlew.bat assembleDebug testDebugUnitTest
/c/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

## 当前状态速查

- **助手化 S0-S8 已全收口**（2026-08-06，现行状态）：基线 358 单测全绿 skipped=0；
  进度/接手见根目录 `PROGRESS.md`，机构解法见 `docs/solutions/`，
  周度审计见 `docs/skill-improvement-report-2026-W31.md`。
- **M1 已完成并收口**（2026-07-30）：偏差 A-D 全部裁决——A 窗口改 208 已实施取证、
  B/C 随用户确认 07-30 交互集修订决策 4/7 追认关闭、D 日志滚动+导出已补做取证；
  U1 契约测试已立（pet_states.json 进 assets，5 条全绿）。验收 9 条按裁决后口径全绿（详见 07）。
  M1 剩余 U2-U6 增补单元已由助手化切片吸收交付（详见 04 注记）。
- 真机：OPPO PJD110（ColorOS，1440×3168 @640dpi），adb serial `de61cd8`。
