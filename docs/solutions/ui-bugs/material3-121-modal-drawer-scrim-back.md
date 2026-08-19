---
title: material3 1.2.1 ModalNavigationDrawer 双失灵——gesturesEnabled=false 短路 scrim + 无内建 BackHandler
date: 2026-08-03
category: ui-bugs
module: ChatScreen 会话抽屉（ModalNavigationDrawer）
problem_type: ui_bug
component: assistant
symptoms:
  - 抽屉开着时点 scrim（抽屉外遮罩区）→ 抽屉不关闭
  - 抽屉开着时按返回键 → App 直接退到桌面（mCurrentFocus 变 Launcher），而非只关抽屉
root_cause: wrong_api
resolution_type: code_fix
severity: high
tags: [material3, modal-navigation-drawer, scrim, backhandler, gesturesenabled, compose, 抽屉]
---

# material3 1.2.1 ModalNavigationDrawer scrim 点击与返回键双失灵

## Problem

聊天页 `ModalNavigationDrawer`（`gesturesEnabled = false`，为防抽屉边缘拖拽与系统边缘
返回手势互抢）出现两条关闭路径同时失灵：点 scrim 不关、按返回键不关反而退出 App。

## Symptoms

- 抽屉开着，`input tap` 点击 scrim 区域多个点位（x=1200/1350 @1440 宽屏）→ uiautomator
  抽屉节点仍在
- 抽屉开着，`input keyevent 4`（BACK）→ `dumpsys window` 的 mCurrentFocus 从
  MainActivity 变为 Launcher（App 被 finish）

## What Didn't Work

- 以为是「组件默认行为，低风险」挂 backlog——真机一测即穿：两条路径全坏。
  Compose 声明式组件的「默认应该有」行为，必须真机断言，不能从 API 印象推断。

## Solution

两处对症（`app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt`，本仓 commit e2e07ce）：

```kotlin
// ① scrim：开着时给 true 才恢复点击/拖拽关；关着仍 false 保原防互抢设计
gesturesEnabled = drawerState.isOpen,

// ② 返回键：自加 BackHandler，只在抽屉开着时拦截
BackHandler(enabled = drawerState.isOpen) {
    scope.launch { drawerState.close() }
}
```

`BackHandler` 来自 `androidx.activity.compose`（activity-compose 直接依赖即可，无需动
manifest 的 `enableOnBackInvokedCallback`——未启用时走 legacy OnBackPressedCallback，
targetSdk 34 在 Android 16 上正常派发）。

## Why This Works

javap 反编译 `material3-android-1.2.1` 双根因实锤：

1. **scrim 被 gesturesEnabled 短路**：`NavigationDrawerKt$ModalNavigationDrawer$2$2$1`
   （scrim 的 onClose lambda）`invoke()` 首条有效指令即 `getfield $gesturesEnabled;
   ifeq → return`——scrim 点击与边缘拖拽共用同一个开关，false 连带 scrim 失效。
2. **1.2.1 不内建返回处理**：全部 36 个 `NavigationDrawerKt*.class` grep `BackHandler`
   零命中（1.3 才加 predictive back 支持）。抽屉开着时 back 穿透到 NavController，
   在 startDestination 上直接 finish Activity。

`gesturesEnabled = isOpen` 让两个语义各归其位：关态禁拖拽（防手势互抢原设计保留）、
开态恢复 scrim 点击与拖拽关（scrim 只在开着时存在，无副作用）。BackHandler 用
`enabled = isOpen` 条件占位，关着时不拦截——聊天根页 back 退出 App、子页 back 回上一页
等既有路径零影响。

## Prevention

- **验收断言模板**（抽屉类组件真机四断言，本项目已固化）：① 开→back→关且留前台
  （mCurrentFocus 不变）；② 开→scrim tap→关；③ 关→back→既有行为不变（根页退 App）；
  ④ 子页 back→回上一页。
- 依赖库行为存疑时直接 `javap -c -p` 反编译 AAR 里的类——比翻 changelog/猜版本行为快且硬。
- 侧边手势禁区决策（gesturesEnabled=false 防互抢）必须配「scrim 是否还能点」的联查：
  material3 里两者是同一开关，不是两个。
