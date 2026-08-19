---
title: Android 12+ 强制 NOT_TOUCHABLE 悬浮窗 alpha≤0.8（宠物半透明「幽灵化」）
date: 2026-07-30
category: ui-bugs
module: PetOverlayService 悬浮窗
problem_type: ui_bug
component: assistant
symptoms:
  - 悬浮窗宠物以约 80% 不透明度渲染，图标文字透体可见（「鬼魂感」）
  - logcat 报 WindowManager: "with FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80 to let touches pass through"
  - dumpsys 该窗口显示 alpha=0.8，但代码从未设置过 alpha
root_cause: config_error
resolution_type: code_fix
severity: high
tags: [overlay, not-touchable, android-12, alpha, windowmanager, 悬浮窗]
---

# Android 12+ 强制 NOT_TOUCHABLE 悬浮窗 alpha≤0.8

## Problem
双窗口悬浮窗设计（PetView 渲染在 `FLAG_NOT_TOUCHABLE` 的显示层）在 Android 12+ 真机上
宠物必然以 80% 不透明度渲染——框架级强制，代码无法覆盖，视觉盲测直接输参照物。

## Symptoms
- 宠物半透明，底层 Launcher 图标文字透体可见（截图肉眼可判）
- logcat 原文：`WindowManager: App com.xiaoduo.deskpet has a system alert window (type = 2038)
  with FLAG_NOT_TOUCHABLE and LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80 to let
  touches pass through`
- dumpsys 窗口 attrs 里 `alpha=0.8`，代码侧无任何 alpha 设置

## What Didn't Work
- 以为是 ColorOS ROM 行为或旧构建残留——清除重装后复现，查实是 Android 12+ 框架
  （不受信任触摸限制的配套惩罚）对 NOT_TOUCHABLE 系统警告窗的统一行为，与 ROM 无关。

## Solution
窗口职责翻转：**需要全不透明渲染的内容（宠物本体）放可触交互层（alpha 1.0，框架不压），
NOT_TOUCHABLE 显示层只放本来就不透明的内容无所谓的部件（气泡，0.92 半透底×0.8 惩罚后仍可读）**。
技术前提先验证：spritesheet 帧定长 192x208，动画本无溢出——「大显示层防溢出」的设计目标
不存在，翻转零代价。翻转后 dumpsys：交互层 flags 无 NOT_TOUCHABLE 无 alpha 惩罚；
显示层 NOT_TOUCHABLE 且 alpha=0.8（像素采样证实只落显示层）。

## Why This Works
Android 12（API 31）起，系统对 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` 且
alpha>0.8 的窗口强制降到 0.8，让用户能看穿「可能遮挡下层应用的透明覆盖层」。
这是平台安全设计，不是 bug，没有任何应用侧开关。设计只能绕：让透明度敏感的像素
永远落在可触窗口里。

## Prevention
- 设计 overlay 窗口分层时，第一问就是「这层需要 alpha 1.0 吗」——需要就不能 NOT_TOUCHABLE。
- 悬浮窗视觉验收必须真机截图亲看（本问题单测/dumpsys flags 都不显形，alpha=0.8 在
  dumpsys attrs 里才可见，渲染效果只有截图能判）。
- 任何「系统应该不会管吧」的窗口 flag 假设，先写最小 demo 真机验证再展开架构。
