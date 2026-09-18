# 小朵 Android 开发日志

## 2026-09-18 S52：S19-1 噪声上攻通道停用（逐块 RMS 探针定案早切真因）

接 S50/S51：用户报告「聆听时间足够长，说档案时还在聆听但没识别到，且基本无停顿（词间 <1s）」——证词与「回忆型停顿」结论冲突，重新取证。用 AudioProbe（诊断构建：armed 时去掉 0.015 门全量落行）拿逐块 RMS 包络，两个实锤：

1. **持续元音被 plateau 通道中途判停**：用户发「啊——」2s+（VAD 0.15~0.30 稳定，波动 0.10~0.15），VAD 在 02.012 中途判停——S19-1 的 plateau 通道把「稳定电平」判为「AGC 钉死噪声」触发上攻（floor 0.35/块 → end=floor×1.4 数块内越过说话者电平）。**稳定是长元音与钉死噪声的共同特征，判据天然误伤**。
2. **自然语句尾段悬崖**（17:36 轮逐块）：…0.30→0.26→0.18→0.095→**纯噪底 0.0013**（100ms 内 -17dB），之后 5.9s 无任何语音能量跨过派发/PROCESSING——half-peak 通道（level<peak/2 在自然语音动态里常真）触发上攻级联同样成立。

**根因分层定案**：①S19-1 上攻通道在正常语音动态上误伤（判停阈值被自己抬过说话者电平）；②真并发现在：codec AGC 对热输入砸模拟增益（峰值 float 越大切得越早：0.19→切 6 字 / 0.16→8 字 / 0.073 元音→存活；声学噪底 0.0082 也被压到 0.0013=只剩电子噪底，约 30 倍砸益指纹）——用户最初「是否与收音设备有关」问对了，且用户证词（无停顿）推翻了我此前「回忆停顿」的误判（证伪时误把 AGC 方向建反）。

**处置**：①`NOISE_ATTACK_ENABLED=false` 停用上攻通道（当前板端噪底 0.0013~0.002，S23 NLMS 后泵态不再观测，上攻收益消失、误伤恒在；开关保留，泵态复发再审）；三个 S19-1 旧测重写为「有界行为锁定」（泵态拖尾由 max_utterance 8s 兑底、幽灵轮由 S19-2 空 ASR 续听兜底）+ 新增持续音回归锁。②用户侧验证：中等音量（峰值压在 AGC 触发线下）连说两轮完整句「查询2022年的合同档案。」「查询2021年的合同档案。」全部完整收账。③遗留：codec AGC 热输入砸增益是硬件/固件层问题（UNPROCESSED/VOICE_RECOGNITION 均躲不过），软件侧候选=软 AGC 补偿/限幅预压（大票候选），当下降音量用法规避。

**追加（mode=0 核实）**：用户设回出厂 mode=0 并重启（source=7）后实喊「查询2023年的档案」完整收账（33600 samples）——注意 Settings 只写 pref，采集源构造时固化，**改后需重启才生效**；另澄清常数表：板上 pref0→source=7（VOICE_COMMUNICATION）、pref2→source=9（UNPROCESSED），全日 source=7 即 mode=0（此前「用户设过 mode1」系常数表记误，cliff 与模式无关的结论不变）。悬浮窗 pref 当前 false（用户关闭，待定是否恢复）。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（633 全绿，含 S52 重写 3 测 + 持续音回归锁）；AudioProbe 诊断构建（armed 全量 cap rms）板端逐块包络取证；adb install -r 后真机两轮完整句验证 + force-stop 重启后 mode=0 复验一轮
verify_claim: 持续稳定音 30 块（3s）无中途判停（旧代码 ~2s 即切）；用户中等音量连续两轮「查询202X年的合同档案。」ASR 全文完整（36800/38400 samples），S51 hold/dispatch 链正常；上攻停用后泵态场景由 max_utterance 有界兑底；mode=0 重启生效后完整句复验通过
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt(探针去门诊断), app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 S50+S51 语音端点双修：判停窗/阈值拆分 + 断句续听

用户报障「总是提前判停，是否与收音设备有关」。三阶段取证闭环：

