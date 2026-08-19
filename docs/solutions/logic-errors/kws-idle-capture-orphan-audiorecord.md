---
title: 对话中销毁 KWS 服务 → 麦克风孤儿空采——stopIdleCapture 状态早退把回收责任推给不存在的下游
date: 2026-08-06
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
problem_type: logic_error
component: assistant
symptoms:
  - "对话进行中销毁 KWS 前台服务后，系统麦克风绿点常亮不灭"
  - "AudioRecord 持续采集但无任何消费者，持续耗电"
  - "回 IDLE 后 collectJob 挂活收块，IDLE 路由到 null kwsSink 全丢"
  - "无任何日志暴露采集仍在运行（静默泄漏）"
root_cause: async_timing
resolution_type: code_fix
severity: high
tags: [microphone-leak, idle-capture, intent-registration, lifecycle-ownership, kws, foreground-service, state-machine, single-owner]
---

# 对话中销毁 KWS 服务 → 麦克风孤儿空采（意愿登记制修复）

## Problem

KWS 常开前台服务（VoiceForegroundService）与 VoiceSessionManager 复用同一条采麦流：服务挂 kwsSink 订阅块，会话管理器在 IDLE 态起采麦循环供 KWS 解码。审计发现 P1：用户在**对话进行中**销毁 KWS 服务（关开关 / 通知动作 / 系统回收）后，采麦流永远不会停止——AudioRecord 空采、块全丢、绿点常亮、无日志。「KWS 服务被销毁而 App/对话仍在」是真机上已被观察到的真实运行形态（session history：08-05 真机验收时进程活着但服务数为 0），不是理论场景。

## Symptoms

- 对话进行中销毁 KWS 前台服务后，系统麦克风绿点常亮不灭。
- AudioRecord 持续读块但没有任何消费者，持续耗电。
- 全程无日志（块在 IDLE 路由分支被静默 `kwsSink?.invoke` 丢弃，null sink 不报错）。

## What Didn't Work

旧设计是「对话中不动采集」的调用点条件早退（修复前实现，由 commit 17f21c0 替换）：

```kotlin
// 旧实现
fun stopIdleCapture() {
    if (state != VoiceStateRouter.State.IDLE) return // 采集归对话管线，直接走人
    collectJob?.cancel(); collectJob = null; capture.stop()
}
```

这个早退局部合理（对话进行中采集确实归对话管线，不能掐），但它没有回答「对话结束后谁负责收」。时序推演：

1. 对话进行中（LISTENING/PROCESSING/SPEAKING），服务 onDestroy（VoiceForegroundService.kt:154-164）：先 `session.kwsSink = null`，再 `session.stopIdleCapture()`。
2. stopIdleCapture 见 `state != IDLE` 直接 return，采集照旧（此步本身正确）。
3. 对话结束回 IDLE——**此时再没有任何人记得「服务已死、采集该收」**。collectJob 挂活在 flow 上继续收块，routeBlock 在 IDLE 态把块路由到已是 null 的 kwsSink，`?.invoke` 静默丢弃。
4. AudioRecord 永不停止：有采集、无消费者、无日志。

根因不在某一行写错，而是**采集流归属权没有单一 owner**——每个调用点各自做条件早退，责任在状态迁移的瞬间悬空。此前两个 S8 会话从未排查过这条路径（session history：stopIdleCapture/collectJob/idleCapture 零提及），属审计新发现。

## Solution

改为**意愿登记制**：调用点只登记意愿，不裁决；回 IDLE 的 `transition()` 统一按意愿裁决 teardown。

意愿标志（VoiceSessionManager.kt:341-343）：

```kotlin
/** KWS 常开采麦意愿（stop 对话中只撤意愿，回 IDLE 统一执行的裁决源）。 */
@Volatile
private var idleCaptureWanted = false
```

start/stop 只登记意愿（VoiceSessionManager.kt:317-341）：

