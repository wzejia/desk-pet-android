# 修复简报 · misc 包(P2 共 18 条)

> 来源:全量审计 2026-10-03(unified-findings.md)。字段原样取自 p2-p3-detail.json,行号未改动。
> 逐条:先在源码验证问题属实(不属实→记「证伪跳过」+理由),属实→先写失败测试(TDD)→最小修复→测试转绿。禁改本简报地界外的文件。SEC-3 若在你的地界:注意 prefs 迁移要兼容板端存量数据。

---

## SEC-2  [codex#2]
- 标题: 平台登录明文 HTTP 传输账号密码
- 文件:行号: (见 problem 内引用)
- 问题: 默认 POST http://10.0.18.166:8080，账号密码 JSON 明文；token 约 25 分钟过期即重发密码。（codex 位置：network_security_config.xml:7-11、NetConfig.kt:90、AguiClient.kt:283-296）
- 影响: 网内被动观察者（镜像端口/流氓 AP/ARP 欺骗）直接收取管理员凭据。
- 修法建议: 登录迁到既有 443 反代（loginUrl 按 agentStreamUrl 同法推导 https）；:8080 必须保留则显式 opt-in 并延长 token 免密周期。
- ocr 索引: []
---

## SEC-3  [codex#3,ocr]
- 标题: 凭证与 API key 明文存 SharedPreferences
- 文件:行号: (见 problem 内引用)
- 问题: 登录密码/bearer token/三家云 key 以明文字符串写入默认 SharedPreferences（codex 位置 NetConfig.kt:103-105,236-263、TokenStore.kt:43-45；ocr[145]=TokenStore.kt:34）；全仓无 Keystore/EncryptedSharedPreferences；TokenEntry.toString() 内嵌原始 token 与「严禁入日志」规则矛盾（ocr[144]=TokenStore.kt:6）。
- 影响: root 设备/本机短暂接触/debuggable dump 可直接读取；云备份面已被 allowBackup=false 关闭。
- 修法建议: 迁 EncryptedSharedPreferences（Keystore 后端）；TokenEntry 覆写 toString 脱敏。
- ocr 索引: [159, 145, 144]
---

## SEC-6  [ocr]  〔待复诊〕
- 标题: ShelfMcpBridge runId 未转义拼入手工 JSON
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt
- 问题: runId 未校验/转义拼入 callFor() 手工 JSON 字面量；ocr 认定来自平台 SSE（TOOL_CALL_START→ToolCallOutcome），codex 审同一候选以「runId client-generated、工具白名单」为由 reject——两通道判断相反。含引号/反斜杠即畸形 JSON 包，构造值可注入额外键（如第二个 "Op"，device 侧 last-wins 解析可能执行）。
- 影响: 最坏把 UI-only 同步变成非预期设备指令；前提是平台侧 runId 可被污染。
- 修法建议: runId 含引号/反斜杠/控制字符时丢弃该字段仍发同步（或校验字符集），与 resultBodyFor() 的 org.json 处理对齐。
- 复诊备注: 跨通道分歧：需读 ShelfMcpBridge/AguiClient 源码定谳 runId 实际来源；平台可控则 P2 成立，client-generated 则降 P3。
- ocr 索引: [190]
---

## ROB-4  [ocr]
- 标题: 采麦喂入 vs 原生引擎释放竞态（native use-after-release）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt
- 问题: kwsSink 采集线程 feed 无同步，onDestroy 持锁 release；sink null-check-then-invoke 窗口内旧闭包仍可喂已释放引擎；runCatching 无日志吞 teardown 失败（三重可达路径）。
- 影响: use-after-release → native crash（SIGSEGV，无 Java 栈）。
- 修法建议: feed 与 release 同一监视器串行（或 KwsEngine.feed 内原子 closed 标志）；记录被吞异常。
- ocr 索引: [201]
---

## ROB-5  [ocr]
- 标题: 气泡双窗口部分 addView 失败泄漏系统窗口
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt
- 问题: 第一个 addView 成功、第二个抛出时，失败路径 stopSelf 返回且 displayBox/interactionBox 未赋值，onDestroy 无从移除已挂窗口。
- 影响: WindowLeaked 型系统窗口泄漏，悬浮层残留。
- 修法建议: 记录首个已挂视图，失败路径先移除再退出。
- ocr 索引: [220]
---

