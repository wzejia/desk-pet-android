# 兜底人工评审（ocr 机扫三次超时代偿）

- 日期：2026-10-03
- 评审人：Claude（人工通读，逐行）
- 范围：5 个 ocr 失败文件，全量精读（大文件分块读毕）
- 级别口径：P1=可直接利用的安全洞 / 会崩溃或丢数据；P2=真实但难触发或有兜底；P3=卫生类
- 声明：本审计只读，未修改任何被审文件；跨文件结论已用只读检索核实（PetOverlayService / WakeBlindWindowBuffer / VadDetector 常量）。

---

## 一、逐文件审阅纪要

### 1. chat/MarkdownRenderer.kt（273 行）

结构：纯 Kotlin object，零 Android import。`parse()` 按行扫描产出 `Block` sealed 层级（段落/列表/代码块/标题/分隔线/引用/GFM 表格），`parseInline` → `parseBold` 做行内片段（code 优先、`**` 优先于 `*`、流式未闭合按字面值保留）。`Table.colMaxUnits` 为派生列宽提示（不参与 equals）。`displayUnits` 按码点 ≥0x2E80 计宽 2（emoji 代理对各计 2，与注释一致）。

关注点核查：`when` 各分支均推进 `i` 无死循环；表格起点要求分隔行已到齐（流式安全）；`TABLE_SEP` 无灾难性回溯（每次组迭代强制吃 `|`）；未输出任何 HTML/URI，纯数据类交给 Compose 渲染，无注入面；正则均为行级锚定，无 ReDoS 实害。

### 2. cloud/VolcengineAsrClient.kt（227 行）

结构：火山 SAUC bigmodel ASR 的 WebSocket 二进制协议客户端（纯 Kotlin）。`transcribe` 同步阻塞（CountDownLatch 15s 超时），onOpen 内一次发完配置帧 + 分片 PCM + 末帧；`onMessage` 解析服务端帧取 result 文本；companion 提供 WAV 剥 PCM 与帧编解码。

关注点核查：API key 仅进 wss 请求头、不入日志；`resultText`/`error` 跨线程写由 latch 建立 happens-before；`parseServerFrame` 各 copyOfRange 分支均有长度卫兵，无越界；15s 超时 + `ws.cancel()` 兜底无永久等待；音频帧大小换算（16000/10*2=3200B=200ms）正确。

### 3. ui/overlay/BubbleTextStrip.kt（333 行）

结构：气泡版 markdown 渲染。一次性 `render()`（行级降级 + 行内 span）；`Session` 流式增量——内部缓冲全量 raw，每 delta 用 `findHoldback` 求「安全前缀」重渲染，前缀稳定则差分吐尾、否则 reset 全量重打。hold 上限：反引号 48 字 / 斜体 24 字。

关注点核查：`prefix`/`dropStart`/`plus` 区间裁剪与偏移正确；QUOTE 剥除循环必终止（`>​+` 至少消费 1 字符）；`renderInline` 递归深度受标记配对约束（闭合取首个配对，无法深嵌套）；空 code span 在流式与一次性路径语义一致。核心问题在「流尾无 flush」（见 F-BT-1）。

### 4. voice/NlmsEchoCanceller.kt（324 行）

结构：NLMS 自适应回声消除。参考环形缓冲 16384 样本单写（配速线程 push）单读（采麦线程 process）；逐样本前向 + 权重更新，窗能量增量维护；块尾三件套=漏泄 + 周期互相关配对平移校正（`maybeAlign`/`xcorr`）+ 发散守卫（out>1.5×in 连续 3 块清零）。欠账钳 live 边、溢出快进、`@Volatile refWrite` 跨线程。

关注点核查：发散守卫与「宁可不消不放大」设计完备；`oldIdx` 的负数取模双 `%(+)%` 写法正确；字节编解码 LE 正确；24k→16k 3:2 重采样边界不越界；`refRead` 越过 refWrite 的欠账路径有钳制（128-129），但互相关校正路径存在未挡上界的缝隙（见 F-NL-2）。

### 5. voice/VoiceSessionManager.kt（1308 行，最重要）

