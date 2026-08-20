---
title: 能量门控「整块不喂不补静音」碎裂流式 KWS 时间轴——字间气口被挖压分 + 冻结部分分被下一声补完（「第一声必漏、第二声秒中」真凶）
date: 2026-08-20
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/KwsCore.kt
problem_type: logic_error
component: kws
symptoms:
  - "唤醒词第一声必漏、第二声秒中（开口仅 ~140ms 即中，四个字来不及念完）"
  - "「我在」莫名迟到数秒出现（安静期被环境响动补完冻结分）"
  - "用户体感「有概率唤不醒」——实际是首声打分被时间轴压缩压到阈值下"
root_cause: logic_error
resolution_type: code_fix
severity: high
tags: [kws, energy-gate, streaming-transducer, timeline, hangover, gap-reset, sherpa-onnx, frozen-partial, voice-pipeline]
---

# 能量门控丢弃语义碎裂流式 KWS 时间轴（挂起窗 + gap-reset 修复）

## Problem

KWS 能量门控为省电实现为「rms<gate 的 100ms 块整块不喂引擎、且不补静音」（桌面 sidecar 同源语义）。
对**流式** transducer KWS 这是两个独立破坏性语义：

1. **时间轴压缩**：喊词内部字间气口（「小-朵-小-朵」间 100-300ms 低能块）被挖掉，音频被压缩拼接，
   模型拿到的不是真实时间轴上的声音，打分被压到阈值之下 → **第一声必漏**。
2. **冻结部分分不冲刷**：深静默块不进流，波束里「差一口气」的部分分永远等不到尾随空白帧来剪枝
   （numTrailingBlanks 无帧可消费），冻结在原地；下一个声音事件（用户补喊的第二声、或任意响动）
   的前几帧把它补完 → **「第二声秒中」假象 / 「我在」莫名迟到**。

## 取证方法（可复用的排障路径）

证伪序列（每步有日志/账目证据，详见 `docs/verification/v17-kws-accuracy/`）：
拼音序列 → 阈值 → 能量门 → 路由残留 → 音频管道延迟（单声等待实验一度指向它）——
最后靠**块级探针**（S11-6：AudioRecord 采集时间戳 lag + cap/feed 双段时间戳）一锤定音：
lag=1-4ms 证明管道实时，命中时间线（第二声开口 140ms 即中）证明冻结补完机制。
**教训：「第二声必中」这种确定性模式不是检出率问题，是状态机/缓冲问题的指纹。**

## Fix（S11-7）

- **挂起窗（hangover）**：过门块后 8 块=800ms（与 VAD 判停 800ms 同源）内低于门仍喂真实块——
  字间气口弱音保留，时间轴连续。窗内低能块只消耗不续窗（门限附近噪声不会无限续窗）。
- **gap-reset**：连续低于门超窗 → 不喂（省电初衷保住）+ 对 KWS 流 reset 恰好一次（幂等沿；
  sentinel 入队保序，reset 仅 decode 消费线程执行，不新增跨线程 native 调用）——冲刷冻结尾分。
- 常量锚：`KwsDefaults.GATE_HANGOVER_BLOCKS=8` + 锚点测试（事故教训：无锚常量会静默丢失）。

## Prevention

1. **流式模型的输入时间轴不可压缩**：任何「丢块」优化必须补等长静音，或接受 reset 边界；
   丢而不补 = 把模型喂给它没受过训练的输入。
2. **流式状态必须有冲刷机制**：不进流期间波束不前进，部分分不会自然死亡——长静默要显式 reset。
3. 调试设施的有效性要用**喂流计数佐证**（fed 增长证明音频真的到了引擎）——见 [[aec-spike-null-evidence-feed-verification]]。
4. 桌面 parity 核对要核**完整语义链**：对账表「整块不喂不判 ✅」与桌面一致，但桌面同样缺
   第二步（桌面环境未暴露此问题）——parity 一致不代表语义正确。

## 反例（不要再这样做）

- 「省电所以静音块直接丢」——丢块不补静音，时间轴断裂（本案）。
- 「降阈值治漏检」——本案阈值无责；不调数据先动参 = 盲调（防误触发回潮）。
