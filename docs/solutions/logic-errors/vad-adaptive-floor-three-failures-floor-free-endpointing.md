---
title: VAD 判停链恒噪板三失败（判停死锁/句中切尾/全聋）——自适应噪声底 EMA 不可恃，无 floor 化三层接管
date: 2026-10-21
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt
problem_type: logic_error
component: voice-pipeline
severity: high
symptoms:
  - "恒噪板（板载自噪 0.19-0.27 恒定）说完话后判停死锁/反复触发——floor 钉在底噪稳态，判停线 max(0.03, floor×1.4) 骑在底噪上，块级电平绕线摆动令判停计数器与上攻计数器互相清零"
  - "说话句中弱音节被计静音 600ms 即切尾——说话期上攻通道被弱音节半峰判据误触发，floor 被推向说话电平，判停线贴住说话弱音节"
  - "正常说话判不起、30s 超时（全聋）——floor 推到 NOISE_FLOOR_MAX=0.5 封顶后判起线 floor×2=1.0"
root_cause: logic_error
resolution_type: code_fix
tags: [vad, endpointing, adaptive-noise-floor, constant-noise, floor-free, absolute-clamp, noise-floor-circuit-breaker, voice-pipeline]
---

# 语音链 VAD 判停在恒噪板双向失败——floor 化判停退役，三层接管（S69/S69b/S69c）

> 项目 desk-pet-android。以下 `VadDetector.kt:NN` / `VoiceSessionManager.kt:NN` 均指
> `E:/desk-pet-android/app/src/main/java/com/xiaoduo/deskpet/voice/` 下同名文件（行号为 merge 5f3c4ac 时点）。
> merge-state（本仓单机无 PR，SHA 即锚）：S69=d4752da，S69b=b70d4e2，S69c=a64bcb8，merge=5f3c4ac @ origin/main。

## Problem

恒噪板（rk3568 板载自噪 0.19–0.27 恒定，来源=板内风扇/采麦链，非环境声）上 VAD 判停链**双向失败**：

- 轻声说完**永不判停**——utterance 永不派发，用户只能双击取消；
- 响亮+句中气口又**过早判停**——ASR 只出单字「放」。

根因同源：判起/判停阈值自适应锚在 EMA 噪声底 `noiseFloor`（VadDetector.kt:196）上，而 floor 在恒噪板上存在三种系统性失真（见 Symptoms），阈值公式本身没错，错在「判停正确性依赖 floor 精确跟踪」这一前提。

## Symptoms（三失败模式 = floor EMA 的三张面孔）

**① 判停死锁（轻声永不切）**
floor 被慢跟随钉在底噪稳态——稳态下 `noiseFloor += (level-noiseFloor)×0.10`（VadDetector.kt:97,:266）推力归零；判停线 `max(0.03, floor×1.4)`（VadDetector.kt:61,:113,:359-360）骑在底噪带（0.19×1.4≈0.27）上，底噪块级电平绕线摆动令一部分块在线上一部分在线下——`belowTicks`（判停计数，:312 递增）与 `sustainedAbove`（上攻计数，:249 递增）**互相清零**（:264 与 ：308 各在对方递增时归零）：既不判停也不上攻。板端实锤：4s 气口不切、「（七）」永不派发。

**② 提前判停（响亮+气口切尾）**
说话期上攻通道（S58 复活 `NOISE_ATTACK_ENABLED=true`，VadDetector.kt:91）被弱音节半峰判据（`level < speechPeak×0.5`，:100,:257）误触发，floor 以 0.35 速率爬向说话电平（:94,:260-261）→ 判停线贴说话弱音节 → 句中字尾弱音节计静音 600ms 即切。板测第 1 轮：完整句切剩「帮我查」。

**③ 全聋（正常说话判不起）**
floor 推到 `NOISE_FLOOR_MAX=0.5` 封顶（VadDetector.kt:106,:261）→ 判起线 `floor×2=1.0`（:112）→ 正常说话（diag level 0.33–0.42、削波 1.0）判不起 → 30s 聆听超时。板测第 3 轮实锤。

## What Didn't Work

