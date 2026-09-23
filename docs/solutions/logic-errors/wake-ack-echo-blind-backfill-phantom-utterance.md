---
title: 唤醒应答「我在」回声经 S40 盲窗回灌产出伪 utterance——NLMS 参考过期直通，VAD 判出幽灵话轮抢占聆听窗
date: 2026-09-23
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
problem_type: logic_error
component: voice-pipeline
symptoms:
  - "唤醒后板子应「我在」，随即监听被结束（一眨眼就被假对话轮截走）"
  - "ASR 转出「告诉我我在。」/「我不过再。」/「不晚，我在。」系回声文本，被当用户指令发 LLM"
  - "打断播报场景重唤醒 3/3 复现；IDLE 唤醒不中招"
  - "日志：S40 blind-window backfill 后 96ms 报 VAD utterance，19200 samples（1.2s）maxLevel 0.66-0.99——而此前活块 vad diag level=0.0007 全静"
  - "NLMS 遥测 inRms≈outRms（回声零消除）"
root_cause: async_timing
resolution_type: code_fix
severity: high
tags: [wake-ack-echo, blind-window, s40, s65, nlms-stale-reference, vad-phantom-utterance, half-duplex, echo-era-guard]
---

# 唤醒应答「我在」回声经 S40 盲窗回灌产出伪 utterance（回声纪元守卫修复）

## Problem

用户报障：唤醒后板子应「我在」，随即监听就结束了。测试板 2026-09-23 10:49 连续三轮打断播报重唤醒 **3/3 复现**，且 IDLE 唤醒同样操作不中招——失败集中在「打断播报（SPEAKING 期重唤醒）」。

链路：打断 → 播 wozai「我在」→ wozai done 后 VAD start → S40 回灌盲窗缓冲进 VAD → VAD 判出「语音段」→ 伪 utterance → ASR 转出「告诉我我在。」→ 假对话轮（PROCESSING）把聆听窗截走。

## 取证（日志 + 代码 + 包三线）

关键时序（`s64-debug-104747.log` 10:49:24 轮，打断重唤醒）：

```
23.647 wake word hit (你好小朵) during SPEAKING   ← 打断
23.690 state -> SPEAKING (wake-ack wozai)
24.665 state -> LISTENING (wozai done, VAD start)
24.740 S40 blind-window backfill: 6 blocks        ← 回灌（全是 wozai 播放期采集块）
25.158 vad diag level=0.0007                      ← 活块全静
25.254 VAD utterance, 19200 samples, maxLevel=0.9877  ← 96ms 后报 1.2s 语音
25.254 utterance 素材 = 回灌块（数学上活块时间装不下 19200 samples）
```

- **盲窗内容**：`WakeBlindWindowBuffer` 设计如此——唤醒命中→vad.start 期间全部采麦块入盲窗（含 wozai 播放窗回声、打断场景还有旧 TTS 残声，maxLevel 0.66-0.99）。
- **NLMS 为什么没消**：`NlmsEchoCanceller.kt:124` `if (now() - lastRefAt > REF_FRESH_MS /*400ms*/) return mic.copyOf()`——新鲜度锚 `now()`（处理时刻），而回灌发生在 wozai 停播 ~0.5s 后 → **恒过期直通**。旁证遥测 `10:49:47 echo cancel active: inRms=0.4882 outRms=0.4894`（零抑制）。
- **IDLE 唤醒幸免**：盲窗内仅唤醒词尾+短弱回声，VAD 判起确认不过阈；打断场景多了旧 TTS 残声（连续强电平真语音）→ 足以确认 speech。

## What Didn't Work（排障中排除的假设）

1. **「平台断流波及」**——排障期间板上 run 大面积断流，一度怀疑同源。实为平台 chatAgent 独立故障（同日另一 solution 定案），与本 bug 无关：本 bug 的 ASR 是**成功转出回声文本**，不是断流。
2. **「回灌块统一过 NLMS 即可」**（S40 原始设计假设）——被 `REF_FRESH_MS` 时间锚证伪（见上）。S40 注释「停顿大时参考过期>400ms 直通——退化为带回声回灌，S19-2 空 ASR 续听兑底伪轮」：兜底假设「回声 ASR 出来是空文本」，实际阿里云 ASR 把「我在」转成了实文字，兜底失效。
3. **「时序撕裂」**（09-12 Case B 同症：533ms 窗口装 1.2s 音频）——seamPolicy 修的是块乱序，不治回声来源本身。

## Solution（方向 1，用户拍板：静音确认后才收）

**回灌截止线**：`cutoff = vadSeamNs - (vadStartDelayMs + ECHO_TAIL_GUARD_MS)`，`ECHO_TAIL_GUARD_MS = 150L`。

- wozai 播放期捕获的块（`capturedAt < wozai 写完时刻 ≈ seam - vadStartDelayMs`）**整段丢弃**——它们主要是回声（旧 TTS 残声 +「我在」），且 NLMS 兜不住；
- delay 窗尾部（写完+喇叭尾巴余量之后）捕获的迟到块**保留回灌**——S40 话头找回能力收窄存续（消费停顿场景仍受益）；
- 代价（明确接受）：wozai 播放期用户抢说的话头丢弃——本就被回声混叠，ASR 出来也是垃圾。

实现：

```kotlin
// WakeBlindWindowBuffer：条目携带捕获戳，drainFrom 按截止线过滤
data class Entry(val pcm: ByteArray, val capturedAtNs: Long)
fun drainFrom(cutoffNs: Long): List<ByteArray> = drainEntries().filterNot { it.capturedAtNs < cutoffNs }.map { it.pcm }

// VoiceSessionManager.backfillBlindWindow
val cutoffNs = vadSeamNs - (vadStartDelayMs + ECHO_TAIL_GUARD_MS) * 1_000_000L
val blocks = wakeBlindBuffer.drainFrom(cutoffNs)
logger("S40 blind-window backfill: ${blocks.size} blocks, S65 echo-era dropped ${total - blocks.size}")
```

## Verification

- 单测 656 绿（+4）：`VoiceSessionManagerS65EchoEraGuardTest`（回声纪元块丢弃→不产伪轮/不出现 S51 hold/状态保持 LISTENING；delay 窗迟到块仍回灌→S51 hold 出现）；`WakeBlindWindowBufferTest` +drainFrom 边界 2 例；S40 用例 1/2 按新语义重写。
- **变异验证**：删 cutoff 过滤（回灌全量）→ S65 主用例红（测试真实锁行为，非恒绿）。
- 测试板 APK `b2df043c` 已装；**真机喊话复测待做**（等 prefs 恢复后）。

## Prevention / 学到

- **「NLMS 兜回声」类设计必须核对时间锚**：参考新鲜度按 `now()`（处理时刻）而非块捕获时刻评估 → 延迟处理（回灌/补算）路径恒直通。跨「采集-处理」时序的模块，时间戳要一路带下去。
- **半双工自噬防护要覆盖缓冲通道**：S59 尾音衰减窗（delay vadStartDelayMs）治的是活块；本条证明**缓冲回灌是独立的第二条通道**，所有「把旧音频灌回管线」的地方都要过同一道闸。
- S40 注释里的兜底假设（「回声→空 ASR→续听」）被云端 ASR 的转写能力演进打破——兜底链依赖下游行为时应标注失效条件。
