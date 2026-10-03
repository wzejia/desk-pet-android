# 小朵 Android 全量审计 · 合并分诊报告（unified-findings）

- 日期：2026-10-03
- 分诊人：Claude（合并分诊员），只对四个来源做去重/分级，未新增发现、未改任何行号
- 输入（同目录）：
  1. `merged-ocr-findings.json` — ocr 机扫 462 条（merged_comments[462]，severity: high 61 / medium 179 / low 220 / null 2）
  2. `codex-report.md` + `findings.json` — codex-security 机扫 5 条（high 1 / medium 2 / low 2）
  3. `manual-code-rescue-findings.md` — 人工兜底代码评审 14 条（P1×0 / P2×2 / P3×12；其 P2 已被管理者抽查证实）
  4. `manual-noncode-findings.md` — 非代码面人工评审 8 条（P1×0 / P2×2 / P3×6）
- 引用口径：`ocr[N]` 指 merged-ocr-findings.json 中 merged_comments 数组第 N 个元素（0 起）；路径以 ocr 的 `app/src/main/java/...` 形式书写，codex 报告中的 `main/java/...` 为同一文件。

---

## 一、执行摘要

| 级别 | 统一条目数 | 说明 |
|---|---|---|
| **P1** | **0** | 无可直接利用的安全洞或必崩/必丢数据缺陷。最接近 P1 的是 SEC-1（TLS 白名单旁路），按「需要特定条件（进入板子所在内网网段）+ 文档明示的已接受设计」判 P2，已标待人工复诊 |
| **P2** | **43** | 原始 72 条（ocr 65 + codex 3 + 人工 4）经并条得 43（清单见第二节，全量字段见 `p2-p3-detail.json`） |
| **P3** | **410** | 原始 417 条（ocr 397 + 人工代码 12 + 人工非代码 6 + codex 2）经并条得 410（聚类见第三节） |

### 分级方法（非机械映射）

ocr 的 severity 是模型自动打的，本报告逐条按 content 复核：

- **high 61 条** → 默认 P2，逐条复核后 **42 条 P2 / 19 条降 P3**。降级原因：纯性能类（PetView 逐帧重绘、Markdown 表格 O(H·(R+1)) 重复测量、PetLogger 全局锁同步写盘）、理论性类（BubbleOwnership 需 2^31 次 claim 溢出、WavCodec 需 >2GiB PCM、FrameAnimator 需 frames==0 的数据变更、PetView 需内置资源缺失）、诊断可观测类（TTS/ASR 错误帧被吞只造成误导性报错不崩溃）、JVM-only 路径（AsrClient 的 JVM org.json CCE 在 Android 生产不受影响）、潜伏数据依赖（saveAll 部分参数 footgun 当前无触发调用方）。
- **medium 179 条** → 默认 P3，仅对 content 有**实锤崩溃/数据损坏路径**的 21 条升级（典型：ChatViewModel 静态桥滞留旧 VM 导致历史回退 [126]、SettingsScreen 协程无兜底崩溃 [80]、设备轮不可打断 [124]、云客户端 Result 契约异常边界家族 [105/129/130/231/232/267/413]）。
- **low 220 条** → 默认 P3，仅 2 条作为家族子项随 P2 并条（[232] AguiClient body!!、[251] SessionIndexCodec "null" id）。
- 拿不准的一律降一级并标**待人工复诊**（共 6 处，见第五节）。
- 人工两份评审的 P1/P2/P3 自评级**原样保留**（其口径与本判据一致），ocr/codex 条目与人工条目重叠处按统一判据定级。

### 三路覆盖情况

- **ocr 机扫**：462 条全部位于 `app/src/main/java/com/xiaoduo`（Kotlin 代码）；gradle/XML/manifest 等非代码面 ocr 零覆盖。
- **codex-security**：全仓 5 条，集中在网络安全/凭据/日志留存/组件导出，且对设备技能、SSE 解析、Markdown 渲染、语音管线等面给出「无问题」结论（其「Reviewed Surfaces」表已披露）。
- **人工代码兜底**：覆盖 ocr 失败的 5 个文件（MarkdownRenderer / VolcengineAsrClient / BubbleTextStrip / NlmsEchoCanceller / VoiceSessionManager，共 1308+ 行精读）。**已核实：这 5 个文件在 ocr 输出中零条目，与 ocr 互补、无重叠**。
- **人工非代码**：覆盖 manifest / gradle 三件套 / res/xml / gradle.properties / wrapper / 签名与秘钥入库检查；该面 ocr 完全未扫，codex 仅命中其中 3 处（见重叠表）。
- **披露（原样带入）**：ocr 机扫 5 文件失败转人工兜底（coverage-flags.json `uncovered`：NlmsEchoCanceller.kt、VoiceSessionManager.kt、BubbleTextStrip.kt、MarkdownRenderer.kt、VolcengineAsrClient.kt）；3 个文件疑似部分评审（coverage-flags.json `suspect_partial`：DynamicWakeAck.kt、BubbleView.kt、ChatSession.kt——该 3 文件的 ocr 条目存在性已知，覆盖完整性未证实）。

---

## 二、P2 清单（43 条，全列）

格式：编号｜来源通道｜位置｜问题｜影响｜修法。`〔待复诊〕` 标记见第五节。

### 安全类（6 条）

**SEC-1｜codex#1 + ocr[171]（双通道）｜net/HttpClients.kt:25,47-49,78-88**
- 问题：WhitelistTrustManager 对 10.0.18.166/97/24 三个白名单 IP 直接 return，跳过全部证书链/有效期/信任锚校验，hostnameVerifier 对同列表短路为 true（无 pinning）。
- 影响：同网段攻击者（ARP 欺骗/DHCP 占位）可冒充平台 443 端点，截获 Bearer token 与上传文件、注入 SSE/TTS 内容。codex 判 confirmed、severity high、confidence high。
- 修法：改为内置自签证书/SPKI pinning 比对叶证书，其余主机走默认 TrustManager；移除 hostnameVerifier 旁路；补「不匹配证书必须握手失败」单测。
- 分级说明：最接近 P1 的条目。按判据留在 P2 顶部的理由：利用前提是攻击者已进入板子所在内网网段（端点为固定内网 IP + Proxy.NO_PROXY），且人工非代码评审将其记为「项目文档明示的内网自签设计，记录在案即可」；codex 报告 Open Questions 亦承认严重度取决于 10.0.18.x 的网段隔离现状。〔待复诊〕