结构：半双工四态语音会话状态机。麦块 `routeBlock` 按 router 分发（KWS/VAD/DROP）；唤醒路径 `playAckThenListen`（世代号防旧 wozaiJob 覆盖新信号）+ S40 唤醒盲区缓冲/接缝水位/S65 回声纪元守卫回灌；S51 断句续听（hold + 倒计时 + 合并派发，现窗=1）；管线 `runPipeline`：ASR → 唤醒词剥除 → 自回声判定（S58b）→ 本地意图（S26/S46）→ AG-UI 流式 → 逐字符分句 → Channel(4) 背压 → TTS 生产者/消费者流水播放；TurnFlags 单轮终态守卫（sealed + terminalFired CAS）防 cancel 残跑跨轮串写；多轮迁移有 currentTurn 一致性 + sealed 三重守卫。

关注点核查：cancel 封轮顺序（seal→stop→backToIdle）与终态恰好一次补发闭环严密；`currentTurn !== turn` 残跑屏蔽一致贯彻；S40/S65 回灌截止线逻辑自洽；`emptyAsrResumesLeft` 有上限防幽灵循环。发现 1 个 P2（runBlocking 不可取消导致线程永久挂死）与若干 P3（见下）。

---

## 二、Findings

### P2

**F-BT-1 [P2] 流式与整段路径均无流尾 flush，未闭合行内标记永久吞掉气泡尾巴**
- 文件：BubbleTextStrip.kt:276-284（`**` 分支无 hold 上限）、BubbleTextStrip.kt:310-332（`Session` 只有 `feed` 无 flush）；佐证：PetOverlayService.kt:534、563（调用方只取 `feed().text`，无收尾全量渲染）
- 原文：`if (close >= 0) i = close + 2 else return lineStart + i`
- 影响：LLM 回复最后一行以未闭合 `**`（任意尾长）、未闭合 `*`（其后 ≤24 字）或未闭合反引号（其后 ≤48 字）收尾时，`findHoldback` 返回 hold 点且再无后续 delta，标记之后的内容永久不上屏。`showBubble` 整段喂入路径走同一 `feed`，同样丢尾。一次性 `render()` 的规则是「丢标记留内容」，流式路径与它不一致且无收尾兜底——静默 UI 文本丢失，用户感知「回答缺后半句」。反例样本：feed("注意**这里是重点") → 气泡只显示「注意」。
- 建议：`Session` 增加 `flush(): Emit`（对全量 raw 走 `render()` 一次性规则差分补发），调用方在 RUN_FINISHED / 收泡前调用；或最小改动——给 `**` 分支加同 `BACKTICK_MAX` 体例的 hold 上限，超限丢标记留内容放行。

**F-VSM-1 [P2] `runBlocking { sentences.send }` 不可取消，barge-in 命中阻塞窗时 Dispatchers.IO 线程永久挂死**
- 文件：VoiceSessionManager.kt:1047（runBlocking send）；VoiceSessionManager.kt:1131-1156（producer 只 `break` 不 close `sentences`）；VoiceSessionManager.kt:1180-1184（consumeSentences finally 只 close `audioQueue` + cancel producer）
- 原文：`runBlocking { sentences.send(s) }` / producer 侧 `if (turn.sealed) break`
- 影响：LLM 流式快于 TTS 播放时 `sentences`(容量 4) 打满，管线线程阻塞在 `runBlocking` 内（该阻塞不属于 pipelineJob 的协作取消域）。此时唤醒词打断/按住打断 → `pipelineJob.cancel()` 无法中断它；producer 收到下一句才看到 sealed 并 break 退出，`sentences` 从此无读者，被阻塞的 `send` 永不返回——每发生一次永久泄漏 1 个 IO 线程，反复打断长回复可逐渐耗尽 Dispatchers.IO（默认 64 线程），拖垮全 App 网络路径。`runPipeline` 的 catch(CancellationException) 里 `sentences.close()`（1112 行）永远执行不到。
- 建议：最小修——`consumeSentences` 的 finally 里补 `sentences.close()`（解除阻塞 send 抛异常，顺 runBlocking → voiceChat → runPipeline catch 正常拆解）；或 `handleDelta` 改用 `trySend` + sealed 短路，消除 runBlocking。

