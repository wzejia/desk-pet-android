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
| silence_end_ticks | 8 块（800ms） | 6 块（600ms） | ⚠️ Android 独立演进至 6 块（S52 晚回调），见补注 4 |
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
> **Corrigendum（2026-09-11）**：①麦块路由 Android 侧两轮演进（桌面快照仍为旧口径）：S12 起 speaking/processing **恒进 KWS**（唤醒词打断）；S33 起 listening **主投 VAD+旁路 KWS**（聆听期重唤醒，修复盲区必漏）。②gate 0.015 语义不变，但 14.18 板校准曾顶格 0.05（p95×2.5 钼 MAX）吞弱音节致漏检，已回调默认；公式与钼位见 `EnergyGate`。③新增影子观测通道（S22：0.15，仅播报/处理期）；S34 全时段喂流实测 rk3568 双引擎吞吐红线（CPU 202%/主引擎撕裂 56%）已回退转 BLOCKED。
> **Corrigendum（2026-10-21，S69 系判停链重构回填，挂账②销账）**：Android 判停链参数体系重构（票C 三包 27 轮真声闭环，DEV_LOG 2026-10-21（十）~（十四）），锚 `voice/VadDetector.kt` + `voice/VoiceSessionManager.kt`：
>
> | 参数 | 现行值 | 票 | 语义 |
> |---|---|---|---|
> | STALL_MIN_SPEECH_TICKS | 10（1s） | S69 | 底噪熔断最少说话态块数（判起后累计 1s 才允许熔断） |
> | STALL_PEAK_RATIO | 0.6 | S69 | 熔断低峰比：证据窗（plateauWindow 最近 10 块）全部 < 语音峰值 EMA×0.6 →「窗内无语音」强证据强制收句 |
> | CONTINUATION_TICKS | 10（1s） | S69 | 续听合并窗（S55 曾暂关=1，因（九）新证据恢复 10——句中气口 >600ms 切尾需窗内合并） |
> | CONTINUATION_MAX_TICKS | 30（3s） | S69 | 续听有界总上限：hold 期冻结块也计数，到顶强制派发（S55「冻结永不解除」死锁硬顶） |
> | START_LINE_MAX | 0.32 | S69b 立 0.35→S69c 修 0.32 | 判起线绝对钳：max(生效判起线, min(floor×2.0, 0.32))——floor 被污染（上攻/S56 raise）后判起线不得超此值（全聋防御+弱尾救回；0.32 保持 end<start 不变量、底噪上沿 0.27 安全边 0.05） |
> | END_LINE_MAX | 0.30 | S69b | 判停线绝对钳：max(0.03, min(floor×1.4, 0.30))——贴板噪上沿 0.27，防判停线爬进说话弱音节域切尾 |
> | NOISE_ATTACK_ENABLED 语义 | true 但 **说话期禁用** | S69b | 上攻通道加 `!isSpeaking` gate——S58「判停可用性优先」取舍被板测新证据推翻（说话期上攻误触发推 floor 0.5 封顶致全聋），floor 学习只在未判起期，判停兜底改由熔断接管（不依赖 floor） |
>
> 物理极限注记：0.19-0.27 域（贴底噪）极轻尾词救不了（VadDetector 类注释既录），用户端对策=尾词稍加重音。说完全链响应 ≈1.6-2s（判停 600ms+续听窗 1s 固有延迟，用户已接受）。挂账：熔断基准跨轮 peak EMA 污染（评审 finding#1，观察「utterance+hold 每 ~1.3s 循环」日志形态再落地 per-utterance peak 修法）。

### 状态机与半双工（sidecar.py，§3.5）

- 四态 `idle/listening/processing/speaking` ✓；麦块分发：IDLE→KWS、LISTENING→AsrListener、processing+speaking 丢弃 ✓。
- 半双工：状态分发 + KWS pause flag + 单条采麦流 ✓；多轮 TTS 播完回 LISTENING ✓。
- 唤醒词前缀兜底 `["小朵小朵", "小朵", "小度小度"]` ✓（S40 起 Android 追加「我在」，见补注 5）。

## 补注（已回写知识包 / 留档）

