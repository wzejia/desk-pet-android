---
title: 唤醒后再喊唤醒词被静默丢弃——stripWakePrefix 只剥词不剥标点，纯唤醒词 utterance 走旧 IDLE 死路（「唤不醒」主诉元凶）
date: 2026-08-06
last_updated: 2026-08-18
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
problem_type: logic_error
component: assistant
symptoms:
  - "用户主诉：喊「小朵小朵」有概率无法唤醒（实际是唤醒成功后又喊了一次被静默丢弃）"
  - "LISTENING 期 ASR 返回纯唤醒词（如「小朵小朵。」）后无任何反馈、直接回 IDLE"
  - "真机 19:09 四轮实证：唤醒→用户在「我在」播完后再喊唤醒词→静默结束"
root_cause: logic_error
resolution_type: code_fix
severity: high
tags: [wake-word, strip-prefix, empty-utterance, dead-end-path, kws, asr, silent-drop, voice-pipeline]
---

# strip-empty 死路：纯唤醒词 utterance 应重播「我在」继续聆听，而非静默回 IDLE

> **历史标注（2026-08-18 补）**：本篇的**解法行为已被 2026-08-10 二次根因修复整体回退**——「重播我在继续聆听」在真机 8 轮实证中形成死循环（聆听期纯唤醒词 → 重播 → 用户再喊 → 永不回 IDLE），改回 `backToIdle("wake-only utterance")` 让 KWS 接管（现行代码 `VoiceSessionManager.kt:467-472` 注释与分支即此回退；对应测试断言已随行为更新）。本篇价值在**问题定位方法论与 Prevention 1-3**（剥离空判用内容谓词/分支完备性/防误伤成对条件），正文以下内容按 2026-08-05~08-09 时段史实读。另：`playAckThenListen` 现仅剩唤醒成功一个调用点；应答音音源自 S9（2026-08-18）起为四层兜底动态解析，见 `docs/solutions/design-patterns/wake-ack-audio-selection-cosyvoice.md` 增补节。

## Problem

唤醒流程为「唤醒词 → 播『我在』→ LISTENING」。用户在「我在」期间或刚播完时重复喊唤醒词，ASR 会返回一个纯唤醒词 utterance（如「小朵小朵。」）。`stripWakePrefix` 剥离唤醒词后只剩标点（"。"），旧代码将此视为「空内容」静默回 IDLE——用户视角就是「我喊了小朵小朵，它没反应」= 唤不醒。

## Symptoms

- 「有概率无法唤醒」主诉（2026-08-05 真机用户反馈）；实际 KWS 唤醒成功，死在第二轮 utterance 处理。
- 静默丢弃零日志：旧路径不回日志、不播反馈音、不留气泡，排障时无从下手。

## What Didn't Work

- **首版修复条件 `stripped.isEmpty()`**（TDD 首红实锤）：漏判「小朵小朵。」——`stripWakePrefix` 只剥词不剥标点，stripped="。" 非空，新分支不可达，测试 `pureWakeWordUtteranceReplaysAckAndKeepsListening` 断言 playCalls==2 得 1 红。
- **排查期误方向**：一度怀疑「我在」音频被掐头（文件层 0.45s 头静音 + 尾字陡切是另一独立问题，见应答音选型篇）；分层排除（文件层 vs 播放层）后才定位到 utterance 分类缺陷。

## Solution

修复 commit **3377530**（本仓 main，单仓无 PR 流程）。空判从「空串」改为「无有效内容」（VoiceSessionManager.kt:410-413）：

```kotlin
val onlyWakeWord = stripped.none { it.isLetterOrDigit() } && userText.trim() != stripped
if (onlyWakeWord && wakeAck != null && !turn.sealed && currentTurn === turn) {
    logger("wake-prefix strip 后为空（纯唤醒词），重播我在继续聆听: $userText")
    playAckThenListen()
}
```

两个条件缺一不可：`none { isLetterOrDigit() }` 把「。」归入空；`userText.trim() != stripped` 防误伤——用户真说了一个纯标点句子（未含唤醒词、未走剥离）时不算。`playAckThenListen()`（L210）是唤醒应答/重听的共用入口：置 wakeAckActive → JUMPING→WAITING 动画 → SPEAKING 播「我在」→ 播完回 LISTENING；sealed/旧轮守卫防迟到触发。

测试：`VoiceSessionManagerWakeAckTest.pureWakeWordUtteranceReplaysAckAndKeepsListening`——唤醒 → release → LISTENING → onUtterance("小朵小朵。") → 断言 playCalls==2、ASR/LLM 零新请求（server.requestCount==1）、回 LISTENING、kwsPaused=true。变异锚：删重播调用 → 红。

真机复验：12:17:03 日志「重播我在继续聆听」→ 用户接续提问成对话。

## Why This Works

utterance 的分类维度被实现漏了一档：「剥离后为空」≠「空串」，剥离子串后残留标点也是空。更深层的设计缺陷是**静默死路**：一条没有任何反馈的分支，把可恢复场景（用户重复唤醒）做成了用户眼中的故障。修复同时补了行为（重听）与可观测性（logger）。

## Prevention

1. **剥离/清洗函数的「空」判定用内容谓词不用串谓词**：`none { isLetterOrDigit() }`（或等价正则）覆盖标点残留；`isEmpty()`/`isBlank()` 不够。
2. **分支完备性评审**： utterance/输入分类的每个分支问「用户此刻得到什么反馈」。静默 drop 分支必须自带日志 + 显式设计理由，否则视为缺陷。
3. **防误伤成对条件**：放宽匹配（标点算空）时必须配收窄条件（原文≠剥离结果）防过杀，两条件一起进测试断言。
4. **共用入口抽取**：应答音播放当时有两个调用点（唤醒成功、纯唤醒词重听），改应答音/动画时漏一个即漂移——`playAckThenListen()` 单入口已沉淀进 CLAUDE.md 架构速览。（2026-08-18 注：纯唤醒词重听调用点已随上文回退移除，现仅剩唤醒成功一处。）

## Related Issues

- `docs/solutions/design-patterns/wake-ack-audio-selection-cosyvoice.md` — 同批修复④：应答音内容选型（「我在」→「哎,我在。」），与本篇共享 `playAckThenListen()` 入口。
- `docs/solutions/logic-errors/delta-callback-check-then-act-ghost-bubble.md` — 同批修复①：同文件异步守卫族。