```kotlin
fun startIdleCapture() {
    idleCaptureWanted = true
    if (state != VoiceStateRouter.State.IDLE) return
    ensureCollectLoop()
}

fun stopIdleCapture() {
    idleCaptureWanted = false
    if (state != VoiceStateRouter.State.IDLE) {
        logger("idle capture stop deferred (conversation owns capture), teardown on IDLE")
        return
    }
    teardownIdleCapture()
}
```

统一执行点在 transition() 的 IDLE teardown 守卫（VoiceSessionManager.kt:606-610）：

```kotlin
// 回归守卫：回 IDLE 且 KWS 意愿已撤 → 收采麦循环（删此 → 对话中 stopIdleCapture
// 后 collectJob 挂活 = 麦克风孤儿空采 → VoiceSessionManagerIdleCaptureTest 红）
if (to == VoiceStateRouter.State.IDLE && !idleCaptureWanted && collectJob != null) {
    teardownIdleCapture()
}
```

teardownIdleCapture() **必须连 collectJob 一起收**——只停 capture 会让 collectJob 挂活在空 flow 上，之后 ensureCollectLoop 见 job active 不再重启 capture，采麦静默坏死：

```kotlin
private fun teardownIdleCapture() {
    collectJob?.cancel()
    collectJob = null
    capture.stop()
}
```

配套新增 `collectLoopActive` 测试缝供回归断言。服务侧 onDestroy 顺序（kwsSink=null → stopIdleCapture → engine.release）无需改动，天然契合意愿制：sink 先摘，意愿后撤，对话结束时由 transition 收尾。

## Why This Works

归属权从「每个调用点各自裁决」收敛为「一个裁决源 + 一个执行点」：

- **裁决源唯一**：idleCaptureWanted 是「采集该不该在」的唯一事实。对话进行中 stop 只改事实不动资源，资源去留不依赖调用点所处状态。
- **执行点唯一**：transition() 是所有状态迁移的必经之路，回 IDLE 的瞬间必然有人裁决一次。旧设计缺的那一环（「对话结束后谁收」）由状态机自己补上。
- **对称防漏**：KWS 常开时对话结束回 IDLE，idleCaptureWanted 仍为 true，守卫不触发，采集循环保留——唤醒常开的根基不受影响。

该修复与项目既有「状态源派生、防线成对」风格一致（session history：keyStopActive 教训=UI 判据从状态源派生而非边沿事件；teardown 同理，应挂在 transition 上而非某次 stop 调用的瞬时状态判断上）。

教训：跨生命周期的资源（采集流、连接、订阅）归口管理用「意愿登记 + 状态迁移点统一执行」，不要在每个调用点做条件早退——早退把责任推给了不存在的下游。

## Prevention

- **模式**：跨生命周期资源一律「意愿标志 + transition/生命周期回调统一裁决」，调用点不直接操作资源。同文件已有同构先例（kwsPaused 复位集中于回 IDLE 的两条路径——backToIdle 与 listen-timeout 分支，无第三处手写复位）。
- **变异锚测试**：VoiceSessionManagerIdleCaptureTest.kt 三条用例钉住全部语义——对话中 stop 延迟至回 IDLE 才收（删 transition 守卫 → 红）/ IDLE stop 立即收 / KWS 意愿仍在时对话结束不收。commit 17f21c0 已实测变异锚（删守卫对应测试变红后还原复绿）。
- **真机验证**：设置页关 KWS → 日志见 `released, fed=...` 汇总行后紧跟 `capture stop`；372 测绿（44 套件）。注意 PetLogger 文件刷盘有分钟级滞后，判「采集是否真收」以日志文件为准、别只信 logcat（session history：08-04 排障确立的口径）。

## Related Issues

- [delta 回调 check-then-act 竞态窗](delta-callback-check-then-act-ghost-bubble.md)——同文件同族异步守卫缺陷（检查与副作用错位）；本文为同族第三例
- [strip-empty 唤醒死路重播我在](strip-empty-wake-word-dead-end-replay-ack.md)——同状态机静默分支缺陷；其 Prevention「静默分支必须自带理由」可推广为「守卫早退必须不跳过 teardown」