**SEC-2｜codex#2（单通道）｜res/xml/network_security_config.xml:7-11 + net/NetConfig.kt:90 + agui/AguiClient.kt:283-296**
- 问题：平台登录默认 POST 到 http://10.0.18.166:8080，账号密码 JSON 明文传输；token 约 25 分钟过期即重发密码。
- 影响：网内被动观察者（镜像端口/流氓 AP/ARP 欺骗）可直接收取管理员凭据。
- 修法：登录迁移到既有 443 反向代理（loginUrl 按 agentStreamUrl 同法推导 https://）；若 :8080 必须保留则设显式 opt-in 并延长 token 免密周期。

**SEC-3｜codex#3 + ocr[159] + ocr[145]（TokenStore.kt:34）+ ocr[144]（TokenStore.kt:6，次级）（双通道）**
- 问题：登录密码、平台 bearer token、三家云 API key 以明文字符串写入默认 SharedPreferences（xiaoduo_net）；全仓无 Keystore/EncryptedSharedPreferences；另 TokenEntry 自动生成的 toString() 内嵌原始 token，与本文件「严禁入日志」规则自相矛盾。
- 影响：root 设备/本机短暂接触/debuggable dump 可直接读取；云备份面已被 allowBackup=false 关闭。
- 修法：密码/token/key 迁 EncryptedSharedPreferences（Keystore 后端）；TokenEntry 覆写 toString 脱敏。

**SEC-4｜codex#3 + 人工非代码 F-P2-2（双通道，任务书已知）｜app/build.gradle.kts:33-35**
- 问题：CLOUD_KEY / ALIYUN_SPEECH_KEY / VOLC_SPEECH_KEY 经 buildConfigField 以编译期常量内联进 dex，apktool/jadx 可从任一分发 APK 静态还原；密钥泄露后长期有效。源值在 gitignored 的 secrets/cloud.properties（未入库），注释表明 2026-08-13 已拍板接受该设计。
- 影响：内网流通的 release APK 即携带可提取密钥；dist/ 已有实际分发的 release 包。
- 修法：短期确认服务端对 key 有调用限额与可轮换手段；中期改设备级短时效 token（密钥不出服务端）；建议对已分发 APK 的 key 做一轮轮换。

**SEC-5｜人工非代码 F-P2-1（单通道）｜app/build.gradle.kts:43（关联 L40 isMinifyEnabled=false）**
- 问题：release 包使用公开口令的 Android 默认 debug.keystore 签名，且 minify 关闭；dist/ 中已有按此形态分享流通的 APK。
- 影响：任何第三方可用同一签名构造 APK 直接覆盖安装（同签名视为升级、无需卸载），升级链完整性零保护；未混淆使密钥字符串与业务逻辑裸奔。
- 修法：生成项目独立 keystore（不入库，密码走 secrets/）替换 debug 签名，成本极低且不改变「直接安装分享」体验；正式发布前补混淆。

