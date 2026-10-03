# 修复简报 · voice 包(P2 共 14 条)

> 来源:全量审计 2026-10-03(unified-findings.md)。字段原样取自 p2-p3-detail.json,行号未改动。
> 逐条:先在源码验证问题属实(不属实→记「证伪跳过」+理由),属实→先写失败测试(TDD)→最小修复→测试转绿。禁改本简报地界外的文件。SEC-3 若在你的地界:注意 prefs 迁移要兼容板端存量数据。

---

## ROB-1  [ocr]
- 标题: AudioCapture start/stop 生命周期竞态
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt
- 问题: start/stop 对 job/gateToken 无共同锁（job 非 @Volatile），tryAcquire→赋值→launch 与并发 stop() 不原子；stop 先释放门再等协作取消；startRecording 错误码被忽略，isRunning 照置 true。
- 影响: 丢失 stop（麦克风滞留打开、门被占致后续 start 静默 no-op）或双捕获循环并行抢麦克风。
- 修法建议: start/stop 串行到同一锁；stop 把 token 交给 job 完成路径再释放；startRecording 失败即失败返回。
- ocr 索引: [23, 24, 25]
---

## ROB-2  [ocr]
- 标题: AudioCapture read 持续失败满速自旋
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt
- 问题: read() 持续返回非正（AudioRecord 卡错误态/启动失败）时读循环零退避无出口。
- 影响: 单核满速自旋，VAD/KWS 静默饿死，直到 job 被取消。
- 修法建议: 连续失败计数，超阈值记日志退出（finally 释放 AudioRecord 与门），重试间让出。
- ocr 索引: [26]
---

## ROB-14  [ocr]
- 标题: 云客户端 Result 契约异常边界缺失（家族）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/main/java/com/xiaoduo/deskpet/cloud/AliyunAsrClient.kt, app/src/main/java/com/xiaoduo/deskpet/cloud/OpenAIAsrClient.kt, app/src/main/java/com/xiaoduo/deskpet/cloud/OpenAIChatClient.kt, app/src/main/java/com/xiaoduo/deskpet/voice/TtsClient.kt
- 问题: Request.Builder.url 对用户可配畸形 baseUrl 抛 IAE（构造在 try 外）；resp.body!! 可 NPE；catch 只覆盖 IOException（回调异常/OkHttp ISE/Base64 IAE 逸出）；SSE feedLine 解析异常逸出 chat()；transcribe() 无顶层异常边界；TTS 流式 body!! 击穿文档化降级链。（同型镜像：OpenAITtsClient/VolcengineTtsClient）
- 影响: 畸形响应/错误配置/取消时序 → 未捕获异常崩溃调用方而非返回 Result.Error。
- 修法建议: 家族统一修：请求构造入 try、body 安全调用+null→Failure、catch 补 RuntimeException 映射 Error、feedLine runCatching 防御化。
- ocr 索引: [105, 129, 130, 231, 232, 267, 413]
---

## ROB-15  [ocr]
- 标题: WakeAckPrefetcher 半数步骤无护栏
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckPrefetcher.kt
- 问题: 仅 ttsSynthesize/cacheStore.write 有护栏；resolver.snapshot()/selector/WakeAckAudio.validate/process/WavCodec.pcmToWav 裸奔，异常直达线程默认 uncaught handler；catch 只捕 Exception（OOM 逸出）。违背类 KDoc「不让异常逃出线程」。
- 影响: 一个异常缓存文件即可杀死进程。
- 修法建议: run() 整体单层 try/catch(Exception) + PetLogger（可用 key 时记 backoff）。
- ocr 索引: [438]
---

## ROB-16  [ocr]
- 标题: AmbientCalibrator ioScope 无 Job
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/AmbientCalibrator.kt
- 问题: CoroutineScope(Dispatchers.IO) 无 Job，ioScope.cancel() 走扩展函数抛 IllegalStateException。
- 影响: 临时采集分支每次运行收尾必抛（掩盖采集结果），scope 级清理永不发生。
- 修法建议: CoroutineScope(SupervisorJob() + Dispatchers.IO)。
- ocr 索引: [379]
---

## CONC-1  [ocr,人工代码 F-VSM-3]  〔待复诊〕
- 标题: VadDetector 跨线程无同步（喂入双线程 + 32 位 ARM Double 撕裂）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
- 问题: feed() 同时来自 Default 采集循环与 stopListening() 线程，runPipeline(IO) 调 start/raiseNoiseFloorFromLastUtterance；仅 startLevelOverride @Volatile，noiseFloor 非 volatile Double RMW，缓冲/tick 状态无 happens-before（人工审调用方 VoiceSessionManager.kt:583-589,748，ocr 审检测器内部，互补）。
- 影响: 并发 feed 可损坏 ArrayList/ArrayDeque 与 tick 状态机；噪声地板丢更新；32 位 ARM 非volatile Double 写撕裂成垃圾阈值（板端 rk3568）。
- 修法建议: 公共入口一把锁，或全部收敛到单一音频消费线程（stopListening 静音块经 Channel 投递）。
- 复诊备注: ocr high vs 人工 F-VSM-3 P3 分级分歧（人工认为 currentTurn 换主屏蔽大部分后果）；合并后按 Double 撕裂+容器损坏可能保留 P2，可复核降级。
- ocr 索引: [419]
---

