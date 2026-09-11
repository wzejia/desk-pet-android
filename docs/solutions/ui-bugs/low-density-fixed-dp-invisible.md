---
title: 低密度大屏 kiosk（160dpi dp=px）固定小 dp 视觉件失真——点径/键径按屏宽比例+钳制（两案同构）
date: 2026-09-10
category: ui-bugs
module: app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt
problem_type: ui_bug
component: visual
symptoms:
  - 同一 APK：模拟器/手机视觉件可见，160dpi 板子上"看不见"或"太小"（用户措辞常为"不见了"）
  - 例1：导航行键 36dp=36px 占屏宽 3.3% 被反馈太小（S31）
  - 例2：思考三点 6dp=6px 占屏宽 0.55% 被反馈"不见了"（S31 追加）
root_cause: fixed_dp_on_low_density
resolution_type: code_fix
severity: medium
tags: [density-160, dp-px, screen-width-ratio, kiosk, adaptive-sizing, typing-indicator]
---

# 模式

Compose dp 按密度换算 `px = dp × density/160`。160dpi 板子（rk3568 系 kiosk，10 寸
1080p）上 dp=px：一切写死的小尺寸 dp 视觉件（≤10dp 的点/线/图标）物理尺寸只有手机
420dpi 的 38%，在大屏上占比更低——**模拟器/手机验证通过 ≠ 板子可见**。

# 判据

用户报告"不见/太小"+ 同包在 420dpi 设备正常 → 先查 `wm density`，160 一律按本模式处理。

# 修法（已两案采用，模式统一）

尺寸按屏宽比例 + 钳制区间（`LocalConfiguration.current.screenWidthDp`）：

- 导航行键：`screenWidthDp * 0.10` 钳 [56,96]dp（S31 键尺寸）
- 思考三点：`screenWidthDp * 0.015` 钳 [6,18]dp（点径），间距随点径 0.6 倍

比例取值让手机（~411dp 宽）落在原值或 min 钳制——**手机零回归**，板子得可见尺寸。