- **纯参数调整**（S50–S58 历代）：判停窗 400→600→800→600ms 反复横跳（演进注释 VadDetector.kt:65-68）、S52 停上攻 / S58 复活上攻的开关反复（:85-91 注释保留完整判决史）——都治标：窗调大伤实时性、上攻开关心率不齐，三个失败模式共享同一 floor 根源，参数域内无解。
- **引用过时注释做归因**：DEV_LOG 归因引用 S56 注释「上攻已停用」，而 S58 已复活——注释假锚致首轮根因分析跑偏。
- **JVM 测试构造三轮迭代才复现死锁**：恒定块序列触发的是「说话期上攻」假死锁；T 密集序列里上攻自愈、并非死锁；T 稀疏 `[F,F,F,T]` 循环才是死锁域——最终用 Python 全保真状态机模拟才定位。测试输入不落在真实故障域时，测试绿 ≠ 无 bug。

## Solution

方向反转：不再追求 floor 精确化，**判停正确性与 floor 解耦，三层接管 + 合并兜底**。

### 1. 底噪熔断（S69）——不依赖 floor 的强制收句出口

- 位置：`VadDetector.feed` 说话态分支（VadDetector.kt:296-305）。
- 判据：证据窗（复用 plateauWindow 最近 10 块，:208,:246-247）**全部**块 < speechPeak EMA×0.6（`STALL_PEAK_RATIO`，:129），且说话态已累计 ≥10 块（`STALL_MIN_SPEECH_TICKS`=10，:126）→ `finishUtterance()` 强制收句（:305→:327）。
- 原理：「窗内无语音」是与**语音峰值的相对关系**，底噪绕线摆动破坏的是绝对电平、破坏不了这个相对关系；低峰比对对持续音**自锁**（peak 含当前块，长元音 level≈peak 恒不满足 ×0.6）——免疫 S52 式长元音误伤。说完到收句 ≈ 证据窗填满 ~1s，替代「8s 强制结束」与「永不派发」两个极端。

### 2. 说话期禁用上攻（S69b）——根除 floor 污染源

- 条件改为 `NOISE_ATTACK_ENABLED && !isSpeaking && ...`（VadDetector.kt:256）：floor 的快速上攻只允许发生在未判起期（S19-1 原场景：AGC 泵态/环境阶跃）。
- 上攻原职能由三路接管：未判起期慢跟随（:266）+ S56 空 ASR 裁判（空文本轮=ASR 认证「不是话」，VoiceSessionManager.kt:973-982 调 `raiseNoiseFloorFromLastUtterance()`，VadDetector.kt:153-157 floor 以 0.6 速率向该轮 RMS 收敛）+ 熔断兜底。失败模式②③的污染源被根除。

### 3. 判起/判停线绝对钳制（S69b 立、S69c 调）——floor 污染后果硬上限

- `START_LINE_MAX=0.32`（VadDetector.kt:119）、`END_LINE_MAX=0.30`（:123），阈值公式改为 `max(provider, min(floor×k, MAX))`（判起 ：351-355、判停 ：359-360）。
- floor 无论被谁污染：判起永不全聋（③封死）、判停永不爬进说话弱音节域（②封死）。0.32 的取值保持 `end(0.30)<start` 不变量，且底噪上沿 0.27 之上留安全边——「的档案」0.25–0.29 弱尾域被判停线切后，判起线在续听期接得住。

### 4. 续听窗恢复 + 有界总上限（S69）——把「判停激进」的代价中和

- `CONTINUATION_TICKS` 1→10（VoiceSessionManager.kt:1353-1358；S55 曾因死锁暂关至 1）+ 新增 `CONTINUATION_MAX_TICKS=30`（:1360-1362）：hold 期**冻结块也计数**（tickContinuation :850-853），累计到顶无视冻结强制派发（:860-863）——S55「冻结永不解除 → hold 无限累积 parts=8/66s」死锁的硬顶防线，冻结也最多挂 3s 必派发。
- 切尾片段在窗内续说则倒计时冻结、下一片段累积合并（holdForContinuation :829-842 累积+重置窗口，dispatchContinuation :874-905 拼接成单条 utterance 走原派发语义）——「的档案」类弱尾在窗内续说被拼回整句。板测 parts=2/3 合并 12+ 次零失败。

单测：VadDetectorTest S69/S69b/S69c 系 6 测，811 绿基线。

## Why This Works