### P3

**F-MD-1 [P3] 超长无换行单行时行内解析 O(n²)**
- 文件：MarkdownRenderer.kt:250、258（`s.indexOf("**", i + 2)` / `s.indexOf('*', i + 1)` 逐标记前向扫描）
- 影响：数十 KB 无换行且含大量 `*` 的单行（LLM 病态输出）解析退化为 O(n²)；常规长度无感。
- 建议：如遇实例再优化（预扫标记位置表）；当前可挂账不动。

**F-MD-2 [P3] 空 code span `` `` `` 两套渲染器行为分叉**
- 文件：MarkdownRenderer.kt:206-219 vs BubbleTextStrip.kt:183-186
- 影响：chat 页保留两个字面反引号，气泡剥除不吐内容；两表面语义不一致（各自有注释但互不引用）。
- 建议：择一统一，注释互指。

**F-ASR-1 [P3] stripWavPcm 对损坏 WAV 的负 chunkSize 可倒退循环**
- 文件：VolcengineAsrClient.kt:177-178
- 原文：`i += chunkSize + 8 + (chunkSize and 1)`
- 影响：chunkSize ≤ -9 时 `i` 递减，随后 `ByteBuffer.wrap(wav, i+4, 4)` 抛 IndexOutOfBoundsException（极端值可长时间空转）。输入为自录 WAV，需文件损坏才触发。
- 建议：循环体加 `if (chunkSize < 0) break`。

**F-ASR-2 [P3] 服务端 JSON 解析失败静默吞掉，只能等 15s 超时**
- 文件：VolcengineAsrClient.kt:86
- 原文：`runCatching { JSONObject(payload.decodeToString()) }.getOrNull() ?: return`
- 影响：末包若解析失败，错误原因丢失，表现为固定 15s 慢失败而非即时明确报错；排障困难。
- 建议：解析失败时 `error = ...; done.countDown()` 或至少 logger。

**F-NL-1 [P3] micHist 环形读出非时序，xcorr 的「旧→新」假设仅在全量样本数 ≡0 (mod 800) 时成立（当前被统一块长掩蔽的潜伏缺陷）**
- 文件：NlmsEchoCanceller.kt:149-150（环形写）、241-250（`xcorr` 按 `micHist[i]` ↔ `refRead - 800 + i` 配对，242 行注释自称旧→新）
- 影响：`micHist[0..799]` 是真实时序窗的循环旋转（旋转量 = micHistCount mod 800 ≠ 0 时），互相关峰被污染（相干样本占比 (800-k)/800，其余与窗外参考错配）。当前生产路径统一 1600 样本/块（1600·m ≡ 0 mod 800），任何块数都对齐、缺陷完全掩蔽（单测 320 样本 ×10 块同理）；一旦未来出现非整块喂入（部分读、变长块、测试改块长），对齐即静默失准。采集掉块丢的是整块，不破坏均匀性，故板端实测未暴露。
- 建议：xcorr 前按 `micHistCount % 800` 做旋转展开（或维护成对齐的线性历史）；至少加注释钉死「仅当块长整除 800 时正确」的约束。

**F-NL-2 [P3] 互相关配对平移可越过 live 边，xcorr 未挡上界**
- 文件：NlmsEchoCanceller.kt:234（`refRead += shift` 无 refWrite 钳制）、246（`if (absPos < 0 || absPos < refWrite - refCap) continue` 只挡下界）
- 影响：正向 shift（至 +1536）可把 refRead 推过 refWrite，下一轮 xcorr 对 `absPos ≥ refWrite` 的「未来」位置读到环内陈旧旧样本参与评分；下一块欠账钳制会拉回，但评分噪声可能造成一次错误对齐。126-127 行注释明确知晓越 live 边危害，但只对欠账路径设防。
- 建议：`refRead += shift` 后钳 `minOf(refRead, refWrite)`；xcorr 补 `absPos >= refWrite` continue。

**F-NL-3 [P3] maybeAlign 的 runCatching 无失败日志**
- 文件：NlmsEchoCanceller.kt:207
- 影响：对齐路径异常静默，遥测缺一块。
- 建议：`.onFailure { logger(...) }`。

