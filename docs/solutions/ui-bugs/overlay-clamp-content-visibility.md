---
title: 悬浮窗位置 clamp 语义——「窗体留 margin」≠「内容可见」，应以内容中心钳屏内
date: 2026-08-03
category: ui-bugs
module: PetOverlayService 悬浮窗 / OverlayClamp
problem_type: ui_bug
component: assistant
symptoms:
  - adb wm size 切到 720x1600 后，桌面宠物完全不可见（持久化的绝对 px 位置整体出屏）
  - 第一轮修复（窗体每轴留 margin 把手）后宠物依然不可见——把手落在窗内透明区，
    精灵区像素均值 43=纯壁纸色
root_cause: logic_error
resolution_type: code_fix
severity: medium
tags: [overlay, clamp, windowmanager, wm-size, 分屏, 折叠屏, 悬浮窗]
---

# 悬浮窗位置 clamp：窗体在屏内 ≠ 窗内内容可见

## Problem

悬浮窗位置以绝对 px 持久化，显示尺寸变化（`wm size`/分屏/折叠屏）后旧坐标可能整体出屏，
宠物「消失」。修复时的语义陷阱：clamp 以**窗体**为对象保留 margin 把手区，但窗体比内容
大且内容居中、两侧透明时，屏内那一条 margin 恰好全是透明区——窗在屏内，内容照样不可见。

## Symptoms

- `wm size 720x1600`：交互窗 (540,1414) 617x668，精灵图像（窗内居中，约 x∈[648,1048]）
  完全出屏，桌面看不到宠物
- 第一轮修「窗留 margin」后：窗被拉回 (528,1328)，但精灵本体 x∈[653,1018] 仍出屏；
  精灵区（屏内段）像素均值 43 ≈ 壁纸亮度，肉眼不可见

## What Didn't Work

- **窗体 margin clamp**（每轴保留 ~48dp 把手）：语义上保证「窗体可触控」，但把手落在
  透明区时用户什么都看不见，等于没修。教训：**clamp 的对象必须是「内容的可视区」，
  不是「窗体的几何边」**。
- 仅凭代码评审通过即收口：critic 评代码正确性过，但编排层真机像素复验打回——
  「可见性」类验收必须有像素级断言，不能只看窗口坐标变化。

## Solution

三件套（本仓 commit cc477c7）：

1. **clamp 语义 = 窗体中心（=内容中心）每轴钳在屏内**
   （`app/src/main/java/com/xiaoduo/deskpet/pet/OverlayClamp.kt:30`）：

```kotlin
private fun clampAxis(pos: Int, win: Int, screen: Int): Int {
    if (win >= screen) return (screen - win) / 2 // 窗比屏大居中兜底
    return (pos + win / 2).coerceIn(0, screen) - win / 2 // 窗中心钳在屏内
}
```

2. **显示尺寸变化时重 clamp**：`PetOverlayService.onConfigurationChanged`（:107，Service 经
   ComponentCallbacks2 必收回调，无需 manifest configChanges）按当前 displayMetrics 重算，
   交互窗 clamp + `moveTo` 联动气泡窗重钳，双双 `updateViewLayout` 真下发。
3. **clamp 结果不落盘**：落盘值保留用户最后拖拽意图；临时尺寸变化（折叠展开又收起）
   不篡改原位置。所有恢复路径（启动 initialPosition、运行时 config change）都再 clamp。

效果：720 屏上 (540,1414)→(412,1266)，精灵半身可见可辨可拖（像素均值 43→126.5）。

## Why This Works

内容在窗内居中时「窗中心 = 内容中心」，把中心钳进屏内即保证内容至少半身可见，
且可见侧含内容中心区（可辨度最高的部分），窗口一半在屏内可拖——从语义上根除
「把手落透明区」。固有副作用（已接受）：拖拽自由度同步收紧为最多半身出屏——
方向上与「陪伴宠物不应可被藏没」一致。

## Prevention

- 悬浮窗/拖动类 UI 的 clamp、碰撞、可见性逻辑，断言对象一律用「内容可视区」
  （本例=内容中心点），不要用窗体几何边近似——窗内透明边距会让两者不等价。
- 「可见性」验收模板：dumpsys 窗口坐标 + 目标区域像素均值（壁纸基线 vs 内容出现后）
  双重取证；坐标对不等于看得见。
- 测试改写票（语义变更导致一批旧测试改期望值）必须防弱化：逐条对账旧断言是否仍带
  精确值、变异实验（钳制上界 +100 → 中心语义测应变红）坐实新测试有咬合。