## ROB-6  [ocr]
- 标题: 气泡发布 TOCTOU：过期 runnable 覆盖新轮次
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt
- 问题: judgePublish 调用线程校验、handler.post 延后执行；新轮 claim/隐藏落地后旧 runnable 仍无条件执行，以旧文本新建会话覆盖 bubbleStrip 并重开触摸。appendBubble 同型。
- 影响: 新轮次气泡内容被旧轮污染，追加喂错会话。
- 修法建议: 捕获 owner/round，posted runnable 内重新校验后才应用。
- ocr 索引: [221]
---

## ROB-7  [ocr]
- 标题: setBubbleTouchable 崩溃面（错误线程 + lateinit 未初始化）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt
- 问题: ① hideBubble/FORCE_HIDE 可从 binder/worker 线程到达，非主线程 wm.updateViewLayout 抛 CalledFromWrongThreadException；② 窗口未建（onStartCommand 前，service 可 bind、companion 实例 onCreate 即发布）时 lateinit displayLp 抛 UninitializedPropertyAccessException。
- 影响: 悬浮窗路径两类确定性崩溃。
- 修法建议: 移入 posted runnable 主线程原子执行；!built 降级 no-op。
- ocr 索引: [222, 223]
---

## DATA-1  [ocr]
- 标题: 会话/历史持久化「读失败→静默降级为空→破坏性回写」数据丢失链
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatHistoryCodec.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatStore.kt, app/src/main/java/com/xiaoduo/deskpet/chat/SessionIndexCodec.kt
- 问题: 索引/消息读失败或损坏一律降级为空且与「没有」不可区分，下一次变更/保存即以空内容覆盖好文件（108/109/110/241/250）；writeText 非原子，torn write 放大（111）；saveIndex 失败仅记日志且 createSession 照常返回 → saveMessages 全程静默 no-op（112）；删消息文件先于索引持久化（113）；Android org.json 把 JSON null 读成 "null" 幽灵会话 id（249+251）；@Synchronized 仅实例级，第二实例交错 RMW last-writer-wins（115，次级）。
- 影响: 一次瞬时 IO 错误/断电即可永久抹掉会话列表或整段对话历史，全程无用户可见信号。
- 修法建议: 读失败可观测并以标志/备份拒绝破坏性覆写；writeText 改临时文件+原子 rename；先 persist 索引后删文件；null 用 (opt as? String) 类型安全读；createSession/saveIndex 失败向上传播。
- ocr 索引: [108, 109, 110, 111, 112, 113, 241, 249, 250, 115, 251]
---

## DATA-2  [ocr]
- 标题: ChatViewModel 静态桥滞留已清 VM → 旧快照回写覆盖新历史
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt
- 问题: 三个静态桥捕获 this VM 且无 onCleared 反注册；VM 销毁后 Service 仍持旧实例，期间悬浮窗 chatInterrupt 对失效实例 markCancelledDone+persist。
- 影响: 旧 _messages 快照整写回会话文件覆盖新 VM 已持久化内容（历史回退）；旧实例被静态引用滞留。
- 修法建议: onCleared() 置空三桥，Service 侧判空。
- ocr 索引: [126]
---

## DATA-3  [ocr]
- 标题: 语音/文字轮双写者（busy 守卫缺口）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt
- 问题: busy 只看 _phase/_voiceActive，「我在」播放/聆听期两标志均假，send 通过；语音 utterance 到达即与在途文字轮成双写者。
- 影响: 对 _messages 交错 RMW 后写整体覆盖先写：丢消息/丢 delta；两轮收尾并发 persist 文件层 last-writer-wins。
- 修法建议: onUserUtterance 开头检查在途文字轮（打断或延后上屏）；_voiceActive=true 提前缩小 TOCTOU。
- ocr 索引: [121]
---

