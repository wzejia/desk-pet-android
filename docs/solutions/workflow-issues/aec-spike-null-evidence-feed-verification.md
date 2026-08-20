---
title: AEC spike「零误触发」是空证据——旁路只捅 kwsPaused 而路由层恒 DROP，KWS 全程没收到音频；实验有效性必须用喂流计数（fed）佐证
date: 2026-08-20
type: solution
category: workflow-issues
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt
problem_type: workflow_issue
component: kws
symptoms:
  - "spike 观测期「零误触发」结论看似完美，实际实验从未真正运行（KWS 零音频输入）"
  - "基于空证据的能力评估（「硬件 AEC 可靠」）随事故/时间被当作事实引用"
root_cause: workflow_issue
resolution_type: process_fix
severity: medium
tags: [aec, spike, null-evidence, feed-verification, experiment-validity, kws, half-duplex]
---

# spike 实验有效性必须用喂流计数佐证（08-04 AEC 空证据案）

## Problem

2026-08-04 的 AEC spike 结论「TTS 播放 ~5.8min 零误触发」被长期当作「硬件 AEC 可靠」的证据。
2026-08-19 代码复查石锤其为**空证据**：旧 spike 旁路（AecSpikeSection 每 200ms 强制
`kwsPaused=false`）对 SPEAKING 态是 no-op——`VoiceStateRouter.route` 对 PROCESSING/SPEAKING
麦块恒 DROP，kwsPaused 只门 IDLE 分支。TTS 播放全程 KWS 没收到任何音频，「零命中」=
实验从未运行，非「AEC 可靠」。

## Fix（S11-3 重做有效 spike）

- 真放行：`VoiceStateRouter.spikeFeedKws`（SPEAKING 态麦块经标志真实进 KWS），AecSpikeSection
  开关直写标志（废弃 200ms 旁路）；UI 初值读真态（导航离开≠disarm）。
- **有效性闸**：观测期必须见 `kws stats` 的 **fed 在 SPEAKING 期间真实增长**——fed 不涨 =
  spike 无效，结论作废重做。任何「零命中」结论先问「喂了吗」。
- 数命中口径：`keyword hit:`（带冒号，tag KwsEngine），排除 `keyword hit dropped (release in progress)`。

## Prevention

1. **凡「零发生」类结论，先证实验在场**：零误触发/零异常/零泄漏类观测，必须有一个正向计数证明
   被测路径真的跑了（本案=fed；通用=输入侧计数）。
2. 旁路/开关类实验设施，改完路由层必须回查旁路是否仍指向真实路径（路由是后来改的，旁路是
   改前写的——两层各自演化，接口语义早已漂移）。
3. BLOCKED.md 的结论性陈述标注证据强度：「实测有效」vs「实验无效待重做」。

## 反例

- 「跑了 5.8 分钟一个误触发都没有，AEC 没问题」——没验证喂流的零命中是空证据（本案）。
