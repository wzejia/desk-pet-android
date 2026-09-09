---
title: 1x 密度板子 touchSlop=12px≈1.7mm 真人按压漂移必超致 tap 被吞——点按判定与拖拽启动阈值解耦（净位移判据）
date: 2026-09-09
category: ui-bugs
module: app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt
problem_type: ui_bug
component: overlay
symptoms:
  - 悬浮窗双击打断板子实测无效（模拟器/注入验证通过）
  - 板子注入点击日志正常（clicked no-op）但双击判定永不触发
  - 双击时宠物位置被轻微拖动（漂移被当拖拽落盘）
root_cause: platform_density_trap
resolution_type: code_fix
severity: high
tags: [touch-slop, density-160, tap-gesture, double-tap, drag-misjudge, injected-input-blindspot, rk3568]
---

# 现象

悬浮窗双击打断（S28）板子实测无效。注入双击（并行 input tap，UP 间隔 23ms）能触发判定；
真人双击（含模拟器鼠标）无效，且宠物偶发被"挪走"。

# 根因（两个事实咬合）

1. **板子 `wm density=160`（1x，dp=px）**：拖拽启动阈值 `touchSlop = ViewConfiguration
   * 0.5 * density`（项目手写 12*dp）= **12 物理像素 ≈ 物理尺寸上约 1.7mm**（10 寸屏）。
   真人按压时手指触点自然漂移 2-3mm，**必然超过** → 手势被误置 `dragging=true`。
2. **手势机结构**：`ACTION_UP` 分支 `if (dragging) {...拖拽吸附...} else if (UP) {...tap
   判定...}`——dragging 被误置后 tap 记录（`lastTapUpAt`）**永不更新**，双击判定数据源恒空。

注入 input tap 没有 MOVE 事件 → 恒 `dragging=false` → 只有注入能通过。**模拟器/注入
验证环境掩盖缺陷**——这是「验证通过但真人不可用」的完整解释链。

修前板子基线复现：35px 漂移 swipe → `drag start` + `drag end, position persisted`
（tap 被吞 + 位置被落盘）。

# 修复

点按判定与拖拽启动阈值**解耦**——新增纯对象 `TapGesture`：

- `isTap(driftX, driftY)`：UP **净位移（欧氏）≤ 32px**（1x 密度 ≈5mm）视为点按，即使
  dragging 已误置也按 tap 处理，并 `moveTo(startX, startY)` 回弹微动（不落盘位置）；
- >32px 才是真拖拽，走原吸附/落盘；
- 双击窗口同时放宽 300ms→400ms（系统 DoubleTapTimeout 对真人 UP-UP 间隔偏紧）。

TapGestureTest 6 测锁定（零位移/漂移级/阈值边界/超界/欧氏对角/常量 sanity）。

# 板子三组复验

| 用例 | 修前 | 修后 |
|---|---|---|
| 漂移单按 21px | drag start，tap 吞 | 按 tap 处理+回弹 ✓ |
| 漂移双击（UP 间隔 215ms） | 永不触发 | double tap → cancelCurrentTurn ✓ |
| 真拖拽 450px | 正常 | 正常（回归无破坏）✓ |

# 教训

1. **低密度大屏设备上 12dp 类阈值要先换算物理尺寸**（1x 密度 12px=1.7mm，手指精度外）。
2. **注入验证无法覆盖带 MOVE 的真人手势**——漂移类手势 bug 必须 `input swipe`（带位移）
   或真人补验；`input tap` 零 MOVE 是盲区。
3. 板子手势测试前必须 `dumpsys window` 取窗口**实时位置**（用户可能拖动过宠物），
   不能沿用历史坐标——本轮排查曾因测点错位白跑两轮。