**F-VSM-2 [P3] VAD Timeout 路径绕过 backToIdle，不清续听挂起**
- 文件：VoiceSessionManager.kt:766-772
- 影响：30s 超时直接 `transition(IDLE)` + `kwsPaused=false`，未走 `clearContinuation()`（backToIdle 纪律外的旁路）；挂起 parts 依赖下一次 startListening/backToIdle 兜底清理。当前因续听窗(1 块) ≪ 30s 实际难残留，属纪律缺口。
- 建议：改走 `backToIdle("listen timeout")`。

**F-VSM-3 [P3] stopListening 与采麦循环跨线程并发喂 VAD，无锁**
- 文件：VoiceSessionManager.kt:583-589（主线程 `vad.feed` 静音块）vs 748（Default 线程 `vad.feed`）
- 影响：两线程并发进入 VadDetector 内部状态（本文件可见调用面，检测器内部未审）；同时两路 onUtterance 的 `pipelineJob?.isActive` 检查（899 行）是 check-then-act，窄窗内可能双管线（currentTurn 换主可屏蔽大部分后果）。
- 建议：VAD 喂入收口到单线程（如 stopListening 的静音块经 Channel 投递），或在 VAD 内加锁。

**F-VSM-4 [P3] onUtterance 进 PROCESSING 不清 continuationParts，竞态残留可并入下一轮**
- 文件：VoiceSessionManager.kt:898-904（无 clearContinuation）；对照 444/857（startListening/backToIdle 均清）
- 影响：stopListening 直派 utterance 与采麦线程 hold 竞态同时发生时，已 hold 的片段在 PROCESSING 期冻结（无 tick 驱动），多轮回 LISTENING 后新片段与其合并 → ASR 收到含陈旧音频的拼接句。窗口窄、后果为单轮识别质量。
- 建议：`onUtterance` 起管线前 `clearContinuation()`（幂等，代价零）。

**F-VSM-5 [P3] 语音转写全文进 logcat（隐私）**
- 文件：VoiceSessionManager.kt:940（`logger("ASR text: $userText")`）、955/958（userText 入日志）
- 影响：用户语音内容（可能含个人档案名等敏感词）落入系统日志，任何有 READ_LOGS 的场景可读；属隐私卫生问题，非可利用洞。
- 建议：release 构建降级为长度/哈希，或挂 debug 开关。

**F-VSM-6 [P3] voiceChat 两段逐字重复的 AG-UI 调用块**
- 文件：VoiceSessionManager.kt:122-138 vs 139-155
- 影响：仅差一个 `NetConfig.llmIsCloud` 判断，onToolCallEvent/onToolCallOutcome 闭包整段复制；改一处漏一处的维护风险。
- 建议：抽私有函数 `aguiChat(stripped, onDelta, threadKey, roundId)`。

---

## 三、统计与说明

| 文件 | P1 | P2 | P3 | 结论 |
|---|---|---|---|---|
| MarkdownRenderer.kt | 0 | 0 | 2 | 整体健壮，仅性能/一致性卫生项 |
| VolcengineAsrClient.kt | 0 | 0 | 2 | 协议实现正确，边界卫生项 |
| BubbleTextStrip.kt | 0 | 1 | 0 | 流尾无 flush 的静默吞尾是唯一实质缺陷 |
| NlmsEchoCanceller.kt | 0 | 0 | 3 | 当前被块长均匀性掩蔽的潜伏对齐缺陷 + 两处卫生项 |
| VoiceSessionManager.kt | 0 | 1 | 5 | 状态机守卫严密；runBlocking 线程挂死是唯一实质缺陷 |
| **合计** | **0** | **2** | **12** | 无 P1 |

- 不确定项：F-NL-1 的实际可触发性取决于 AudioCapture 是否恒产出 3200 字节整块（已按 VadDetector.BLOCK_BYTES=3200 与统一采集推断，未逐行审 AudioCapture 本体）；F-VSM-1 的触发频率取决于 LLM 流速与 TTS 播速的差、以及用户打断时机。
- 两处 P2 均有明确最小修法且互不耦合，可独立挂票。