**SEC-6｜ocr[190]（codex 对同一候选显式 reject——反向信号）｜device/ShelfMcpBridge.kt:56-57**
- 问题：runId 未校验/转义即拼入 callFor() 手工 JSON 字面量。ocr 认定 runId 来自平台 SSE（TOOL_CALL_START → ToolCallOutcome）；codex 审查同一候选时以「runId 是 client-generated 且工具已白名单」为由 suppress——两通道对 runId 来源的判断相反。含 `"`/`\` 的 runId 即产生畸形 JSON 包，构造值可闭合字符串注入额外键（如第二个 `"Op"`，device 侧 last-wins 解析器可能执行）。
- 影响：最坏把 UI-only 同步变成非预期设备指令；前提是平台侧 runId 可被污染。
- 修法：runId 含引号/反斜杠/控制字符时丢弃该字段仍发同步（或校验字符集），与 resultBodyFor() 用 org.json 的处理对齐。〔待复诊〕

### 崩溃/健壮类（16 条）

**ROB-1｜ocr[23]+[24]+[25]（同族并条）｜voice/AudioCapture.kt:74-76, 88, 144**
- 问题：start()/stop() 对 job/gateToken 无共同锁（job 非 @Volatile），tryAcquire→赋值→launch 序列与并发 stop() 不原子；stop() 先释放门再等协作取消，读循环可能仍滞留 read()；startRecording() 错误码被忽略，isRunning 照置 true。
- 影响：丢失 stop（麦克风滞留打开、门被占导致后续 start 静默 no-op）或双捕获循环并行抢麦克风、双重喂流。
- 修法：start/stop 串行到同一锁；stop 把 token 交给 job 完成路径再释放；startRecording 失败即失败返回。

**ROB-2｜ocr[26]｜voice/AudioCapture.kt:282-285**
- 问题：read() 持续返回非正（AudioRecord 卡在 ERROR_INVALID_OPERATION 等）时读循环零退避、无出口。
- 影响：单核满速自旋，VAD/KWS 静默饿死，直到有人 cancel 该 job。
- 修法：连续失败计数，超阈值记日志退出（finally 释放 AudioRecord 与门），重试间让出。

**ROB-3｜ocr[75]｜ui/overlay/VoiceIndicatorView.kt:98-101**
- 问题：setMode()/startLoop()/stopLoop() 调 Choreographer.getInstance()（无 Looper 线程即抛），且 loopRunning/lastFrameNs/displayLevel/mute 无同步；onLevel() 文档明确允许任意线程（VAD 分支）调用。
- 影响：从非主线程驱动显隐时崩溃（Choreographer）或动画状态损坏。
- 修法：生命周期收敛主线程（或经主 Looper Handler post），并文档化线程契约。

**ROB-4｜ocr[201]｜VoiceForegroundService.kt:221-227**
- 问题：kwsSink 闭包在采集线程 feed 原生引擎无同步，onDestroy 持锁 release；sink 的 null-check-then-invoke 窗口内旧闭包仍可喂已释放引擎；runCatching 无日志吞掉 teardown 失败。三重可达路径（stopIdleCapture 的意图注册式延迟、闭包滞留、部分失败继续 release）。
- 影响：use-after-release → native crash（SIGSEGV，无 Java 栈）。
- 修法：feed 与 release 串行到同一监视器（或 KwsEngine.feed 内加原子 closed 标志）；至少记录被吞异常。

**ROB-5｜ocr[220]｜PetOverlayService.kt:224-231**
- 问题：双窗口挂载中第一个 addView 成功、第二个抛出时，失败路径 stopSelf 返回且 displayBox/interactionBox 尚未赋值，onDestroy 的 `?.let` 无从移除已挂窗口。
- 影响：WindowLeaked 型系统窗口泄漏，悬浮层残留。
- 修法：记录首个已挂视图，失败路径先移除再退出。

**ROB-6｜ocr[221]｜PetOverlayService.kt:525-534**
- 问题：judgePublish 在调用线程校验 token、handler.post 延后执行；期间新轮 claim 或更新 hide 落地后，过期 runnable 仍无条件执行——以旧轮文本新建会话覆盖 bubbleStrip、重开触摸。appendBubble 同型。
- 影响：新轮次气泡内容被旧轮污染，后续追加喂错会话。
- 修法：捕获 owner/round，在 posted runnable 内重新校验后才应用。

**ROB-7｜ocr[222]+[223]（并条）｜PetOverlayService.kt:601-613 + 622-624**
- 问题：① setBubbleTouchable(false) 在调用方线程同步执行，hideBubble/FORCE_HIDE 可从 binder/worker 线程（如 chat sink）到达，非主线程调 wm.updateViewLayout 抛 CalledFromWrongThreadException；② ensureWindows 只在 onStartCommand 运行，而 service 可 bind、companion 实例 onCreate 即发布，窗口未建时 lateinit displayLp 抛 UninitializedPropertyAccessException。
- 影响：悬浮窗路径两类确定性崩溃。
- 修法：移入 posted runnable 在主线程原子执行；!built 时降级 no-op。

**ROB-8｜ocr[455]（07-rescue 批次）｜ui/overlay/OverlayMenu.kt:179-180**
- 问题：以 WindowManager 悬浮窗 anchor 直接 showAsDropDown，无 isAttachedToWindow/token 检查、无错误处理。
- 影响：teardown 与长按竞态/多窗口切换令 window token 失效时抛 BadTokenException 崩溃进程。
- 修法：show 前 guard anchor.isAttachedToWindow 并 try/catch 包裹。

**ROB-9｜ocr[333]（medium 升级）｜ui/screens/ChatScreen.kt:211-212**
- 问题：vm.uploadFile 在 rememberCoroutineScope 的 IO 协程内无 try/catch（组合 scope 无 CoroutineExceptionHandler）；中途导航离开（设置/全屏语音）时协程被静默取消。
- 影响：上传网络/IO 异常直达未捕获处理器 → 应用崩溃；文件 chip 遗留指向未上传文件。
- 修法：try/catch（CancellationException rethrow）并 toast 反馈；迁移到 viewModelScope 使其幸存导航。

**ROB-10｜ocr[352]｜ui/screens/SessionDrawer.kt:86-92**
- 问题：会话搜索 onSearch 在 withContext(Dispatchers.IO) 内无 try/catch，IO 异常逸出 produceState producer。
- 影响：任一次搜索 IO 错误撕毁整个组合（UI 崩溃）。
- 修法：catch 非 Cancellation 异常降级（log + 空结果），CancellationException 必须 rethrow。

**ROB-11｜ocr[339]｜ui/screens/PermissionScreen.kt:83-91**
- 问题：startActivity(ACTION_MANAGE_OVERLAY_PERMISSION) 无守卫，部分 OEM 精简系统无该 action 解析 activity。
- 影响：点「授权悬浮窗」即抛 ActivityNotFoundException 崩溃（机型相关）。
- 修法：try/catch（PetLogger.e 记录）+ 回退 ACTION_APPLICATION_DETAILS_SETTINGS。

**ROB-12｜ocr[332]｜ui/screens/ChatScreen.kt:681-683**
- 问题：附件 picker 为 `*/*`，readBytes() 在 20MB 限制（vm.uploadFile 内）之前把整个 provider 内容读入内存；OOM 被 runCatching{}.getOrNull() 吞成通用「读取文件失败」toast（同样掩盖 SecurityException）。
- 影响：选数百 MB 视频/压缩包 → 内存尖峰（低端板可被系统杀）或误导性失败。
- 修法：游标先取 OpenableColumns.SIZE 预拒超大文件，或流式带硬上限读取。

**ROB-13｜ocr[80]（medium 升级）｜ui/screens/SettingsScreen.kt:409-410（三处调用点）**
- 问题：agent 列表加载 / 对话轮 / 采麦校准三处 scope.launch 无异常兜底，任一抛出未映射为对应 Error 结果的异常沿 rememberCoroutineScope 的 Job 传播。
- 影响：应用崩溃，且 loading/running 卡 true、按钮永久禁用。
- 修法：每处 try/catch（CancellationException rethrow，其余转对应 Error 结果）+ finally 复位标志。

**ROB-14｜ocr[105],[129],[130],[231],[232],[267],[413]（medium/low 升级，同机制并条）｜cloud/OpenAIAsrClient.kt:39-43；cloud/OpenAIChatClient.kt:58, 82-84；agui/AguiClient.kt:765, 779-781；cloud/AliyunAsrClient.kt:42；voice/TtsClient.kt:96（同型镜像：OpenAITtsClient / VolcengineTtsClient）**
- 问题：Result 契约客户端异常边界缺失家族——Request.Builder.url 对用户可配的畸形 baseUrl 抛 IAE（构造在 try 外）；resp.body!! 可抛 KotlinNPE（body 声明可空）；catch 只覆盖 IOException（onDelta 回调异常、OkHttp 对已取消调用的 ISE、Base64 IAE 均逸出）；SSE feedLine 解析运行时异常逸出 chat()；transcribe() 无顶层异常边界（stripWavPcm 异常直通）；TTS 流式路径 body!! 击穿文档化降级链。
- 影响：畸形响应/错误配置/取消时序 → 未捕获异常崩溃调用方，而非返回 Result.Error。
- 修法：家族统一修：请求构造入 try、body 安全调用 + null→Failure、catch 补 RuntimeException 映射 Error、feedLine runCatching 防御化。

**ROB-15｜ocr[438]｜voice/WakeAckPrefetcher.kt:47-49**
- 问题：仅 ttsSynthesize 与 cacheStore.write 有护栏；resolver.snapshot()、WakeAckSelector.needsPrefetch/cacheKey/fileName、WakeAckAudio.validate/process/toSamples、WavCodec.pcmToWav 裸奔，异常直达线程默认 uncaught handler；两处护栏 catch 只捕 Exception（OOM 逸出）。与类 KDoc「任何一步落空即退，不让异常逃出线程」直接矛盾。
- 影响：一个异常缓存文件即可杀死进程。
- 修法：run() 整体单层 try/catch(Exception) + PetLogger（可用 key 时记 backoff），替代散点护栏。

**ROB-16｜ocr[379]｜voice/AmbientCalibrator.kt:70-71**
- 问题：CoroutineScope(Dispatchers.IO) 上下文无 Job，ioScope.cancel() 解析为扩展函数并抛 IllegalStateException（"Scope cannot be cancelled because it does not have a job"）。
- 影响：临时采集分支每次运行收尾必抛（掩盖采集结果或在途异常），scope 级清理永不发生。
- 修法：CoroutineScope(SupervisorJob() + Dispatchers.IO)。

### 数据完整性类（6 条）

**DATA-1｜ocr[108],[109],[110],[111],[112],[113],[241],[249],[250]（次级子项 [115],[251]）｜chat/ChatStore.kt:43-45, 156-160, 53-56, 173-174, 63, 118-119；chat/ChatHistoryCodec.kt:36；chat/SessionIndexCodec.kt:38, 55（42-43）**
- 问题：「读失败/损坏 → 静默降级为空 → 破坏性回写」数据丢失链：① indexFile 读失败与「尚无索引」不可区分，返回空索引，下一次变更即 saveIndex 覆盖好索引（108）；② 迁移路径读失败以 "" 喂 decode 得空索引，写回仅含 legacy 会话的索引，其余会话全部掉表（109）；③ 消息文件读失败/损坏静默得 emptyList，下次 saveMessages 把空/部分列表整写回文件（110 + ChatHistoryCodec:36）；④ writeText 非原子（先截断后写），一次 torn write + 空索引兜底 + 下次变更即抹掉全会话列表（111）；⑤ 会话不在索引时 saveMessages 静默丢弃（无日志），叠加 createSession 在 saveIndex 失败时照常返回 session → 整段对话无信号丢失（112）；⑥ 删消息文件先于索引持久化，次序颠倒致僵尸索引条目（113）；⑦ Android org.json 的 optString 把 JSON null 读成字面量 "null"，编码端写 null currentId 后解码得幽灵会话 id "null"（249 + 251 同型 id/title）。次级：@Synchronized 仅实例级，第二实例交错 RMW 可 last-writer-wins 掉整会话（115）。
- 影响：一次瞬时 IO 错误/断电即可永久抹掉会话列表或整段对话历史，全程无用户可见信号。
- 修法：读失败可观测（nullable/Result 返回）并以标志/备份拒绝破坏性覆写；writeText 改临时文件 + 原子 rename（ATOMIC_MOVE）；先持久化索引后删消息文件；org.json null 用 `(opt as? String)` 类型安全读；createSession/saveIndex 失败向上传播。

**DATA-2｜ocr[126]（medium 升级：实槽数据回退）｜chat/ChatViewModel.kt:144-147**
- 问题：三个静态桥捕获 this VM 且无 onCleared 反注册；Activity 销毁/viewModelScope 取消后，PetOverlayService 仍持旧 VM 直至下次 init。
- 影响：滞留期内悬浮窗触发 chatInterrupt 会对失效实例 markCancelledDone + persist——旧 _messages 快照整写回会话文件，覆盖新 VM 已持久化内容（历史回退）；旧实例被静态引用滞留。
- 修法：override onCleared() 置空三个静态桥，Service 侧判空调用。

**DATA-3｜ocr[121]｜chat/ChatViewModel.kt:181-185**
- 问题：busy 守卫只防「文字轮晚于语音轮」：语音播放/聆听期 _phase=IDLE 且 voiceActive=false，用户 send 通过检查；语音 utterance 随后到达即与在途文字轮形成双写者。
- 影响：对 _messages 的交错读-改-写后写整体覆盖先写——丢消息/丢 delta（fail-silent）；两轮收尾并发 persist 文件层 last-writer-wins。
- 修法：onUserUtterance 开头检查在途文字轮（打断或延后语音上屏）；_voiceActive=true 提前至 rotateSessionIfIdle 之前缩小 TOCTOU 窗口。

**DATA-4｜ocr[122]+[123]（并条）｜chat/ChatViewModel.kt:205 + 380-387**
- 问题：_messages 的读-改-写跨三方线程（语音管线 IO / OkHttp IO 协程 / 主线程）非原子；_files 在 IO 协程与主线程并发写；uploadJobs 为非线程安全 HashMap 且被三线程读写。
- 影响：互吞 append/delta（语音 user 消息或 assistant 占位被抹、流式丢字）、已删附件复活、HashMap 内部结构损坏。
- 修法：写入改 StateFlow.update（CAS 自旋原子 RMW）或收敛单一调度器；uploadJobs 换 ConcurrentHashMap。

**DATA-5｜ocr[235]｜chat/ChatTurnLogic.kt:39-40**
- 问题：rawBuffers 为普通 HashMap + StringBuilder（非线程安全），MutableMap.getOrPut 非原子；流式线程 applyDelta 与主线程 markDone/markError/prepareResend 并发。
- 影响：map 损坏、双 StringBuilder 丢 delta、终态后残留 buffer。
- 修法：ConcurrentHashMap.compute(assistantId){...} 单临界区完成追加与 cleaned text 读取；或文档化并强制单线程契约。

**DATA-6｜ocr[236]｜chat/ChatTurnLogic.kt:38-39**
- 问题：applyDelta 无状态守卫——markDone/markError/markCancelledDone/prepareResend 之后到达的迟到/重复 delta 会以 cleaned text 重建 rawBuffer 并把终态消息翻回 STREAMING。
- 影响：已完成轮次带陈旧内容复活，StringBuilder 泄漏至 reset()。
- 修法：仅 SENDING/STREAMING 状态应用 delta，其余原样返回。

### 并发/生命周期竞态类（8 条）

**CONC-1｜ocr[419] + 人工代码 F-VSM-3（双通道，分级有分歧：ocr high vs 人工 P3）｜voice/VadDetector.kt:170-171 + voice/VoiceSessionManager.kt:583-589, 748**
- 问题：feed() 同时来自 Dispatchers.Default 采集循环（routeBlock/backfillBlindWindow）与 stopListening() 线程，runPipeline(IO) 调 start()/raiseNoiseFloorFromLastUtterance()；仅 startLevelOverride @Volatile，noiseFloor 为非 volatile Double 读-改-写，lastUtteranceRms/tick 计数/缓冲均无 happens-before。
- 影响：并发 feed 可损坏 ArrayList/ArrayDeque 与 tick 状态机；噪声地板丢更新；32 位 ARM 上非 volatile Double 写可撕裂成垃圾阈值（板端为 rk3568）。
- 修法：全部公共入口一把锁，或把 VadDetector 访问收敛到单一音频消费线程（stopListening 静音块经 Channel 投递）。〔待复诊〕

**CONC-2｜ocr[443]｜voice/WakeBlindWindowBuffer.kt:30-33**
- 问题：非同步 ArrayDeque 双域变更——add()/drain() 在采集收集协程（Default），clear() 可达 KWS 唤醒回调线程与 UI/barge-in 线程（文档化的「ack 播放中唤醒打断唤醒」场景即双写）；drainEntries() 的 toList+clear 非原子。
- 影响：并发 clear/addLast 可丢块、head/tail 损坏或抛 ArrayIndexOutOfBoundsException；间隙块静默丢失违背「恰好回灌一次、不丢音频」契约。
- 修法：所有访问器加私有锁（仓库 continuationLock 已有同型先例）。

**CONC-3｜ocr[423]｜voice/VoicePlayer.kt:95-97**
- 问题：streamTrack/activeTrack/streamWrittenFrames（非 volatile Long）由调用方 IO 线程在 play()/finish() 写，stop()/reset() 可来自 UI/会话线程，无同步。
- 影响：check-then-act 双建 AudioTrack 泄漏一个；并发 reset() 后 write/playbackHeadPosition 打在已 release 轨道（ISE，或 drainUntil 以 head=0 空转到 wall-clock 死线，卡最长 audioDuration+3s）；Long 撕裂。
- 修法：play/finish 收敛单线程并在该线程 finally 释放，或生命周期转换加专用锁。

**CONC-4｜ocr[425]｜voice/VoicePlayer.kt:147-150**
- 问题：ref-pacer 线程可异常退出而不清 refPacerRunning——playbackHeadPosition 抛出只翻局部 alive 标志，Thread.sleep 的 InterruptedException 直接杀线程；startRefPacer() 见 true 早退。
- 影响：pacer 永不再启，writeAll 持续入队 refPending（约 48KB/s 无界增长），AEC 参考静默死亡——恰是该 pacer 要修的 NLMS 退化。
- 修法：循环体 try/finally 清 refPacerRunning（+记日志）。

**CONC-5｜ocr[431]｜voice/WakeAckCacheStore.kt:38**
- 问题：同名文件的全部写入共享固定 ".tmp" 路径且无同步；两线程并发 write 交错后，先完成的 moveAtomically 把半写/混合缓冲转正。
- 影响：读者消费损坏音频直到自愈——恰是原子重命名设计要防的事。
- 修法：每次写用唯一临时名（nanoTime/UUID/线程 id 后缀）。

**CONC-6｜ocr[228]｜agui/AguiClient.kt:181-182**
- 问题：reloginUsed/lastAuthError 为共享可变实例状态，所有公共入口变更且 chat() 入口重置 reloginUsed=false；runId 仅 clock()+attempt 生成可撞。
- 影响：并发 chat 互相清零配额，违反「每次 chat 透明重登 1 次」；A 操作的 lastAuthError 串为 B 的错误消息。
- 修法：状态改为按调用传递的上下文对象（贯穿 login/ensureThreadId/reloginOnce），或显式约束单实例串行并文档化。

**CONC-7｜ocr[166]+[167]（并条）｜agui/AguiClientFactory.kt:60-64 + 58**
- 问题：DEFAULT 键与 LEGACY_KEY 同槽混叠——load() 的 legacy promotion 把值 move 进 thread_id_<key>（偷走默认会话存活 threadId，下次 load 为 null 另建新线程）；DEFAULT 的 save/remove 直写 LEGACY 使「晋升后永久删除」不变量失效，非默认键首次 load 会 adopt 该 id → 两个独立会话共享同一服务端 thread；promotion 本身 check-then-act 无同步。
- 影响：默认会话服务端连续性静默丢失；会话串线。
- 修法：DEFAULT 与 LEGACY 彻底解耦：一次性迁移 flag + copy（不 move），promotion 加同步。

**CONC-8｜ocr[124]（medium 升级：真实功能缺陷，有超时兜底）｜chat/ChatViewModel.kt:520-522**
- 问题：runDeviceTurn/runShelfNavTurn 不登记 chatJob/activeAssistantId、不捕获 turnEpoch，cancelCurrentTurn 见 activeAssistantId==null 直接 return。
- 影响：barge-in 完全打不断设备/密集架导航轮；busy(SENDING) 吞掉用户后续一切输入直到轮次自然结束（DeviceClient/ShelfMcpBridge 有 4s/6s 超时兜底，窗口有限但用户感知「打断没反应」）。
- 修法：与 runChat 同款登记（chatJob + activeAssistantId + turnEpoch），使取消路径能 markCancelledDone/markError 并复位 phase。

### 功能正确性类（7 条）

**FUNC-1｜ocr[282]+[283]+[281]+[187]（同机制并条）｜device/DeviceIntentMatcher.kt:38-39, 56, 41 + device/ShelfNavIntentMatcher.kt:45**
- 问题：意图匹配缺疑问句/否定句门控家族——动作分支先于疑问检测且开关词是问句子串：「整理台开机了吗」命中 isOn 发 PowerOn、「净化柜强力模式是什么」切强力模式（282）；否定词不处理：「不要打开整理台」执行 PowerOn、「不要关闭整理台」执行关闭（283）；fan-speed 分支命中但 fanSpeed() 解析失败时吞掉 query 回退（281）；ShelfNav 问句/聊天句被劫持为页面导航（187）。
- 影响：自然问句/否定句**确定性误触发物理设备动作**或错误跳页。
- 修法：动作分支前加疑问标记门（吗/呢/什么/？/?）与否定门（不要/别），问句路由 QueryStatus 或 null。

**FUNC-2｜ocr[180]｜device/DeviceClient.kt:60-61**
- 问题：关机校验 optInt("RunFlag") 缺键默认 0——空/畸形/缺 RunFlag 的状态体（只要能 parse 成 JSON 且无 err 字段）被判「成功关闭」。
- 影响：关机未被证实却向用户报成功。
- 修法：哨兵默认（如 -1），flag 缺失即失败，与其他字段处理一致。

**FUNC-3｜ocr[181]｜device/DeviceClient.kt:69**
- 问题：FanSpeed 回读校验硬编码 Device.ZLT，忽略 cmd.device（sealed 命令显式携带且 JHG/净化柜为合法目标）。
- 影响：净化柜风速指令对错误设备校验——误报失败或掩盖真实失败。
- 修法：回读改用 cmd.device。

**FUNC-4｜ocr[186]｜device/ShelfNavIntentMatcher.kt:49-50**
- 问题：首现决胜取页——「从借阅切到归还」中借阅索引在前返回 borrow（源页），match() 静默导航且无 LLM 兜底；KDoc 甚至把错误目标写成预期。反向句式同型。
- 影响：源→目标句式 100% 导航到用户要离开的页面。
- 修法：候选页跨多于一个值时返回 null 交 LLM 解析。

**FUNC-5｜人工代码 F-BT-1（管理者已抽查证实）｜ui/overlay/BubbleTextStrip.kt:276-284, 310-332（佐证 PetOverlayService.kt:534, 563）**
- 问题：流式与整段路径均无流尾 flush——Session 只有 feed 无 flush；`**` 分支无 hold 上限（`*`≤24 字、反引号≤48 字）；调用方只取 feed().text 无收尾全量渲染。
- 影响：LLM 回复以未闭合 `**`（任意尾长）、`*`（≤24 字）或反引号（≤48 字）收尾时，标记之后内容永不上屏，用户感知「回答缺后半句」。反例：feed("注意**这里是重点") → 气泡只显示「注意」。
- 修法：Session 增加 flush()（对全量 raw 走一次性 render 规则差分补发），调用方在 RUN_FINISHED/收泡前调用；最小改动为给 `**` 分支加 hold 上限（超限丢标记留内容放行）。

**FUNC-6｜人工代码 F-VSM-1（管理者已抽查证实）｜voice/VoiceSessionManager.kt:1047 + 1131-1156 + 1180-1184**
- 问题：`runBlocking { sentences.send(s) }` 不可取消——sentences(容量 4) 打满时管线线程阻塞在 runBlocking（不属于 pipelineJob 协作取消域），pipelineJob.cancel() 中断不了；producer 侧只 break 不 close，consumeSentences 的 finally 只 close audioQueue + cancel producer（1112 行的 sentences.close() 在 catch(CancellationException) 内永不可达）。
- 影响：每发生一次永久泄漏 1 个 Dispatchers.IO 线程；反复打断长回复可逐渐耗尽 IO 线程池（默认 64）拖垮全 App 网络路径。
- 修法：最小修——consumeSentences 的 finally 补 sentences.close()（解除阻塞 send 使其抛异常沿 runBlocking→voiceChat→runPipeline catch 正常拆解）；或 handleDelta 改 trySend + sealed 短路消除 runBlocking。

**FUNC-7｜ocr[449]+[450]（并条）｜voice/WakeAckResolver.kt:71-73 + 121-122**
- 问题：memo 以外层 Decision 为键缓存 fallback/NONE 结果且无失效（resolver 为进程级单例）；assetNames() 把首次 assets.list 的结果（生产 assetLister 将 IOException 吞成空集）memo 终身。
- 影响：一次瞬态失败 → 进程生命周期内 vendor 资产层/修复后的缓存永不重试，唤醒应答静默降级且零诊断，不可自愈。
- 修法：回退结果不写 memo（或 evict）；memo 按 cache 文件长度/lastModified 新鲜度键；空集不 memo 并记录 lister 失败（保 S9-4 取证链）。

---

## 三、P3 汇总（410 条，按主题聚类）

ocr 侧 P3 共 **397 条**（high 19 / medium 158 / low 218 / null 2），按主题分七类；另有跨通道合并条目与人工独立条目。索引均为 merged-ocr-findings.json 的 merged_comments 下标（0 起）。

### 1. 可维护性-契约-测试缺口（98 条，全部 maintainability 类）
代表：ocr[370] PrefContract.kt:76 保全键集不全（kws_enabled 等 7 键不在任何 *_KEYS，升级丢键无测试可拦）；ocr[154] TtsVoiceDefaults.kt:32 else→openai 静默兜底的三重漂移面；ocr[50] VoiceClientFactory.kt:160 一次性门控使设备 URL 首配后须重启才生效。
完整清单（98）：2,3,4,6,20,27,28,29,31,37,38,46,53,57,58,64,68,70,83,89,95,99,100,106,142,143,146,148,152,154,157,158,164,165,174,175,185,188,193,195,199,200,205,208,210,215,219,227,242,244,245,252,253,259,260,280,284,295,296,301,303,309,316,318,320,325,328,329,331,341,346,358,361,363,368,370,371,372,373,374,385,388,389,390,398,399,400,407,410,411,418,422,442,445,451,452,458,459

### 2. 并发-线程安全-竞态（68 条）
代表：ocr[5] BubbleHidePolicy.kt:18 写读无线程序可丢 12s 延迟隐藏；ocr[49] VoiceClientFactory.kt:66 voiceRound claim-then-use 非原子致 UI 更新被 stale 判定丢弃；ocr[202] VoiceForegroundService.kt:214 onWake 与 onDestroy TOCTOU 可留孤儿麦克风采集。
完整清单（68）：0,5,10,11,13,14,15,16,18,19,21,40,42,49,50,98,135,147,177,179,183,202,203,204,211,212,213,217,218,225,230,237,262,264,265,269,270,292,294,308,311,314,334,342,344,356,359,362,366,367,381,382,383,384,393,394,402,420,421,424,426,427,428,429,432,435,439,444（注：387 已移入资源泄漏类）

### 3. 风格-文档-注释（61 条，style/documentation/test 类）
代表：ocr[35] TtsSanitizer.kt:12 与桌面端 sidecar 标点白名单一致性只靠注释背书；ocr[39] VoiceStateRouter.kt:84 单行 KDoc 隐藏已产过生产 bug（S4）的契约；ocr[93] ChatSession.kt:24 `\s` 不含全角空格/ NBSP。
完整清单（61）：7,8,9,22,32,35,36,39,48,54,72,73,74,78,88,90,96,97,104,107,117,120,127,136,137,155,169,170,194,197,209,246,248,254,275,279,286,289,299,300,302,304,319,321,322,327,330,338,386,391,403,404,405,406,408,437,446,447,448,460,461

### 4. 健壮性-边界输入-安全与隐私卫生（61 条）
代表：ocr[114] ChatStore.kt:168 sessionId 路径拼接未校验（id 恒为内部 UUID，沙箱内理论面）；ocr[233] AguiClient.kt:773 S68 调试日志倾倒原始 SSE tool-call 载荷（tool args 含用户对话，隐私卫生）；ocr[119] OpenAITtsClient.kt:41 baseUrl 误配 http 时 key 明文外送。
完整清单（61）：17,33,34,43,56,63,66,67,69,71,79,81,82,86,91,92,93,101,102,103,114,119,139,149,150,151,153,156,163,184,196,198,206,229,233,247,256,258,263,272,290,305,323,326,337,343,348,350,354,369,375,395,396,401,409,416,430,433,456,457,378

### 5. 性能-主线程-内存（48 条，全部 performance 类）
代表：ocr[307] PetView.kt:44 视觉静止时仍每 Choreographer 帧无条件 invalidate（低端板常驻重绘）；ocr[364] PetLogger.kt:69 全局锁内逐行同步开/写/关文件，主线程日志即 jank；ocr[434] WakeAckCacheStore.kt:53 read 先整读后校验，单个超大垃圾缓存项每次唤醒全额分配。
完整清单（48）：1,12,30,45,52,76,84,87,116,125,128,131,140,168,176,178,192,214,234,240,257,268,285,293,306,307,310,312,315,317,335,340,345,347,355,357,364,365,376,377,392,412,415,417,434,441,453,454

### 6. 错误吞掉-误导性失败-降级语义（47 条）
代表：ocr[138] VolcengineTtsClient.kt:70 错误帧被空 data 检查先吞，配额耗尽只报「TTS 返回空音频」；ocr[230] AguiClient.kt:510 所有非 2xx 误映射 Gone 并删映射，一次 5xx 永久孤儿化存活线程；ocr[94] ChatMessage.kt:39 未知状态码静默渲染为已完成。
完整清单（47）：41,44,47,55,61,62,94,118,132,133,134,138,141,160,161,162,172,173,182,189,191,207,216,238,239,243,255,261,273,274,276,277,278,287,288,291,297,298,313,324,336,349,351,360,414,436,440

### 7. 资源泄漏-生命周期清理（14 条）
代表：ocr[387] KwsEngine.kt:39 构造中途失败（createStream 抛）泄漏已构造 native spotter，重试循环逐次累积；ocr[224] PetOverlayService.kt:715 onDestroy 不清 pending handler posts，拆除后仍触 setBubbleTouchable/showMenu；ocr[269] AliyunTtsClient.kt:84 中断路径不调 ws.cancel() 泄漏 WebSocket。
完整清单（14）：51,59,60,65,77,85,224,226,266,271,353,380,387,397

### 8. 跨通道 P3 条目（并条后独立列示）

- **三通道命中｜DebugBubbleReceiver 导出常驻 manifest**：codex#5 + ocr[326] + 人工非代码 F-P3-1。receiver exported=true 无 permission，release 仅靠运行时 FLAG_DEBUGGABLE 守卫；release 无实际漏洞、debug 包可被同机应用伪造气泡文本。修法：signature 级自定义权限或迁 debug source set，并挂 issue 跟踪防遗忘。
- **双通道命中｜crash 日志无轮转无上限**：codex#4 + ocr[214]。crash-*.log 绕过仅匹配 xiaoduo-*.log 的轮转/清理/导出过滤器，appendText 无大小上限，崩溃循环下全天累积；文件在应用私有目录、分享面板已过滤，属留存卫生。修法：LogRotator 识别 crash-<date>.log 或统一命名。
- **WAV 解析负值/溢出族（4 处同型）**：ocr[41] voice/WavCodec.kt:47（Int 溢出绕过 require，IOOBE 违背「畸形输入一律 IAE」契约）+ ocr[262]/[263] cloud/AliyunAsrClient.kt:163-168（负 chunkSize 倒退/死循环，transcribe 同步无 catch）+ 人工代码 F-ASR-1 cloud/VolcengineAsrClient.kt:177-178（人工判 P3）。均为自录/内置 WAV 损坏才触发（无外部输入面），按人工对同型的 P3 判例统一 P3。〔待复诊：是否统一升 P2〕
- **人工代码独立 10 条**：F-MD-1（MarkdownRenderer 行内解析 O(n²) 病态输入）、F-MD-2（空 code span 两渲染器行为分叉）、F-ASR-2（服务端 JSON 解析失败静默 15s 慢失败）、F-NL-1（micHist 环形旋转被块长均匀性掩蔽的 xcorr 对齐潜伏缺陷）、F-NL-2（互相关平移可越 live 边未挡上界）、F-NL-3（maybeAlign runCatching 无失败日志）、F-VSM-2（VAD Timeout 绕过 backToIdle 不清续听挂起）、F-VSM-4（onUtterance 进 PROCESSING 不清 continuationParts 竞态残留）、F-VSM-5（语音转写全文进 logcat，隐私卫生）、F-VSM-6（voiceChat 两段逐字重复的 AG-UI 调用块）。人工文件自带不确定项：F-NL-1 触发性取决于 AudioCapture 恒整块假设（未逐行审 AudioCapture 本体）；F-VSM-1 触发频率取决于 LLM/TTS 流速差。
- **人工非代码独立 5 条**：F-P3-2（gradle.properties 行内 # 注释疑混入 jvmargs 值——自带不确定项：未跑构建无法实证 Gradle 8.9 是否过滤该 token）、F-P3-3（wrapper 指向本地发行包且无 distributionSha256Sum）、F-P3-4（release 携带 x86_64 ABI 无谓增大 15-20MB）、F-P3-5（buildConfigField 密钥插值未转义，换 key 隐性坑）、F-P3-6（内网主机白名单两处手工维护——与 ocr[172] HttpClients.kt:47 所指「hostnameVerifier lambda 与 TrustManager 内部两份清单」是**不同的清单对**，未并条，互为参照）。

---

## 四、跨通道重叠表（多通道独立命中 = 高置信信号）

| # | 统一条目 | 级别 | 通道命中 | 备注 |
|---|---|---|---|---|
| 1 | SEC-1 TLS 白名单完全旁路证书校验 | P2〔待复诊〕 | codex#1 + ocr[171] | 人工非代码按「文档明示的已接受设计」记录在案、未列 finding |
| 2 | SEC-3 凭证/密钥明文存 SharedPreferences | P2 | codex#3 + ocr[159] + ocr[145] | codex 位置 NetConfig.kt:236-263 覆盖 ocr[159] 的 240-241 |
| 3 | SEC-4 云端密钥 BuildConfig 明文编入 APK | P2 | codex#3 + 人工非代码 F-P2-2 | 任务书已知重叠，codex#3 横跨 SEC-3/SEC-4 两半 |
| 4 | CONC-1 VadDetector 跨线程无同步 | P2〔待复诊〕 | ocr[419] + 人工代码 F-VSM-3 | 人工审调用方、ocr 审检测器内部，互补拼成全貌；分级有分歧 |
| 5 | DebugBubbleReceiver 导出常驻 manifest | P3 | codex#5 + ocr[326] + 人工非代码 F-P3-1 | **三通道命中**，任务书已知其中两条 |
| 6 | crash 日志无轮转无上限 | P3 | codex#4 + ocr[214] | 同机制（无上限累积）双命中 |
| 7 | SEC-6 ShelfMcpBridge runId 注入 | P2〔待复诊〕 | ocr[190] 命中；codex 审同一候选后 **reject** | 跨通道分歧信号：ocr 称 runId 来自平台 SSE，codex 称 client-generated，需读源码裁决 |

其余 codex「无问题」结论面（设备技能 whitelist、SSE 防御性解析、Markdown 无注入面、语音解析器有界、无密钥入日志）与 ocr 大量 P3 卫生条目并存但不构成同题命中；人工代码 5 文件与 ocr 零重叠（互补已核实）。

---

## 五、待人工复诊清单（6 项）

1. **SEC-1（TLS 白名单旁路）**：是否因「LAN 邻接即可直接利用、无用户交互」升 P1。支持 P1：codex high/high、confirmed、威胁模型明含 LAN 攻击者；支持 P2：利用前提是进入板子内网网段（固定内网 IP），且属项目文档明示的自签设计（人工非代码「记录在案即可」）。判 P1/P2 直接决定整改优先级，建议用户拍板。
2. **SEC-6（runId 注入）**：ocr 与 codex 对 runId 来源（平台 SSE vs 客户端生成）判断相反，需读 ShelfMcpBridge/AguiClient 源码定谳；若确为平台可控则 P2 成立，若 client-generated 则降 P3。
3. **CONC-1（VAD 并发）**：ocr high vs 人工 F-VSM-3 的 P3 分级分歧（人工认为 currentTurn 换主屏蔽大部分后果）；合并后按「32 位 ARM Double 撕裂 + 容器损坏可能」保留 P2，可复核降级。
4. **WAV 解析族（ocr[41]/[262]/[263] + F-ASR-1）**：人工对同型机制判 P3（需文件损坏才触发），ocr 对其中两处打 high；本报告按人工判例统一 P3，可复核是否因「IOOBE 逃逸 IAE 映射契约」统一升 P2。
5. **ocr[161]（saveAll 部分参数 footgun）**：P3 判定基于「当前调用方均传全参」，未逐调用面核实；若存在部分参数调用则应为 P2 数据丢失项。
6. **ocr[375]（AsrClient JVM org.json CCE）**：生产 Android 是否受影响取决于构建是否捆绑 JVM org.json:json 构件（未核依赖清单）；若捆绑则升 P2。

---

## 六、分级统计细目

| 来源 | 原始条目 | → P1 | → P2 | → P3 |
|---|---|---|---|---|
| ocr 机扫 | 462（high 61 / med 179 / low 220 / null 2） | 0 | 65（high 42 / med 21 / low 2） | 397（high 19 / med 158 / low 218 / null 2） |
| codex-security | 5（high 1 / med 2 / low 2） | 0 | 3（#1、#2、#3） | 2（#4、#5） |
| 人工代码兜底 | 14（P2×2 / P3×12） | 0 | 2（F-BT-1、F-VSM-1） | 12（其中 F-ASR-1 并入 WAV 族、F-VSM-3 并入 CONC-1） |
| 人工非代码 | 8（P2×2 / P3×6） | 0 | 2（F-P2-1、F-P2-2） | 6（其中 F-P3-1 并入三命中条） |
| **并条后统一** | **489 原始 →** | **0** | **43** | **410** |

P2 中双通道及以上命中 4 条（SEC-1、SEC-3、SEC-4、CONC-1）；单通道：ocr 35 条、codex 1 条（SEC-2）、人工非代码 1 条（SEC-5）、人工代码 2 条（FUNC-5、FUNC-6）。

> 本报告为分诊产物，全部内容可回溯至四个来源文件；除并条外未做任何合并、拆分行号或改写引用。