**取证一（S50，判停参数）**：板端 15:59 四连切实锤——轻声说话句尾弱音节 RMS 跌破 0.05 被计静音，600ms 计满切尾（「盘点当前有哪」「盘点当前有」「进行档案」「进行档案盘」，maxLevel 0.31~0.47 vs 大声完整句 0.83）。用户拍板 A+B 组合：判停窗 6→8 块（回桌面端对账值）+ 判停/判起阈值拆分（end 线独立 0.03）。<b>关键发现</b>：环境音自动校准没缺席（09-14 自动跑过 + 09-16 手动重校两次），但公式 `suggestVadSpeechLevel = p95×1.6 coerceIn(0.05,0.15)` 在安静域被下限钳死（p95=0.0018×1.6=0.0029→0.05），校准数学上给不出低于 0.05 的判停线——拆分是唯一出路。改动：VadDetector 新增 `SPEECH_END_LEVEL=0.03` + `speechEndLevelProvider`（判起线继续跟校准联动；恒 end&lt;start），SILENCE_END_TICKS 6→8；KwsDefaults 注释/对账表补注 4 同步演进史。效果：切尾点从第 7 字推到第 8 字（弱音节不再掉线）。

**取证二（排除 AGC 假设）**：正常音量对照轮（maxLevel 0.39 更响）反而切得更早（第 7 字）——<b>切尾位置=气口位置，与音量无关</b>；换采集源关 AGC 的路 S18 已实测不通（rk809 codec 级，VOICE_RECOGNITION 也钉死）。真因：固定静音窗判停吃不掉长句字组气口，且切尾改变 LLM 工具路由（盘点被当查询调 c_archive_query）。

**修复（S51 断句续听，用户拍板）**：判停片段不即派发——`handleVadEvent` 截流→累积+重启 VAD+静音块倒计时（`CONTINUATION_TICKS=10`/1000ms）；语音级块或 `vad.isSpeaking`（含判停静音尾）冻结倒计时；窗耗尽合并成单条 utterance 走 `onUtterance` 原语义派发。直通例外：按住松开 stopListening（松开=明确说完）与单测直注不经截流（15 个既有 VSM 测零改动）。坑：判停静音尾与续听窗共用音频流重叠计数，窗剩余&lt;判停窗时片段成型中途提前派发（新用例 2 实锤）——`isSpeaking` 期冻结解。backToIdle/startListening 单点清挂起（同 kwsPaused 纪律）。<b>边界拍板</b>：回忆型 2s+ 停顿不迁就（每轮延迟不可接受），按住说话是正解；续听窗 1000ms 容忍总静音≈1.8s 覆盖短语间气口。

真机：S51 hold/dispatch 链 3 轮全对（713ms 派发无残留），一口气长句「查询当前有哪些2021年」完整收账。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（631 tests 全绿，含 S51 新增 4 测：hold 不即派发/续说冻结+双片段合并 20800 samples/cancel 清挂起/按住直通；VadDetectorTest S50 锚同步 6 处）；adb install -r 192.168.137.45:5555 + 板端日志取证（S51 continuation hold/dispatch 锚 + ASR text 对照）
verify_claim: S50：轻声弱音节 0.03~0.05 域计回说话（切尾点 7 字→8 字）；判起线校准联动零回归（S18/S19-1 用例全绿）。S51：带气口说话片段自动合并（hold→静音耗尽→单条派发）；cancel/超时/重唤醒清挂起无幽灵派发；按住松开零延迟直通；一口气长句 ASR 完整、延迟 = 判停 800ms + 续听窗 1000ms ≈1.8s（既定代价）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsDefaults.kt, docs/voice-param-reconciliation-2026-07-28.md, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS40BlindWindowTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt(新), docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-17 S48 Agent 全量拉取 + 会话闲置轮转 + 悬浮窗「创建新会话」

三项需求（grilling 一轮定案）：Q1=a 大 pageSize 一次拿全（失败退循环翻页）；Q2=惰性超时轮转——按消息 ts 记录，最后一条消息超 10 分钟无后续才在下次交互时新建（空会话无时间戳永不触发，杜绝空会话循环，用户拍板）；Q3=超时轮转完全静默；Q4=菜单「查看最近一轮」→「创建新会话」+ busy 置灰 + 成功气泡反馈。

