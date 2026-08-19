---
title: delta 回调 check-then-act 竞态窗——sealed 检查与 bubble.show 之间隔着可阻塞 sink,cancel 过境顶出幽灵气泡
date: 2026-08-06
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
problem_type: logic_error
component: assistant
symptoms:
  - "barge-in/取消后气泡已 hide,又被迟到的 delta show/append 顶出（幽灵气泡）"
  - "竞态窗极窄,真机难复现：需 sink 回调阻塞期间 cancel 恰好落地"
root_cause: async_timing
resolution_type: code_fix
severity: medium
tags: [check-then-act, race-condition, coroutine-callback, sealed-turn, ghost-bubble, double-check, voice-pipeline, agui-delta]
---

# delta 回调 check-then-act 竞态窗：bubble 操作前必须 double-check sealed

## Problem

S8-5 流式直出改造后，AG-UI delta 回调路径为「入口检查 `turn.sealed` → `transcriptSink.onAssistantDelta()` → `bubble.show/append`」。critic 评审抓出 P2：入口的 sealed 检查与 bubble 操作之间夹着一个**可阻塞的** sink 回调（ChatViewModel 写消息流），cancel 恰好在窗口内落地时，sealed 检查已过、bubble 仍被执行——已被 hide 的气泡被迟到的 show/append 重新顶出。

## Symptoms

- barge-in（键盘首字符/停止键/按住说话打断）后偶发幽灵气泡：旧轮回复的残段在气泡区闪现。
- 常规测试无法复现：竞态窗 = sink 回调耗时，正常路径仅毫秒级；需人为阻塞 sink 才能确定性命中。

## What Didn't Work

- **只靠入口 sealed 拦截**（VoiceSessionManager.kt:455 的既有守卫）：能挡「cancel 后新到的 delta」，挡不住「检查已通过、正卡在 sink 回调里」的 delta——check 与 act 不原子。
- **指望 transcriptSink 快速返回**：sink 实现（ChatViewModel 消息流写入）含 StateFlow 更新与文件持久化排队，耗时不受控；把「回调够快」当并发假设是隐患而非设计。

## Solution

修复 commit **3377530**（本仓 main，单仓无 PR 流程）。在 bubble 操作前做第二次 sealed 检查（VoiceSessionManager.kt:459-460）：

```kotlin
transcriptSink?.onAssistantDelta(delta) // App 聊天页逐 delta 实时上屏（原文，清洗归适配侧）
// S8-5 回归守卫：sink 回调可阻塞，cancel 可在入口 sealed 检查与此处之间落地——
// bubble 操作前必须 double-check，否则 hide 后被迟到 show/append 顶出幽灵气泡
// （删此 → BargeInTest.cancelDuringSinkCallbackDoesNotGhostBubble → 红）
if (turn.sealed) return@chat
```

入口检查（L455）保留不动——它仍挡住 transcriptSink 一路的大部分迟到 delta；double-check 只补 bubble 一路的窗口。三个候选方案（double-check / 锁 / 串行化 dispatcher）里这是最**小充分解**：不加锁、不改线程模型，一行兜底。

测试（TDD 先行）：`VoiceSessionManagerBargeInTest.cancelDuringSinkCallbackDoesNotGhostBubble`——`BlockingFirstTranscriptSink` 在首 delta 的 sink 回调内挂起（双 latch：entered/release），主线程此时 cancel，放行后断言 `bubble.ops` 零 show/append。未修时实测 `[hide, show]` 红；变异锚（删 double-check）→ 红 → 还原绿。

## Why This Works

check-then-act 的成立前提是 check 与 act 之间**没有任何可让渡执行权的点**。本例的 let-go 点隐蔽：sink 回调不是锁、不是挂起函数，只是「别人写的、可能很慢的同步代码」——但 cancel 在另一条线程落地不需要这个点让渡任何东西。凡「检查标志位 → 调用外部回调 → 依据标志位做副作用」的三段式，外部回调就是窗口。double-check 把 act 前的最后一次判断挪到副作用紧邻处，窗口收敛到一条指令级，实际不可命中。

## Prevention

1. **三段式代码评审规则**：`if (flag) ...; externalCallback(); sideEffect()` 形态必问「callback 期间 flag 会不会变」。会 → sideEffect 前 double-check 或整体加锁。
2. **变异锚纪律**：每个异步守卫配一条「删守卫 → 测试红」的锚测试，注释写明格式 `（删此 → 测试名 → 红）`——本仓 S8 起已立，此守卫即范本。
3. **可阻塞点枚举**：评审异步管线时显式列出回调/IO/锁三类让渡点；「别人实现的接口回调」一律按不可控耗时处理。
4. **测试手法**：确定性命中竞态窗不靠 sleep 碰运气，用双 latch（entered/release）把窗口撑到无限宽，cancel 在窗口内确定性落地。

## Related Issues

- `docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md` — 同仓异步/手势域 P1；共享教训「框架/回调语义不许凭印象推断」。
- `docs/solutions/ui-bugs/edge-event-missed-keystopactive-stateflow.md` — 同批真机期修复③，边沿事件 vs 状态源，同属「时序假设」族缺陷。
