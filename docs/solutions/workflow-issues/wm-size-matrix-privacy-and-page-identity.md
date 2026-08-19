---
title: 真机多屏矩阵（adb wm size）测试三纪律——隐私即删、页面身份文本锚、证据裁切
date: 2026-08-03
category: workflow-issues
module: 真机验收 / S6 多屏矩阵
problem_type: workflow_issue
component: development_workflow
applies_when:
  - 用 adb wm size / wm density 做多屏适配矩阵截图验收
  - 真机截图为盲测或回归取证入库
  - 用户持机期间进行脚本化设备操作
tags: [wm-size, 多屏矩阵, 隐私, uiautomator, 页面身份断言, screencap]
---

# 真机多屏矩阵测试三纪律

## Context

S6 多屏矩阵（`adb wm size` 1080x2400 / 720x1600 + reset）实测中连续踩中三坑：
一张截图捕到控制中心里的**短信验证码**与桌面**私人便签**；两张「聊天页」截图实为
**设置页**（App 恢复到上次栈顶）却通过了像素抽查；悬浮窗证据图里壁纸含人像。
三条都是流程性问题而非代码 bug，任一条再犯都是事故。

## Guidance

**① 隐私即删**：用户真机的任何全屏截图都可能含通知栏验证码、便签、聊天记录。
拍到即删（本地+不入库），证据只保留目标区域裁切（宠物/气泡/组件框）。
用户持机期间尽量减少脚本化 tap——本项目已有两次误入微信/通话记录的教训。

**② 页面身份用文本锚断言**：`wm size` 变更触发 Activity 重建，App 恢复的是
**上次栈顶页面**（设置页），不是你以为的聊天页。截图前必须验页面身份：

```bash
# 不够：mCurrentFocus 只到 Activity 级（设置/聊天同属 MainActivity）
# 不够：像素均值采样（同为深色主题的页面互不清）
# 正确：uiautomator 文本锚
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | grep -c "按住"   # ≥1 才是聊天页
```

**③ 证据裁切到目标区域**：悬浮窗类证据只裁宠物/气泡包围盒（dumpsys 取窗口
bounds 再裁），壁纸人像、桌面便签、图标标签一律不进库。

## Why This Matters

隐私内容一旦 commit 进 git 历史，删除成本极高（改写历史）且可能已被索引——
「即删不入库」是唯一低成本防线。页面身份误判则直接污染验收证据链：
两张设置页截图差点作为「聊天页矩阵证据」入库，下游评审会基于错误证据下结论。

## When to Apply

- 任何 `adb wm size`/`wm density` 矩阵测试（变更后前台页面与栈顶都会变）
- 真机截图作为证据入库前的固定检查点
- 编排多 builder 并行时的设备操作（配合 .scratch/device.lock 互斥锁——运行时创建、
  gitignore 不入树，协议详见 docs/solutions/workflow-issues/parallel-builders-single-device-mutex.md）