1. **listen_timeout_s 默认值陷阱**：`AsrListener.__init__` 默认 8.0，但 sidecar.py:397 显式传 `30.0`，生效值 30s。Kotlin 实现**直接硬编码 30s**（勿照抄类默认值）。桌面端 `sidecar.py:522` 文档串里「8s 超时计时」是过期注释（桌面仓库只读，不回改）。
2. **知识包 §7.1 #6 自相矛盾已澄清**：#6 说「仅 `。！？` + flush 阈值 200」，与 §3.1 表（`。！？!?;`+\n，MIN 4/MAX 30）冲突——#6 是 voice-assistant 项目的历史教训描述，**当前 HEAD 权威 = §3.1 表**。已在知识包 #6 补一行澄清。
3. `voice_listen.py` 类文档串里 min_bytes「× 0.3（2880 字节）」是阶段一残留描述，实际代码无 0.3 系数（9600 字节）——桌面仓库只读不回改，Kotlin 按 9600 字节实现。
4. **【Android 有意偏离】silence_end_ticks 8 -> 4（800ms -> 400ms）**：2026-08-29 用户拍板端点提速（「说完 ~1s 才结束 LISTENING」体验问题）。代价：句中停顿 >400ms 会被截断成多句（VAD 单次触发，截断即半句进 LLM）。**【2026-09-11 再改：4 -> 6（400ms -> 600ms）】**用户拍板放宽：400ms 截断实际使用中频发（半句进 LLM 答非所问），600ms 为「端点响应 vs 句中停顿保护」折中点。KWS 能量挂起窗 GATE_HANGOVER_BLOCKS 保持 8 不联动（保护对象是唤醒词字间气口，语义不同）。桌面端仍 800ms 不动。**【2026-09-18 S50 再改：6 -> 8（600ms -> 800ms）+ 判停线拆分】**轻声说话实测句尾四连切（「盘点当前有哪」等，maxLevel 0.31~0.47 vs 正常 0.83，弱音节 RMS 跌破 0.05 被计静音）——判停窗回桌面端原值 800ms；同时新增独立判停线 SPEECH_END_LEVEL=0.03（不走 vad_speech_level：校准公式 coerceIn(0.05,0.15) 在安静域钳在 0.05，数学上无法低于 0.05），弱音节 0.03~0.05 域计回说话。判起线保持 max(0.05, floor×2.0) 不变，环境校准联动语义不变。**【2026-09-18 晚 S52 后再调：8 -> 6（800ms -> 600ms）】**S52 探针定案停用上攻通道 + end 线拆分 0.03 后，原「弱音节/尾段被吞」根因已除，回调 600ms 降延迟（用户拍板）；总静音容忍 = 600ms + 续听窗 1000ms ≈ 1.6s。KWS 挂起窗保持 8 不变。
5. **【Android 有意扩展】唤醒前缀剥除表追加「我在」**：2026-09-14 用户拍板。背景：S40 唤醒应答期盲区回灌（见 CONSTRUCTION.md S40）后，ack 回声残余在 NLMS 参考过期（>400ms）时未被消净，ASR 常转出「我在。」被当指令发 LLM（幽灵轮，现场 29 次回灌中可见 5 例）；混合形态「我在，查询档案」话头带应答残留。追加「我在」后：纯「我在。」剥空走 onlyWakeWord 回 IDLE（幽灵轮消失）、混合形态话头剥净。已知代价：指令恰以「我在…」开头会被误剥（命令式对话极少见）。桌面端不动。

## Kotlin 验收清单（M3 逐项打勾，**2026-10-21 按 S69 系现行口径刷新**）

- [x] speech_level 0.05 / start 3 / end 6（S52 晚回调，补注 4）/ **end_level 0.03（S50 拆分）** / min 3（9600B）/ timeout **30s** / max 8s / pre_roll 3 / deque **6** / 块 100ms@16k
- [x] **S69 系**：熔断 STALL_MIN_SPEECH_TICKS=10 + STALL_PEAK_RATIO=0.6（证据窗=plateauWindow 10 块）/ 续听 CONTINUATION_TICKS=10 + CONTINUATION_MAX_TICKS=30 / 判起钳 START_LINE_MAX=0.32 / 判停钳 END_LINE_MAX=0.30 / 上攻说话期禁用（2026-10-21 Corrigendum 表）
- [x] 分句：`。！？!?;`+\n，MIN 4，MAX 30，词边界回退，逐字符喂，残留收尾
- [x] 消毒：白名单 + alnum/space + **连续空白压缩** + 空则跳过
- [x] KWS：threshold 0.3（08-19 Corrigendum）/ gate 0.015（可配置）/ threads 2 / 命中 reset
- [x] 状态机四态 + 麦块分发 + KWS pause + 单采麦流 + 前缀兜底
- [x] **采麦源 4 档**（2026-10-21 票D）：0→7(VC)/1→6(RECOGNITION)/2→9(UNPROCESSED)/3→1(MIC)，未知归 7；coerceIn(0,3)；设置页可点击循环 + SourceDrift 漂移告警网（AudioCaptureSourceDriftTest 锚组 1-4）
