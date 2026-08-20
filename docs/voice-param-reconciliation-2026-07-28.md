# 语音链路参数对账表（单一事实源）

日期：2026-07-28 ｜ 对应 ticket：`.scratch/xiaoduo-android/issues/07-vad-param-reconciliation.md`
权威源：`E:\desk-pet-migration\desk-pet` 当前 HEAD（`pet/chat/voice_listen.py` / `sidecar.py` / `providers/tts_orchestrator.py`）
用途：**Kotlin 实现逐项对照验收**（知识包 §7.7 #47：双栈参数漂移已发生两次，第三栈必须先对账）。

## 结论：知识包 §3.4 / §3.1 / §3.5 / §5 与 HEAD 全部一致，仅 2 处需补注

### VAD（voice_listen.py `AsrListener`，sidecar.py:396 实例化）

| 参数 | HEAD 生效值 | 知识包 §3.4 | 对账 |
|---|---|---|---|
| speech_level | 0.05（rms/8000 归一化） | 0.05 | ✅ |
| speech_start_ticks | 3 块（300ms） | 3 块 | ✅ |
| silence_end_ticks | 8 块（800ms） | 8 块 | ✅ |
| min_utterance_ticks | 3 块（min_bytes = 3×1600×2 = 9600） | 3 块 | ✅ |
| listen_timeout_s | **30.0**（sidecar 显式传参；类默认值 8.0 未生效） | 30s | ✅（⚠️ 补注 1） |
| max_utterance_s | 8.0 | 8s | ✅ |
| pre_roll_ticks | 3 块 | 3 块 | ✅ |
| pre-roll deque 容量 | 6（= 判起 3 + pre_roll 3） | 6 | ✅ |
| 块 | 100ms @16kHz Int16 mono（3200 字节） | 同 | ✅ |
| 行为 | 单次触发后停，调用方重启；未判起只滚 pre_roll；说话中只累积说话块 | 同 | ✅ |

### 分句（tts_orchestrator.py）

| 参数 | HEAD | 知识包 §3.1 | 对账 |
|---|---|---|---|
| SENTENCE_END_CHARS | `。！？!?;` + `\n` | 同 | ✅（⚠️ 补注 2） |
| SENTENCE_MIN_CHARS | 4 | 4 | ✅ |
| SENTENCE_MAX_CHARS | 30 | 30 | ✅ |
| 长句切点回退 | 切口在 ASCII alnum 中间 → 回退词边界（split_long_cut） | 同 | ✅ |
| 喂法 | LLM delta 逐字符喂 splitter | 同 | ✅ |
| 收尾 | 流结束残留作最后一句（strip 后非空才发） | 同 | ✅ |

### TTS 消毒（sidecar.py `_sanitize_for_tts`，§3.1）

- 白名单标点：与知识包 §3.1 **逐字符一致**（句读/引号括号/破折省略/单位连字符）。
- 规则：`isalnum() or isspace() or in 白名单` → **压缩连续空白** → 无 alnum 返回空（跳过合成）。
- ⚠️ 知识包未提「压缩连续空白」这一步——Kotlin 照做（`cleaned.split().joinToString(" ")`）。

### KWS（sidecar.py，§5）

| 参数 | HEAD | 知识包 §5 | 对账 |
|---|---|---|---|
| keywords_threshold | 0.5 | 0.5 | ✅ |
| RMS 能量门控 | 0.015（整块不喂不判） | 0.015 | ✅ |
| num_threads / provider | 2 / cpu | 同 | ✅ |
| 块 / 采样 | 100ms @16k 直采直喂，Int16→float32/32768 | 同 | ✅ |
| 用法 | accept_waveform → is_ready/decode/get_result → 命中 reset | 同 | ✅ |

> **Corrigendum（2026-08-19）**：keywords_threshold 修订 **0.5 → 0.3**（2026-08-10 真机验证值，IDLE 态 10/10；08-18 仓库事故曾因无测试锚静默回退 0.5 致漏检回归）。锚：`voice/KwsDefaults.kt` + `KwsDefaultsTest`。本表为 07-28 历史快照，其余行不动。

### 状态机与半双工（sidecar.py，§3.5）

- 四态 `idle/listening/processing/speaking` ✓；麦块分发：IDLE→KWS、LISTENING→AsrListener、processing+speaking 丢弃 ✓。
- 半双工：状态分发 + KWS pause flag + 单条采麦流 ✓；多轮 TTS 播完回 LISTENING ✓。
- 唤醒词前缀兜底 `["小朵小朵", "小朵", "小度小度"]` ✓。

## 补注（已回写知识包 / 留档）

1. **listen_timeout_s 默认值陷阱**：`AsrListener.__init__` 默认 8.0，但 sidecar.py:397 显式传 `30.0`，生效值 30s。Kotlin 实现**直接硬编码 30s**（勿照抄类默认值）。桌面端 `sidecar.py:522` 文档串里「8s 超时计时」是过期注释（桌面仓库只读，不回改）。
2. **知识包 §7.1 #6 自相矛盾已澄清**：#6 说「仅 `。！？` + flush 阈值 200」，与 §3.1 表（`。！？!?;`+\n，MIN 4/MAX 30）冲突——#6 是 voice-assistant 项目的历史教训描述，**当前 HEAD 权威 = §3.1 表**。已在知识包 #6 补一行澄清。
3. `voice_listen.py` 类文档串里 min_bytes「× 0.3（2880 字节）」是阶段一残留描述，实际代码无 0.3 系数（9600 字节）——桌面仓库只读不回改，Kotlin 按 9600 字节实现。

## Kotlin 验收清单（M3 逐项打勾）

- [ ] speech_level 0.05 / start 3 / end 8 / min 3（9600B）/ timeout **30s** / max 8s / pre_roll 3 / deque **6** / 块 100ms@16k
- [ ] 分句：`。！？!?;`+\n，MIN 4，MAX 30，词边界回退，逐字符喂，残留收尾
- [ ] 消毒：白名单 + alnum/space + **连续空白压缩** + 空则跳过
- [ ] KWS：threshold 0.5 / gate 0.015（可配置）/ threads 2 / 命中 reset
- [ ] 状态机四态 + 麦块分发 + KWS pause + 单采麦流 + 前缀兜底
