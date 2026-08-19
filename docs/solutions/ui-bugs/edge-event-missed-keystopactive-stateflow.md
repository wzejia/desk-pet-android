---
title: UI 开关订阅边沿事件必丢——多功能键停止档有概率不显示，voiceActive 改 keyStopActive 状态源派生
date: 2026-08-06
type: solution
category: ui-bugs
module: app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt
problem_type: ui_bug
component: assistant
symptoms:
  - "语音唤醒轮 AG-UI 流出文本时，输入框多功能键有概率不切换成停止键（方块）"
  - "日志实锤：VM onCreate 19:46:49 晚于 onUserUtterance 19:46:44——voiceActive 置位边沿发生在订阅者存在之前"
root_cause: async_timing
resolution_type: code_fix
severity: medium
tags: [stateflow, edge-event, state-derived, combine, viewmodel-late-init, function-key, voice-active, ui-consistency]
---

# 边沿事件 vs 状态源：UI 显示判据必须用状态派生，且与行为门控同源

## Problem

语音轮（悬浮窗唤醒发起）流出文本时，App 聊天页多功能键应显示停止键。实现依赖 `voiceActive`——它在 `onUserUtterance` 回调里置位（**边沿事件**）。但 ChatViewModel 是 Activity 级 AndroidViewModel，用户中途打开 App 时 VM 才创建，边沿早已过去，`_voiceActive=false` 永不再翻真——停止键有概率不出现，用户无法打断。

## Symptoms

- 用户反馈：「语音唤醒后输出文本时，多功能键没切换成暂停键」「有概率」。
- 日志铁证：`onCreate`（19:46:49）晚于 `onUserUtterance`（19:46:44）5 秒——边沿事件对晚到订阅者不可见。

## What Didn't Work

- **重发边沿/事件缓存类方案**（未采纳）：给边沿事件加重放是补丁思维——晚建订阅者要的不是「错过的事件重播」，而是「此刻该不该显示」的答案。
- **只看 `voiceActive` 加日志排查**：能复现概率性（取决于 VM 创建时机），但修不好——病根在事件/状态模型选型，不在时序调优。

## Solution

修复 commit **640203a**（本仓 main，单仓无 PR 流程）。引入状态源派生流（ChatViewModel.kt:218-221）：

```kotlin
val keyStopActive: StateFlow<Boolean> =
    combine(_voiceActive, VoiceClientFactory.session(getApplication()).stateFlow) { va, st ->
        va || st != VoiceStateRouter.State.IDLE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)
```

UI 侧（ChatScreen）从 `vm.voiceActive.collectAsState()` 改订 `vm.keyStopActive.collectAsState()`。关键设计：**这个判据不是新发明的，是把 `cancelCurrentTurn()` 已有的行为门控判据（`voiceActive || vsm.state != IDLE`）原样提升为 UI 显示判据**——显示与行为同源，天然一致。VoiceSessionManager.stateFlow 是持续状态而非边沿，VM 任何时刻创建都能算出正确当前值。

真机复验：唤醒轮流出中打开 App，停止键在；12:17:22 用户按停止键 barge-in 成功。

## Why This Works

边沿事件回答「刚才发生了什么」，状态源回答「现在是什么」。UI 的可见性问题是后者。边沿模型隐含假设「所有订阅者都全程在线」——AndroidViewModel 懒创建、Compose 重组、进程重建全都会打破它。而「门控判据已存在」是本仓的普遍形态：行为层（cancel 能不能打）往往早就被迫处理了对时序鲁棒的判据，UI 层另起炉灶用边沿是漂移源头。**UI 显示判据与行为门控判据必须同源**，这不是巧合修复，是应当主动执行的一致性规则。

## Prevention

1. **边沿 vs 状态源检查单**：凡 UI 可见性由「某回调里置位的 Boolean」驱动，问三问——订阅者会晚到吗？事件会重发吗？进程重建后值还在吗？有一问不稳 → 改状态源派生（combine 真实状态流）。
2. **显示/行为同源规则**：UI 显示 X 的判据 与 触发 X 行为的门控判据 引用同一份派生流；发现两处各自实现即判漂移。
3. **日志定根因**：「有概率」类 UI bug 先抓事件时间线（onCreate vs 回调时间戳），边沿错过类问题一行日志即实锤，勿靠改代码碰运气。
4. **stateIn(Eagerly) + initialValue**：派生流给保守初值（false=不显示停止键），错过真相的代价是「少显示」而非「误显示」—— fail-safe 方向选对。

## Related Issues

- `docs/solutions/logic-errors/delta-callback-check-then-act-ghost-bubble.md` — 同批修复①，同属「时序假设」族（那边是 check-then-act 窗口，这边是边沿订阅窗口）。
- `docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md` — 同仓 Compose 手势域；共享预防规则「语义/时序不许凭印象，要实证」。