#### 票 1：Agent 全量拉取
- 真机实测（curl + 板上 token）：`agent/list` 空 body 黔回前 10 条（信封 total:14/pageSize:10/pages:2，PageHelper 风格）；`{"pageSize":500}` 一把拿全 14 条
- `AguiClient.listAgents`：循环翻页聚合（pageNum 递增直到不足一页；20 页硬上限防服务端忽略参数死循环；401 透明重登后清空半卷从第 1 页重拉）；`listAgentsOnce` body 改 `{"pageSize":500,"pageNum":N}`
- `SettingsScreen` 选择器：限高 320dp 滚动列表（全量后不再撑爆页面）
- 坑：`chatNewSession?.invoke()` 在 runCatching 内产生 Boolean?，`getOrElse{false}` 后仍是可空——`?: false` 在 lambda 内先兜平

#### 票 2：会话闲置 10 分钟惰性轮转
- `SessionRules.shouldRotateForIdle(lastMessageTs, now, timeoutMs)`：纯函数；`ChatMessage.ts` 字段已有，零新增存储
- `ChatViewModel.rotateSessionIfIdle()`：静默版（不查 busy/无提示/存储异常降级不轮转），与 newSessionInternal 同构
- 触发点两处：文字 `send()`（upload gate 后、session 捕获前）+ 语音 `onUserUtterance`（置 voiceActive 守卫窗之前，rotate 后本轮消息入新会话/新 thread）
- resends 不轮转（旧会话内操作语义）

#### 票 3：悬浮窗菜单「查看最近一轮」→「创建新会话」
- `OverlayMenu` 行替换 + `canCreateNewSession` 置灰（busy 快照）
- Service→VM 双桥（chatInterrupt 同范式）：`chatBusyProvider={busy}`（菜单弹出时刻快照）+ `chatNewSession={newSessionFromOverlay()}`（busy 拒绝 false 双保险）；不进 APP，Service 侧 `showBubble(CHAT, "已开启新会话")` 反馈
- Activity 的 viewLastRound 哨兵机制保留未删（仅换菜单入口，surgical）

```evidence
verify_cmd: ./gradlew testDebugUnitTest（606 例全绿，+11：翻页聚合/401 重拉/分页 body 契约/超时判定 4 例）；真机：logcat `agent/list POST ... pageSize=500 pageNum=1` + 设置页可见超 10 个 agent（s48_agents.png）；chat send 空会话不触发轮转 ✓；悬浮窗菜单 dump 六项含「创建新会话」✓（s48 菜单 dump）。用户补验（2026-09-17 10:30）：② 菜单「创建新会话」板端日志实证 `menu: create new session, ok=true`（桥链路/busy gate/气泡链路通）；① 闲置轮转板端日志未捕获 rotate 记录（用户口头确认完成），规则层由 4 例单测 + 空会话不触发实测兜底
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/SessionRules.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientAgentListTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/chat/SessionRulesTest.kt
]
```

## 2026-09-17 S46 密集架页面导航（本地意图直连，不走平台）

需求：语音/文字「打开借阅/归还/盘点页面」→ 密集架 app 跳对应页面。平台侧注册 open_page MCP 工具不确定可行 → 改走本地意图分流（零平台改动），完全复用 S26-3 设备技能同款模式。