## DATA-4  [ocr]
- 标题: ChatViewModel 多线程 RMW 竞态（_messages/_files/uploadJobs）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt
- 问题: _messages 读-改-写跨语音 IO/OkHttp IO 协程/主线程三方非原子；_files IO 协程与主线程并发写；uploadJobs 非线程安全 HashMap 被三线程读写。
- 影响: 互吞 append/delta、已删附件复活、HashMap 结构损坏。
- 修法建议: StateFlow.update 原子 RMW 或写收敛单一调度器；uploadJobs 换 ConcurrentHashMap。
- ocr 索引: [122, 123]
---

## DATA-5  [ocr]
- 标题: ChatTurnLogic rawBuffers 非线程安全
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatTurnLogic.kt
- 问题: 普通 HashMap + StringBuilder + 非原子 getOrPut；流式线程 applyDelta 与主线程终态处理并发。
- 影响: map 损坏、双 StringBuilder 丢 delta、终态后残留 buffer。
- 修法建议: ConcurrentHashMap.compute(assistantId){...} 单临界区完成追加与读取；或文档化单线程契约。
- ocr 索引: [235]
---

## DATA-6  [ocr]
- 标题: applyDelta 无状态守卫，迟到 delta 复活已终态轮次
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatTurnLogic.kt
- 问题: markDone/markError/markCancelledDone/prepareResend 之后到达的迟到/重复 delta 以 cleaned text 重建 rawBuffer 并把终态消息翻回 STREAMING。
- 影响: 已完成轮次带陈旧内容复活，StringBuilder 泄漏至 reset()。
- 修法建议: 仅 SENDING/STREAMING 状态应用 delta，其余原样返回。
- ocr 索引: [236]
---

## CONC-8  [ocr]
- 标题: 设备/密集架导航轮不可打断，busy 吞输入
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt
- 问题: runDeviceTurn/runShelfNavTurn 不登记 chatJob/activeAssistantId、不捕获 turnEpoch，cancelCurrentTurn 直接 return。
- 影响: barge-in 打不断设备轮；busy(SENDING) 吞掉用户输入直到自然结束（DeviceClient/ShelfMcpBridge 有 4s/6s 超时兜底）。
- 修法建议: 与 runChat 同款登记，使取消路径能 markCancelledDone 并复位 phase。
- ocr 索引: [124]
---

## FUNC-1  [ocr]
- 标题: 意图匹配缺疑问句/否定句门控（家族）
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/device/DeviceIntentMatcher.kt, app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt
- 问题: 动作分支先于疑问检测且开关词是问句子串：「整理台开机了吗」发 PowerOn、「净化柜强力模式是什么」切强力（282）；否定不处理：「不要打开整理台」执行 PowerOn（283）；fan-speed 分支命中但解析失败吞掉 query 回退（281）；ShelfNav 问句/聊天句被劫持为导航（187）。
- 影响: 自然问句/否定句确定性误触发物理设备动作或错误跳页。
- 修法建议: 动作分支前加疑问标记门（吗/呢/什么/？）与否定门（不要/别），问句路由 QueryStatus 或 null。
- ocr 索引: [282, 283, 281, 187]
---

## FUNC-2  [ocr]
- 标题: 关机校验 RunFlag 缺省 0 误报成功
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/device/DeviceClient.kt
- 问题: optInt("RunFlag") 缺键默认 0，无 err 字段的空/畸形状态体被判「成功关闭」。
- 影响: 关机未证实却向用户报成功。
- 修法建议: 哨兵默认（-1），缺 flag 即失败。
- ocr 索引: [180]
---

## FUNC-3  [ocr]
- 标题: FanSpeed 回读校验硬编码 ZLT 忽略 cmd.device
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/device/DeviceClient.kt
- 问题: 净化柜(JHG)风速指令的回读校验打在错误设备上。
- 影响: 误报失败或掩盖真实失败。
- 修法建议: 回读改用 cmd.device。
- ocr 索引: [181]
---

## FUNC-4  [ocr]
- 标题: ShelfNav 源→目标句式取首词导航错页
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt
- 问题: 首现决胜：「从借阅切到归还」返回 borrow（源页）且无 LLM 兜底；KDoc 把错误目标写成预期。
- 影响: 该句式 100% 导航到用户要离开的页面。
- 修法建议: 候选跨多页时返回 null 交 LLM。
- ocr 索引: [186]