判停正确性不再依赖 floor 精确跟踪——本板三个失败模式已实证 floor 不可靠：

- **熔断用不被噪声摆动破坏的证据**：摆动的是绝对电平，「证据窗全部块 ≪ 语音峰值」这个相对关系在底噪摆动下稳定成立；且对说话期自锁（peak 含当前块），不会把正在说的话收掉。
- **钳制给 floor 污染的后果设硬上限**：判起/判停线被夹在 `[provider 下限, 0.32/0.30]` 带内，floor 再怎么被污染，最坏退化回「固定阈值 VAD」，而不是发散成全聋/贴弱音节。
- **合并语义中和激进判停的代价**：判停线选择从「一步错全错」变成「错一步可撤」——切早了，续听窗内拼得回来；这才允许判停线取偏灵敏的值。
- **取证方法论——双向失败取证法**：同一参数族在轻声（判停过钝）与响亮+气口（判停过锐）两个方向的对偶失败，把参数可用域从两侧夹死，即可证明问题不在参数**值**而在参数**化方式**（floor 本身），从而正当化「换机制」而非「继续调参」。执行面配合两条金线：vad diag level 每秒采样轨迹（电平 vs 阈值带可视化）+ 轮转日志三线对时（VAD utterance / S51 continuation / ASR text）定位临界带。

## Prevention

1. **反馈控制环做阈值必须审计驱动信号的故障态行为**：EMA 跟踪环的驱动信号若包含「它要区分的另一态」的信号（本例：上攻通道的驱动含说话电平），说话期=故障态即污染源。审计问句：这个环在被控两态里各会被推向哪？推向错误态的通道必须在故障态断开。
2. **计数器互相清零的死锁模式**：两个状态机（belowTicks 判停 / sustainedAbove 上攻）共享同一条判定线（骑在噪声带上的 end 线）时，线两侧的信号摆动会互相打断对方计数，谁也计不满。共享判定线的计数器要么合并，要么必须有第三条独立证据线（熔断）兜底。
3. **TDD 变异锚真红验证**：删掉熔断块重跑熔断测试必须变红（变异测试锚），防「测试绿但保护已死」——S56/S58 注释假锚（文字称停用、常量已复活）的教训是声明与代码可以静默脱钩，只有变异验证能钉住保护存在。
4. **测试构造必须忠实落在故障域**：T 稀疏 `[F,F,F,T]` 与 T 密集序列天壤之别（一个死锁、一个自愈）。复杂状态机的测试输入序列，先用全保真模拟（Python 状态机镜像）预演确认落在故障域，再落到 JVM 单测。
5. **归因引用注释前先对代码验证**：注释声明「X 已停用」必须在 X 的定义处有常量佐证（一眼可验 `NOISE_ATTACK_ENABLED` 的值）；DEV_LOG/类注释引用过时注释做归因是首轮分析跑偏的直接原因。

## 相关文档

- [wake-ack-echo-blind-backfill-phantom-utterance](../logic-errors/wake-ack-echo-blind-backfill-phantom-utterance.md)——VAD 话轮边界误判家族的判起侧镜像（回声伪 utterance）；本篇是判停侧。两篇同守 VoiceSessionManager 半双工语义。
- [energy-gate-drop-breaks-streaming-kws](../logic-errors/energy-gate-drop-breaks-streaming-kws.md)——能量判据语义家族姊妹篇（「阈值行为须按状态机语境设计」同构规则）。
- 防复发规则供给链：[aec-spike-null-evidence](../workflow-issues/aec-spike-null-evidence-feed-verification.md)（fed 计数取证）→ [unanchored-code-silent-loss](../workflow-issues/unanchored-code-silent-loss-in-rebuild.md)（钳制常量须测试锚）→ [guard-comment-anchors](../workflow-issues/guard-comment-anchors-must-bind-tested-wiring.md)（锚须在被测接线）——本文档 Prevention 3/4 逐级引用。
- 鉴别诊断：「全聋/说完被吞」须先排除 [rk3568s-usb-audio-hal-fd-leak-mic-dead](../hardware-environment/rk3568s-usb-audio-hal-fd-leak-mic-dead.md)（硬件零馈入）与 [kws-decode-starvation](../workflow-issues/kws-decode-starvation-timeline-tearing.md)（调度撕裂）两个非算法形态。