- 新增 `device/ShelfNavIntentMatcher`（纯 Kotlin，JVM 单测 20 例）：保守匹配——导航动词（打开/跳转/进入/切换/切到/去…）或页面名词 + 目标词（借阅/借书、归还/还书、盘点）三路取先现者；**含执行对象词（档案/文件/编号/盒…）绝不拦截**——「借阅档案A」必须走平台 MCP 工具链（防双控语义不破）
- `ShelfMcpBridge`：`NAVIGATE_PAGES = {borrow, return, inventory}` 白名单 + `navigate(page)` **同步** POST `{"Op":"mcpUiNavigate","page":...}`（区别 sync 的 fire-and-forget：导航需成败回执）；导航非 MCP 工具，不进 SYNCED_TOOLS
- `ChatViewModel`：文字轮 deviceCmd 后同级拦截 → `runShelfNavTurn`（零 LLM；成功「已打开XX页面」/失败「密集架联动不可用…」作为 assistant 终态上屏）
- `VoiceSessionManager`：S26-3 同款注入 `shelfNavMatcher/shelfNavExecutor`，命中走同一条分句/TTS 管线；密集架无自带播报 → **不置 turnSawToolCall**（正常 TTS，区别设备轮静音语义）
- `VoiceClientFactory`：按 `shelfBaseUrl` 非空装配（空=禁用零劫持）
- 密集架端（D:/mijijia 同步改动）：AppController 新增 mcpUiNavigate Op——borrow/return 走 searchPageRequest 跳检索页对应模式，inventory 走 openInventoryPage()（archives_inventory 同款）

边界：拦截后**不发云端**——「打开借阅页面」若发云端会被 LLM 误路由到 archive_borrow（执行语义）引发多余工具调用（S30 拦截语义同源）。

```evidence
verify_cmd: ./gradlew testDebugUnitTest --tests ShelfNavIntentMatcherTest（20 例全过）+ assembleDebug；密集架端 curl mcpUiNavigate borrow/return/inventory 三页真机跳转截图 + 非法 page 注入守卫（详见 D:/mijijia/docs/DEV_LOG.md S46 追加节）
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt,
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt,
  app/src/test/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcherTest.kt
]
```

## 2026-08-05 S8 UI 微调合集代码侧收口

### 会话背景
- 从 anzhuo4 死会话恢复（API 429 5 小时用量上限），加载上下文后继续
- 用户选择执行模式：串行关键路径（S8-3→S8-7）+ 派 opus critic 盲审
- 纪律：单测只增不减（基线 345），skipped=0，禁 mock/skip/删测；每守卫必须有变异锚测试

### 变更清单

#### S8-1 输入框视觉回滚两层+横线
- `ui/screens/InputBar.kt`：R3 单容器 → 两层独立 Surface + 层间 HorizontalDivider（outline 色 1dp）
- R3 功能修复全保留（行高 48、FunctionBar padding 4dp、isUp 快照、idle 粉底 gate、停止键、上传链）
- 纯视觉变更，无新单测

#### S8-2 图标白底
- `res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`：5 密度 PNG 替换（#FFFFFF 底 + 前景小朵形象）
- `res/values/ic_launcher_background.xml`：改 `#FFFFFF`
- 资源替换，无新单测

#### S8-3 TTS 中按住说话=打断并接续新一轮
- `voice/VoiceSessionManager.kt`：
  - `startListening`：speaking/processing 态按住（playWakeAck=false）→ cancelCurrentTurn → 进 LISTENING 采音
  - 3 处 sealed 回归守卫：consumeSentences 循环头 L490 + 合成后二查 L502 + 多轮迁移 L464
- `voice/VoiceSessionManagerBargeInTest.kt`：+2 测（PROCESSING 打断 4 断言 + SPEAKING 打断 stateFlow 闩）
- 测试 351→353

#### S8-4 气泡渲染 markdown 控制符修复
- `ui/overlay/BubbleTextStrip.kt`：裸 markdown 控制符剥离
- `ui/overlay/BubbleTextStripTest.kt`：+6 测（无空格标题/引用/加粗/斜体/行内代码/表格管道符）
- 测试 345→351（S8 开工前已完成）

#### S8-5 气泡文字改流式直出（撤 reveal 音频同步）
- `voice/VoiceSessionManager.kt`：
  - AG-UI delta 回调直接 bubble.show/append，与 TTS 播放解耦
  - 退役 playAndReveal/reveal 方法（RevealScheduler 类保留备查）
  - TTS 单句失败降级：气泡文字不受影响（delta 已上屏），仅跳过本句播放
- 无新单测（退役旧方法，delta 流式路径由现有 BargeInTest sealed 守卫覆盖）