## CONC-2  [ocr]
- 标题: WakeBlindWindowBuffer ArrayDeque 双域竞态
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/WakeBlindWindowBuffer.kt
- 问题: add/drain 在采集收集协程（Default），clear() 可达 KWS 唤醒回调/UI barge-in 线程（文档化的 ack 播放中唤醒打断唤醒场景即双写）；drainEntries() toList+clear 非原子。
- 影响: 并发 clear/addLast 丢块、head/tail 损坏或 AIOOBE；间隙块静默丢失违背「恰好回灌一次」契约。
- 修法建议: 所有访问器同一私有锁（仓库 continuationLock 已有同型先例）。
- ocr 索引: [443]
---

## CONC-3  [ocr]
- 标题: VoicePlayer 流式生命周期竞态
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/VoicePlayer.kt
- 问题: streamTrack/activeTrack/streamWrittenFrames（非 volatile Long）由 IO 线程 play()/finish() 写，stop()/reset() 可来自 UI/会话线程，无同步。
- 影响: 双 AudioTrack 泄漏一个；写已 release 轨道抛 ISE 或 drainUntil 空转到 wall-clock 死线（卡最长 audioDuration+3s）；Long 撕裂。
- 修法建议: play/finish 收敛单线程 + 释放延迟到该线程 finally；或生命周期转换加锁。
- ocr 索引: [423]
---

## CONC-4  [ocr]
- 标题: ref-pacer 线程异常退出后永不再启
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/VoicePlayer.kt
- 问题: playbackHeadPosition 抛出/Thread.sleep 中断杀死线程但不清 refPacerRunning，startRefPacer 见 true 早退。
- 影响: refPending 无界增长（约 48KB/s）且 AEC 参考静默死亡——正是该 pacer 要修的 NLMS 退化。
- 修法建议: 循环体 try/finally 清 refPacerRunning（+记日志）。
- ocr 索引: [425]
---

## CONC-5  [ocr]
- 标题: WakeAckCacheStore 固定 .tmp 并发写碰撞
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckCacheStore.kt
- 问题: 同名文件并发 write 共享固定 .tmp 路径无同步，先完成的 moveAtomically 可把半写/混合缓冲转正。
- 影响: 读者消费损坏音频直到自愈——恰是原子重命名设计要防的事。
- 修法建议: 每次写用唯一临时名（nanoTime/UUID 后缀）。
- ocr 索引: [431]
---

## CONC-6  [ocr]
- 标题: AguiClient 共享实例 auth 状态并发竞态
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt
- 问题: reloginUsed/lastAuthError 共享可变实例状态且 chat() 入口重置；runId 仅 clock()+attempt 可撞。
- 影响: 并发 chat 互相清零配额违反「每次 chat 透明重登 1 次」；A 操作的错误消息串到 B。
- 修法建议: 状态按调用传上下文对象，或明确单飞行约束并文档化。
- ocr 索引: [228]
---

## CONC-7  [ocr]
- 标题: AguiClientFactory DEFAULT/legacy 槽位混叠 + promotion 竞态
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/agui/AguiClientFactory.kt
- 问题: DEFAULT 键与 LEGACY_KEY 同槽：load() legacy promotion 把值 move 走（默认会话 threadId 被偷，下次 load null 另建新线程）；DEFAULT save/remove 直写 LEGACY 使「晋升后永久删除」不变量失效，非默认键首载 adopt 该 id → 两会话共享同一服务端 thread；promotion 本身 check-then-act 无同步。
- 影响: 默认会话服务端连续性静默丢失；会话串线。
- 修法建议: DEFAULT 与 LEGACY 彻底解耦：一次性迁移 flag + copy（不 move），promotion 加同步。
- ocr 索引: [166, 167]
---

## FUNC-6  [人工代码 F-VSM-1]
- 标题: runBlocking{sentences.send} 不可取消 → IO 线程永久挂死
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
- 问题: sentences(4) 打满时管线线程阻塞于 runBlocking（不在 pipelineJob 协作取消域）；producer 只 break 不 close；consumeSentences finally 只 close audioQueue（1112 行 sentences.close() 永不可达）。
- 影响: 每发生一次永久泄漏 1 个 IO 线程；反复打断长回复可耗尽 Dispatchers.IO（默认 64）拖垮全 App 网络路径。
- 修法建议: consumeSentences finally 补 sentences.close()；或 handleDelta 改 trySend + sealed 短路消除 runBlocking。
- ocr 索引: []
---

## FUNC-7  [ocr]
- 标题: WakeAckResolver 瞬态失败被 memo 永久化
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckResolver.kt
- 问题: memo 以外层 Decision 为键缓存 fallback/NONE 无失效（进程级单例）；assetNames() 把首次 assets.list 的 IOException 空集 memo 终身。
- 影响: 一次瞬态失败 → 进程生命周期内 vendor/缓存层永不重试，唤醒应答降级且零诊断、不可自愈。
- 修法建议: 回退结果不写 memo（或按文件长度/mtime 新鲜度键）；空集不 memo 并记录 lister 失败。
- ocr 索引: [449, 450]