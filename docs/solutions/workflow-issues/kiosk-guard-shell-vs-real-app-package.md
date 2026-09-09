---
title: kiosk 设备看护壳陷阱——跳转目标包名凭印象猜错包，真主应用要从 dumpsys activity top 实证
date: 2026-09-09
category: workflow-issues
module: app/src/main/java/com/xiaoduo/deskpet/util/AppLauncher.kt
problem_type: workflow_issue
component: app-launcher
symptoms:
  - 跳转密集架 FAIL（ok=false / toast 未找到应用）
  - getLaunchIntentForPackage 恒 null（monkey 报 No activities found）
  - am start 显式启动后 StartActivity 立刻 paused+visible=false 自毁
root_cause: assumed_package_topology
resolution_type: code_fix_plus_method
severity: medium
tags: [kiosk, guard-service, launcher-intent, dumpsys, system-uid, rk3568, dense-shelf]
---

# 现象

S27/S31 跳转密集架：以 `com.ys.ys_receiver` 为目标包（此前 dumpsys package 见过它有
MAIN filter + 板子上它负责看护拉起密集架），三种症状叠加：

1. `getLaunchIntentForPackage` 恒 null——其 StartActivity filter **无 LAUNCHER
   category**（monkey 报 No activities found）；
2. `am start -n` 显式启动被系统接受，但 StartActivity **起来立刻 paused+visible=false
   自毁**（translucent 过渡壳，缺看护触发条件不落地）；
3. 真主界面从未出现。

# 拓扑真相（板子实证）

| 包 | 角色 | uid |
|---|---|---|
| `com.ys.ys_receiver` | **看护壳**（GuardService 拉密集架）+ 无 LAUNCHER 的 StartActivity | system(1000) |
| `com.yinduo.compactShelf` | **密集架真 UI 主应用**（.view.main.MainActivity，标准 LAUNCHER 入口） | 用户 uid |
| `com.softwinner.launcher` | 板子桌面（三者互不相同） | — |

kiosk 设备「看护壳」与「真主应用」常**不同包**：凭包名片段猜测（"ys"像厂商名）会踩空。

# 修复与方法

- `TARGET_PACKAGE` → `com.yinduo.compactShelf`（monkey 实证其 LAUNCHER 可启动 →
  `getLaunchIntentForPackage` 路径可用）；板子端到端复验：点导航行中键 → 前台切
  `compactShelf/.view.main.MainActivity` + 日志 ok=true。
- S31 顺带做成设置页可配（`KEY_JUMP_PACKAGE`，空=默认密集架）——kiosk 现场换目标
  不用重发包。

# 方法论（可复用排查序列）

1. `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`——报 No activities 即
   无桌面入口，`getLaunchIntentForPackage` 必 null；
2. `dumpsys activity top`——**列全系统常驻 Activity**，从中找目标应用的真实 UI 包
   （本例就此揪出 yinduo 包）；
3. `dumpsys package <pkg>` 看 filter 的 category 完整列表（有 MAIN 无 LAUNCHER 的
   是壳/过渡页典型特征）；
4. 板子上验证点击类交互前先 `dumpsys activity activities | grep resumed` 确认前台
   =目标 App——GuardService 20-60s 周期抢前台会把点击吞到别的界面。