#### S8-6 气泡「看不到开头」补救
- `ui/overlay/OverlayMenu.kt`：新增「查看最近一轮」菜单项（canViewLastRound 门控）
- `PetOverlayService.kt`：hasShownBubbleContent 标记 + playPetOnceThen companion 转发
- `MainActivity.kt`：handleViewLastRoundIntent + onNewIntent（EXTRA_VIEW_LAST_ROUND → ChatViewModel）
- `chat/ChatViewModel.kt`：pendingScrollMessageId 一次性事件（哨兵 `__last__`）+ consumePendingScroll
- `ui/screens/ChatScreen.kt`：LaunchedEffect(pendingScrollMessageId) 消费 → scrollToItem + listState 上提
- 无新单测（ChatViewModel 是 AndroidViewModel，项目无 Robolectric/mock 基建，纯 JVM 不可达；降级为真机取证）

#### S8-7 唤醒成功即时视觉反馈
- `voice/VoiceSessionManager.kt`：
  - `_wakeAckActive: MutableStateFlow<Boolean>` + 公开 `wakeAckActive` StateFlow
  - startListening(playWakeAck=true)：入口置 true + playPetOnceThen(JUMPING, WAITING)
  - transition()：SPEAKING + wakeAckActive → 跳过通用 TALKING 映射
  - wozaiJob finally：无条件 reset false（critic P2 修复）
  - cancelCurrentTurn：reset false
- `ui/screens/FullscreenVoiceScreen.kt`：LaunchedEffect 订阅 wakeAckActive，gate SPEAKING 映射
- `ui/PetView.kt`：playOnceThen 脚手架（上一会话已就绪）
- `PetOverlayService.kt`：playPetOnceThen companion 转发
- `voice/VoiceSessionManagerWakeAckTest.kt`：+3 测（时序/按住路径/cancel 复位）+ 1 增强
- 测试 353→356

#### S8-8 生成中等候态三点动效
- `ui/screens/ChatScreen.kt`：
  - TypingDots Composable（三圆点错相呼吸，900ms 周期，三角波 alpha 0.3↔1.0）
  - SENDING 状态替换原 CircularProgressIndicator + "小朵思考中…" 文字
- 无新单测（Compose UI 动画，重组不崩即为验收）

### 测试基线
- **356 绿 skipped=0 failures=0 errors=0**
- 增量：HEAD 345 → S8-4 +6 → S8-3 +2 → S8-7 +3 = 356
- 验证命令：`./gradlew.bat testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL

### 工作树
- `git diff HEAD --stat`：20 files, +595/-153
- 零 commit，所有变更在 working tree
- 真机 de61cd8 在线，apk 已装（app/build/outputs/apk/debug/app-debug.apk）

### Critic 轮次汇总
| 票 | critic | 结果 | P1 | P2 | P3 |
|---|---|---|---|---|---|
| S8-3 | 不逊 | 4 P2 → 修复 3，1 留档 | 0 | 4→1 | 0 |
| S8-5 | 不逊 | 1 P2 + 1 P3（首轮 critic 死亡，重派） | 0 | 1 | 1 |
| S8-6 | 不逊 | 1 P2 降级 P3 + 4 P3 | 0 | 0 | 5 |
| S8-7 | 不逊 | 1 P2 已修复 + 5 P3 | 0 | 0 | 5 |
| S8-8 | 不逊 | 2 P3 | 0 | 0 | 2 |

### 剩余事项（下会话真机验证）
1. **S8-5 P2**：delta callback check-then-act 竞态窗（L438 sealed → L444 bubble.show，cancel 可插入致幽灵气泡）——权限低，真机期顺修
2. **S8-5 P3**：BargeInTest 注释 "reveal 逐字查岗" → "delta sealed 拦截"
3. **真机逐票验收**（见 CONSTRUCTION.md 各票验收条）
4. **分票 git commit**（当前零 commit，建议每票一 commit + 真机证据截图附 commit message）
5. **S8-2 桌面截图**：浅色/深色壁纸各一，确认白底图标可读性（已抓 `C:\Users\Administrator\s8-2-desktop.png`）

```evidence
verify_cmd: ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL, 356 tests, skipped=0, failures=0, errors=0
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/PetView.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/res/mipmap-*/ic_launcher.png,
  app/src/main/res/values/ic_launcher_background.xml,
  app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStripTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerBargeInTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt
]
```