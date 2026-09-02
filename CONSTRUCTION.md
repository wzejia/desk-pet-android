# CONSTRUCTION.md — 小朵助手化施工清单

> 来源：`docs/spec/assistant-app.spec.md`（v1）｜ 创建：2026-07-30
> 用法：主编排会话从这里取票派发给 builder 子智能体；完成一项勾一项 + `PROGRESS.md` 记证据。
> 每切片尾部是「严评循环」（编排协议见 CLAUDE.md）：builder 交付 → critic 子智能体盲测严评 →
> 不过就打回返工再评，连败 3 次换下一项并记 BLOCKED.md。

## 切片总览（依赖序，S1 排雷最前置 = KD4 风险前置原则）

| 切片 | 内容 | 验收 | 状态 |
|---|---|---|---|
| S0 | 地基：Compose 引入 + M3 主题（深色）+ 导航 + NetConfig/TokenStore + OkHttp 工厂 + CrashReporter | 单测 +  assembleDebug 绿 + 真机深色切换截图 | ✅ |
| S1 | AG-UI 链路排雷：登录/token + SSE 消费器三件套 + 真机首聊 | 消费器 JVM 单测 + 真机日志首聊成功 | ✅ |
| S2 | 聊天主界面：消息流/Markdown/输入条/历史持久化/状态反馈 | 真机文字对话一轮 + 杀进程回看 + 对标截图 A | ✅ 返工+导航+边栏+多会话全收口(213 测绿,盲测双组相对尺胜);斜体/渐变/遮罩 3-bug 已修 |
| S3 | 悬浮窗升级：双窗口 + 气泡 + 长按菜单 + 持久化三件套 | dumpsys 双窗口 + 对标截图 B + clamp 单测 | ✅ B3 定案关闭+默认外观图+盲测 v4 遮挡 4:2 胜(形态组不逊)+气泡所有权上移(critic 不逊)+重 clamp 两轮定案 |
| S4 | 语音链路：采麦/VAD/ASR/分句/TTS/半双工/reveal | 参数对账逐项勾 + 真机按住说话一轮 + 对标截图 C | ✅ 引擎+对账齐;真机全链路两轮实测过(2026-08-03,含流式上屏);降级路径取证;P3×3 已清 |
| S5 | KWS 常开：sherpa-onnx + 前台服务 + 校准入口 | 真机「小朵小朵」唤醒 10 次成功率记录 + 合规取证 | ✅ 代码+唤醒 10/10+0 误唤醒+通知/指示点取证全收口;剩 B2 主观项(听感/AEC/sticky)随日常使用回收 |
| S6 | 适配与对标终评：深色 + 多屏矩阵 + 四组并排严评 | 对照矩阵截图 + 严评记录全「不逊」 | 🔶 深色五页✅/wm 矩阵✅(720 抓 P2 已修)/盲测 3 组胜(悬浮窗/聊天页/输入区);气泡组 D 用户拍板挂起保持原样 |
| **S7** | **UI/语音/输入全面升级（S7-a~g）** | 各子切片验收见下；单测只增不减(基线 274) | ✅ 收官（345 测绿；真机验收全闭；盲测组 2 三连败关账 R3 保留；AEC spike 用户拍板挂起） |
| **S8** | **UI 微调合集（S8-1~8）**：输入框回滚两层+横线 / 图标白底 / TTS 中按住=打断并接续 / 气泡渲染残留修复 / 气泡文字改流式（撤 reveal） / 气泡看不到开头补救 / 唤醒视觉反馈 / 等候三点动效 | 各票验收见下；单测只增不减（基线 345→356） | ✅ 全部收口（358 测绿 skipped=0；真机逐票验收 2026-08-05/06 过；真机期顺修 P2 竞态窗+strip-empty 死路+多功能键边沿缺陷+wozai 重制 e3，详见各票与 DEV_LOG 2026-08-06） |
| **S10** | **悬浮窗贴边收纳（半身贴边）**：拖至左右缘松手滑入半身 / 单击滑出+可拖出 / 重启保持贴边 | 吸附几何 JVM 单测（模式 C）+ 模拟器/真机像素断言；voice/** 零 diff | ✅ 收口 2026-08-19（模拟器 B/A/A2/C/C2/D/E/F/G + 真机 RM-1~6 全绿，证据 `docs/verification/v16-edge-dock/`；⚠️ S10-1/S10-2 critic 独立评审欠账，08-21 配额恢复后补派） |
| **S11** | **唤醒词准确率排查+打断核查**：阈值事故回归 / 文字轮云端分支丢失 / 门控时间轴碎裂（真凶）三修复 + spike 真重做 | 一声唤醒真机 10/10 + 基线只增不减 | ✅ 收口 2026-08-20（五票全 critic 不逊；一声 10/10；证据 `docs/verification/v17-kws-accuracy/`；spike 真机观测待做） |
| **S12** | **唤醒词打断转正**：SPEAKING/PROCESSING 进 KWS + 唤醒路径打断接续 + spike 设施退役 | spike 双合格（误触发 0/4min+检出 8/10）+ 真机打断 8/8 | ✅ 收口 2026-08-21（正常+大音量两轮 8/8；观察票=AEC 偶发失效态；证据 v17 README §6） |
| **S13** | **形象动作改造**：四循环态静止=idle 行第 0 帧 / 说话=idle 行 6 帧@120ms / 唤醒跳·点击挥手取消 | 457 测绿 + 模拟器静止双图 MD5 一致 + 说话期≠静止帧 | ✅ 收口 2026-08-24（原型过目后拍板；证据 v18；13d9206） |
| **S14** | **全屏语音页交互改版+「我在」可视化**：「我在」气泡+全屏大字 / 三段式重排 / 底部抽屉双键 / 字幕富文本滚动 / 白屏销案 | 460 测绿 + 模拟器五相位+抽屉开合 + 旋转零 churn | ✅ 收口 2026-08-24（真机现场验收全过；证据 v19） |
| **S15** | **聆听期反馈泡+「我在」驻留**：进聆听显「在听…」离即收 /「我在」驻留 2.5s 后内容顶替 | 461 测绿 + 模拟器按住说话日志链+截图 | ✅ 收口 2026-08-25（真机现场验收过） |
| **S16** | **平台智能体选择**：设置页列表单选 agent（/agent/list 443 反代实测）/ agentId 动态 provider / 会话记忆保留 | 467 测绿 + 模拟器列表/选择/持久化/换 agent 对话 | ✅ 收口 2026-08-29（真机风格感受留日常） |
| **S17** | **全屏字幕三合一适配**：跨区字幕+比例系统化+列宽钳制 + 上让位防护栏 / 纯 Kotlin 布局计算器 | 472 测绿 + 模拟器横竖屏 + rk3568 板子落位 | ✅ 收口 2026-08-29（手机零回归设计，真机留日常） |
| **S22** | **播放期影子低阈值 KWS**：第二 spotter 命中记日志（数据定转正阈值） | 489 测绿，板子已装 | 🔨 代码收口 2026-09-02（待一晚数据） |
| **S23** | **NLMS 参考回声消除**：TTS PCM 作参考减回声喂 KWS（P4 提前，根治） | 493 测绿（ERLE≥10dB 单测锚） | 🔨 代码收口 2026-09-02（待板子 A/B） |
| **S21** | **工具调用后需重新唤醒**：工具轮回 IDLE 不走多轮续听（独立开关默认开） | 488 测绿（e2e 双开关路径） | 🔨 代码收口 2026-09-02（真机现场待回收） |
| **S20** | **MCP 调用结果静音开关**：TOOL_CALL 标记 -> 含工具轮跳过 TTS（文字照上屏） | 486 测绿（e2e 双开关路径） | 🔨 代码收口 2026-08-31（真机现场待回收） |
| **S19** | **自适应 VAD 阈值 + 空 ASR 续听（P1+P2）**：EMA 双轨噪声底（平台/半峰双通道）/ 空文本续听上限 2 次 | 482 测绿（P1×4+P2×3+VAD 16 全绿） | 🔨 代码收口 2026-08-31（板子现场待回收） |
| **S18** | **VAD 阈值校准联动+板子声学诊断**：阈值 provider 注入 / 校准双值写入 / 采麦源三档 / 电平轨迹诊断 | 475 测绿 + 板子校准生效实证 | 🔨 S18-1/2 收口 2026-08-31（板子终验/自适应阈值待回收） |

## S0 地基

- [x] Compose BOM + Kotlin compiler 扩展接入（Kotlin 1.9.24 对应 compiler 1.5.14），AGP 构建绿
- [x] Material3 主题：双色色板（浅色对标 ChatGPT 留白 / 深色纯黑底），`isSystemInDarkTheme` 跟随
- [x] 导航：Chat（主）/ Settings / Permission 三页（单 Activity + Compose Navigation）
- [x] NetConfig（5 字段可编辑 + 内置默认 10.0.18.x）+ TokenStore（25min）+ Prefs 迁移测试
- [x] OkHttp 单工厂：NO_PROXY + host 白名单 TrustManager（KTD4）
- [x] CrashReporter：未捕获异常写 files/logs/crash-yyyy-MM-dd.log + 上报入口
- 验收：`./gradlew.bat assembleDebug testDebugUnitTest` 绿（单测 ≥17 基线不退）；真机深浅色切换两张截图

## S1 AG-UI 链路排雷（最前置，理由：KD4 链路风险第一周上真机）

- [x] AguiSseConsumer 纯 Kotlin 状态机：事件解析 + 三件套（终止帧/空 run 重试/SNAPSHOT 兜底）
- [x] 单测覆盖：正常流 / 断流无终止帧→error / 空 run×3→error / SNAPSHOT 补尾 / content 分片兼容
- [x] AguiClient：登录（8080）→ token 缓存 → 443 SSE POST 增量单条
- [x] 真机：临时调试入口发一条「你好」，日志取证首 token 与全文（凭据读 `.scratch/secrets/platform-account.json`）
- 反向验证：单测里故意删终止帧断言 → 必须变红 → 还原贴全绿
- 验收：单测新增 ≥8；真机日志含 `RUN_FINISHED` 与完整回复；**日志全文 grep 无 token 明文**

## S2 聊天主界面

- [x] ChatMessage + ChatStore 持久化（JSON 文件或 Room，选型记 notes）+ 单测
- [x] 消息流 LazyColumn：双气泡 + Markdown（粗体/列表/代码块）+ 流式整段追加 + 状态点（sending/streaming/done/error）
- [x] 输入条：按住说话按钮（主）+ 键盘切换 + 发送；IME 跟随（adjustResize）
- [x] 失败重发 / 清空历史 / 杀进程重进回看
- 验收：真机文字对话 ≥3 轮成功；重启后历史在；对标截图 A（vs ChatGPT 输入区 + 消息流）

## S3 悬浮窗升级（双窗口）

- [x] 显示层窗口（~2× 宠物，NOT_TOUCHABLE）+ 交互层窗口（宠物尺寸）拆分（后用户拍板职责翻转：PetView 回交互层，显示层=气泡专用条）
- [x] 气泡 Composable/View attach 显示层顶部（§4.2 参数）+ show_bubble 开关接线
- [x] 长按菜单：PopupWindow（打开 App / 语音开关 / 隐藏悬浮窗）
- [x] 位置 clamp 纯函数 + 单测；位置/开关持久化；杀进程恢复
- 验收：dumpsys 取证双窗口 flags（显示层 NOT_TOUCHABLE）；拖拽/单击/长按真机各 ≥3 次；对标截图 B（vs 手机精灵）

## S4 语音链路（参数对账表逐项勾，勾完才算完）

- [x] AudioCapture 单流复用 + 协程 channel 分发（回调只推队列）
- [x] VadDetector（§3.4 照抄：0.05/3 块/8 块/deque=6/timeout 30s 显式）+ 单测（合成 PCM 喂入）
- [x] AsrClient（9802，language="zh"）/ TtsClient（9803 直连，WAV 剥头拼 PCM）
- [x] SentenceSplitter（切点/MIN=4/MAX=30/逐字符喂）+ TtsSanitizer（白名单+连续空白压缩）+ 单测
- [x] VoiceSessionManager 四态半双工 + KWS pause flag 挂点（S5 用）
- [x] RevealScheduler（句级 PCM 时长均分 / 降级 4.5 字/秒）+ 单测
- [x] App 按住说话全链路：按下→VAD→ASR→AG-UI→分句→TTS→气泡+reveal
- 验收：对账表 Kotlin 验收清单逐项打勾（附证据）；真机按住说话 ≥3 轮；降级路径（断 TTS）取证

## S5 KWS 常开

- [x] sherpa-onnx-1.13.4.aar 进 app/libs + abiFilters arm64-v8a；模型 5 文件进 assets
- [x] KwsEngine 封装（newFromAsset / threshold 0.5 / 消费线程 decode）
- [x] VoiceForegroundService：microphone 类型声明 + 常驻通知 + 用户可见动作拉起 + START_STICKY
- [x] 能量门控可配置（默认 0.015）+ 校准入口（采集 RMS 分布→建议阈值→一键写入）
- [x] 唤醒→VoiceSessionManager listening；speaking 时 pause flag
- 验收：真机「小朵小朵」唤醒 10 次记录成功率与误唤醒数（目标 ≥8/10 且无误唤醒，不达标校准后重测）；
  前台服务通知 + 麦克风指示点截图取证

## S6 适配与对标终评

- [x] 深色模式全页面走查（聊天/设置/权限/气泡/菜单）
- [x] 多屏矩阵：`adb wm size` 模拟 1080x2400 / 1440x3168 / 小屏 720x1600，聊天页 + 悬浮窗截图各一张（验完 `wm size reset`）
- [x] 四组并排严评：A 聊天页 / B 输入区 / C 悬浮窗形态 ✅（相对尺逐项不输）；D 气泡——用户拍板挂起保持原样
- [x] 严评不通过项回炉（连败 3 次记 BLOCKED 换项）
- 验收：矩阵截图齐 + 严评记录全「不逊」+ 全部单测绿

## S7 UI/语音/输入全面升级（来源：`docs/spec/assistant-ui-voice-input.spec.md` + ADR 0002/0003）

> grilling 2026-08-03 定案。9 需求经逐项拷问+代码查证+DeepSeek/平台实测。
> 已实现项不重复造：scrim 点关 / 设置入侧栏 / drawer+hamburger / 会话删除 / 会话切换+关drawer（grilling 查实，基线保留）。

### S7-a 会话管理（A：废弃聊天记录页 + 重命名路线C）

- [x] 删除 `ChatHistoryScreen.kt` + `AppNavHost.CHAT_HISTORY` 路由 + `SessionDrawerContent`「聊天记录」入口（保留 `ChatHistoryCodec`，仅命名遗留）
- [x] `ChatSession` 加 `titleManual: Boolean = false`；`SessionIndexCodec` 同步加字段（向后兼容缺省 false）
- [x] `ChatStore.saveMessages`：`titleManual=true` 跳过 `deriveTitle`（防手动名被覆盖）
- [x] `ChatStore.renameSession(id, title)`：本地改 title + 置 titleManual + 存索引；+ JVM 单测
- [x] `AguiClient.renameThreadForSession`：调平台 rename（对称 `deleteThreadForSession`，**端点已从桌面仓查实** `POST {threadBaseUrl}/renameThread` body `{threadId,threadName}`），fire-and-forget 失败只 log；+ 单测
- [x] `ChatViewModel.renameSession`：busy 守卫 + 本地改名 + 异步平台同步
- [x] `SessionDrawerContent`：长按弹操作菜单（重命名/删除）替代「直接弹删除确认」；重命名 AlertDialog + OutlinedTextField
- 验收：✅ 全过（2026-08-03）——聊天记录页/路由/入口删；长按弹菜单截图；**重命名后发新消息 title 不被覆盖**（S7RenameProbe 实证+截图）；平台 rename 日志成功/失败双路径取证（renameThread ok / WiFi 断开期 fire-and-forget 本地保留）；单测 +12（294 绿，critic 不逊+变异双红）

### S7-b 顶栏瘦身（B）

- [x] `PetStatusBar`：去「小朵」Text + 头像 40→32dp + 留状态文案 + 去 vertical padding（m3 IconButton 48dp 热区撑栏 ~48dp）
- [x] 右上角预留位（S7-d 全屏语音入口，Spacer 48dp）
- 验收：✅ 全过（2026-08-03）——真机截图：顶栏收紧、无「小朵」文字、状态文案「待命」在

### S7-c 气泡（C：字号/高度/宽度/自动滚/markdown 渲染）

- [x] `BubbleView`：textSize 11→13f；`MAX_HEIGHT_DP` 74→100
- [x] `PetOverlayService` 显示层窗口宽 2×宠物(~384dp)→~260dp（`BUBBLE_WIN_WIDTH_DP=260`）
- [x] `BubbleView` 自动滚动：`ScrollingMovementMethod` + 打字机 tick 后 `post{scrollToBottom}`（critic 打回 1 条：同步滚动用旧 layout 末字换行不贴底 → 返工过）
- [x] `BubbleTextStrip` 反转「剥离」→「渲染」：纯 Kotlin `StyledText` 模型（粗体/斜体/行内代码/列表 •；JVM 可测）+ BubbleView `toSpannable`（StyleSpan/TypefaceSpan+背景）；表格/代码块/多级标题不渲染
- [x] 流式 holdback 保留（逐行未动，critic diff 钉死）
- [x] `BubbleTextStrip` 单测：剥离断言改 spanned 断言（18→26，变异双红）
- 验收：✅ 全过（2026-08-03）——字号13/高度100/宽度260（dumpsys 显示层 1040px=260dp×4）；超长自动滚到底（末尾行可见）；粗体/斜体/代码三 span 同屏渲染；表格管道符剥除不渲染；单测绿（critic 不逊）

### S7-d 全屏语音模式（D，最重；依赖 S4/S5 已就绪 + S7-b）

- [x] 新 Composable `FullscreenVoiceScreen`：全屏深色背景(#0D0D0D) + 居中 PetView min(440dp,屏宽) 等比（critic 打回 P1：480dp>360dp 屏宽必裁切→钳制返工过；360dp 屏「~3×」物理不可达 KDoc 留痕） + 相位驱动四态 + 四态 caption + ~~PhaseHalo 状态光环~~（2026-08-07 用户拍板删除：状态反馈由 caption+迷你声波+宠物四态承担）
- [x] `PetStatusBar` 右上角 mic 入口按钮（样式对齐 hamburger）
- [x] 进入：`PetOverlayService.setWindowsHidden`（GONE/VISIBLE 不动 pref/服务/round）+ 共享 KWS + `ReplyCarrier` 纯逻辑分发 Sink（VoiceStateRouter 不动，spec 偏差记 notes）
- [x] 回复路由：全屏=TTS+小朵 talking 无文字（默认）；FullscreenBubbleSink 恒 no-op
- [x] 退出：~~长按小朵 → 菜单（退出全屏/打开设置/隐藏悬浮窗）~~ + 右上角 X 关闭钮（2026-08-07 删菜单，仅保留 X；悬浮窗长按菜单不动）
- [x] 设置项「全屏转写」开关（默认关）+ 全屏渲染 chatVm.messages 最后 assistant（baseline 防旧回复）
- 验收：✅ 全过——功能（2026-08-03）：右上角进全屏；悬浮窗隐藏；唤醒触发对话两轮（含用户真声 reply 650 chars）；回复=talking+TTS 无文字；长按菜单退出；退出后悬浮窗恢复；小朵零裁切保清晰；四态 caption+光环可辨。**盲测组 1（vs 千问）四轮 14:20/19:23/19:20/21:19 胜（2026-08-04，用户放行 R4+白底 #FAFAFA 改判：caption 主语化+聆听 mic 图标+红挂断钮；状态可辨 4:3/精致度 5:4/三项平=全项不逊）**；单测 +5（代码 critic 不逊+变异双红）

### S7-e 语音行为（E；#9 依赖 S7-d）

- [x] #9 预录 `wozai.wav`（assets，平台 TTS 合成「我在」~~48044B~~ **25920B** PCM/mono/24k/16bit，音色与在线 TTS 一致；2026-08-07 去「哎」后 b-trim 路线当选）
- [x] `VoiceSessionManager` 唤醒流程插确认步骤：唤醒→播「我在」(SPEAKING 丢麦块)→完→LISTENING；仅 KWS 唤醒路径播（按住说话不播，记 notes）；`WakeAck` 接口注入（缺失降级不致命）
- [x] #7 阶段1 键盘打断：`ChatScreen` 输入框 onValueChange 首字符 → `cancelCurrentTurn`（文字轮 turnEpoch+chatJob+markCancelledDone 部分标 DONE 不重发；语音轮 sealed 封轮+停 TTS+停气泡；VM 门控=voiceActive||vsm.state!=IDLE，编排层拍板）
- [ ] #7 阶段2 唤醒词打断：AEC spike **已挂起**（用户 2026-08-04 拍板：误触发 2/3 轮 0 命中、累计 ~5.8min 已达 ≥5min 口径；检出率 10 次需用户配合喊唤醒词未测）→ 记 BLOCKED
- 验收：✅ 唤醒后播「我在」再 LISTENING（日志 SPEAKING(wake-ack wozai)→LISTENING(wozai done)，用户真声轮实证）；✅ 键盘首字符打断（文字轮：barge-in: cancel text turn+部分 DONE 无重发+729 字迟到全文未覆盖；语音轮：TTS 播 24s 中 barge-in: cancel voice turn(voiceActive=true, vsmState=SPEAKING)→4ms 内 IDLE 停播）；🔶 AEC spike（误触发首轮 0/0，待续）；单测 +15（314 绿，critic 三轮后不逊+变异锚 6 处红绿齐+WakeAckTest 5 连跑证稳）

### S7-f 输入框重构+上传（H，含 #2 修复；依赖 S7-a + S7-e）

- [x] `InputBar` 重构两层：上层 InputArea（语音=「按住说话」满宽触摸区 / 文字=TextField）+ 下层 FunctionBar（上传文件键 + 多功能键）
- [x] 按住反馈（#2 修复）：实时声波纹 Canvas（`AudioCapture` RMS 驱动）+「松手发送 上移取消」文字 + 上滑超阈值(~80dp)取消 + `Vibrator` haptic
- [x] 多功能键状态机（**2026-08-04 用户增补停止档**，对齐 DeepSeek 生成中停止范式）：输出中(SENDING/STREAMING/voiceActive)=**停止键（方块）→ `vm.cancelCurrentTurn()`**（与键盘首字符打断同源，S7-e 能力现成）＞ 空闲+有内容=发送键 ＞ 空闲+无内容+文字模式=语音切换键 ＞ 空闲+语音模式=键盘切换键 ＞ 上传中=禁用；停止后键回退原状态
- [x] 上传文件：`POST /ai-server/common/file/upload`（字段 `file` multipart，类型不限）；`FileMeta` 解析；`filesList` 管理（uploading/done/error）
- [x] **待查→接入**：发真带文件消息抓包确认 fileId 接入对话方式（请求体 `fileIds`/`attachments`?）→ 接入发送
- [x] 单测：多功能键状态机 + `FileMeta` 解析 + 上传响应
- 验收：✅ **代码侧收口**（2026-08-04）——InputBar 迁出 ui/screens/InputBar.kt 两层；声波纹 24 柱仅 holding 期订阅 captureBlocks+EnergyGate.rmsOf；awaitEachGesture 上滑 80dp 取消（滑回可恢复）+Vibrator 30ms；FunctionKeyLogic 五态纯逻辑（busy||voiceActive→STOP 最高优先）；AguiClient.uploadFile（fileClass=agent----{agentId}----{threadId}，401 透明重登）；FileMeta.buildContent 前缀注入（`@file:{fileId}?{name}` `|`+`\n`）；FileListRules 同源守卫（send/resend 共享 uploadInFlight 判定点）+clearAttached 只清本轮快照（critic R1 两 P2 打回修复）；345 测绿 skipped=0（314→345，+31）。critic R2 **不逊**（契约逐字零漂移/变异 4 处红绿齐/泄密 0 命中/异步守卫四问全过）。P3 留档：clearAttached 读-改-写毫秒级竞态窗（与 R1 resetFiles 同暴露，非回归）。
- 验收：✅ **真机验收**（2026-08-04 晚，de61cd8，截图 .scratch/s7f/）——真机期揪出 **P1 松手不物化**（consume 先于 changedToUp → 恒 false，字节码双 agent 亲验；修复=consume 前快照 isUp，critic 不逊）+ **P3 idle 永久粉底**（cancelled remember 未 gate holding；修复=`holding && cancelled`，critic 不逊），双修复真机复验全过。逐项：**松手发送全链** ✅（`manual release`/`VAD utterance maxLevel=0.4146`→ASR 6 字→LLM firstToken→SPEAKING，用户气泡上屏）；**声波纹起伏** ✅（w7 成簇起落 vs w8 贴底帧对比）；**上滑 80dp 取消无消息发出** ✅×2（`cancel listening` 之间零 ASR/LLM）；**上传真实文件 fileId 进列表** ✅（73B/15MB/19.5MB 三连 fileId 日志原文+chips 渲染+发送后清空+模型逐字引用附件原文=@file: 前缀语义铁证）；**停止键** ✅（此前 barge-in 日志+键回退）；**上传中禁发** ⚠️ 降级记档（内网 1.65s 瞬态四抓未中，逻辑层 9 测+变异钉死）；**震动** ✅ 用户手感确认有（2026-08-05）；**盲测组 2** 三连败关账（2026-08-05 凌晨，详见 BLOCKED.md；后注：S8-1 拍板回滚两层+横线，单容器方向撤回，功能修复保留）。观察项已落定：TTS 中按下说话只终轮不接续 → **S8-3 票立项改为「打断并接续新一轮」**。

### S7-g 图标（G）

- [x] 替换 `mipmap-*` launcher icon（用户拍板：idle 首帧裁切 adaptive icon，底色 #171717）
- 验收：✅ 全过（2026-08-03）——aapt `icon='res/mipmap-anydpi-v26/ic_launcher.xml'`；真机桌面图标清晰可辨（截图裁图标行取证）；critic 不逊

### S7 依赖序与待查

- **可并行**：S7-a / S7-b / S7-c / S7-g（独立）——**执行修正：a+b 合并一票**（同改 ChatScreen.kt 地界冲突），波次 1 = S7-ab/S7-c/S7-g 三并行 ✅ 已收口
- **依赖**：S7-d ← S7-b（右上角入口）+ 语音链路(S4/S5 已就绪)；S7-f ← S7-a(ChatScreen) + S7-e(barge-in 接输入框)；S7-e#9 ← S7-d（两模式之一）
- **待查项（施工确认）**：① 上传 fileId 接入对话（S7-f）✅ **已关闭**（2026-08-03 平台 web bundle 静态取证：content 文本前缀 `@file:{fileId}?{name}` 多条 `|` 连接+`\n`，记 implementation-notes）② 平台 rename 端点路径 ✅ **已关闭**（桌面仓查实 `/renameThread`，S7-a 已接线真机成功）③ AEC 可靠性（S7-e 阶段2，spike 待做）④ 全屏转写设置（S7-d，按 spec 默认关直接做）

## S8 UI 微调合集（2026-08-05 用户拍板五项 + 当日追加三项）

> 来源：用户日用反馈逐项裁决。无新协议面，纯 UI/交互层。
> 决策留痕：S8-5 撤 reveal 音频同步 = 修订 KD13（reveal 边念边出字 2026-07-30 补选进，2026-08-05 拍板撤）；深色细节项用户撤回不做。

### S8-1 输入框视觉回滚：两层 + 恢复横线
- [x] InputBar 视觉从 R3 单容器改回两层：上层 InputArea / 下层 FunctionBar，层间恢复横线分隔（divider）
- [x] 保留 R3 功能修复不丢（行高 48、FunctionBar padding、单容器期修掉的纵向空洞不得以别形式回来）
- 验收：真机截图两层+横线可见（语音态/文字态各一）；单测绿；critic 视觉对照 S7 两层版截图评「不差于」
- ✅ 真机过（2026-08-05，docs/verification/s8-1-*.png 双态）。**后注（08-06 用户拍板）**：两个独立圆角矩形观感不佳 → 改**单圆角矩形容器+内部横线**（c-inputbar-voice/text.png），R3 功能修复仍全保留

### S8-2 图标白底
- [x] launcher icon 背景改纯白（前景沿用现版小朵形象），mipmap 全密度替换
- 验收：真机桌面截图白底图标；与手机桌面浅色/深色壁纸各截一张核对可读性
- ✅ 深色壁纸过（2026-08-05，s8-2-dark-wallpaper.png，白底圆图标深色壁上清晰可辨）；浅色壁纸项用户拍板跳过（08-06）

### S8-3 TTS 中按住说话 = 打断并接续新一轮
- [x] speaking 态按住说话：立即停 TTS + 停气泡 + 封当前轮（cancelCurrentTurn 复用），同一次按住直接进 listening 采音，松手即送
- [x] 状态机迁移单测：speaking→（按住）→listening 全链事件序
- 验收：真机日志时序取证（TTS stop → listening → ASR → LLM 一条线，无残留轮）；单测 +N
- ✅ 真机过（2026-08-05 19:03:56 日志链）：SPEAKING 中按住 → `barge-in: cancel voice turn`→收泡→IDLE→`LISTENING (VAD start)`→ASR「你还能做什么？」→LLM 一条线；单测 +2

### S8-4 气泡渲染错位/标记残留修复
- [x] 未渲染构造（表格 `|`、代码块 ```、多级标题 #、引用 >）在气泡内裸显 → 最小剥离或安全降级，气泡内不得出现裸 markdown 控制符
- [x] BubbleTextStrip 单测补：表格/代码块/标题/引用输入的输出断言
- 验收：真机长回复气泡截图无裸 `**`、`|`、` ``` `、`#`；单测绿
- ✅ 真机过（2026-08-05，s8-4-bubble-a/b.png）：markdown 表格回复气泡内零裸控制符（管道符/井号/星号全剥离）

### S8-5 气泡文字策略改流式（撤 reveal 音频同步）
- [x] 气泡文字改直接消费 delta 流式上屏（与 App 聊天页同节奏），不再按句级 PCM 时长均分逐字；TTS 播放与文字解耦
- [x] 流式 holdback/清洗保留（半截标记闭合后再产出），TTS 失败降级路径文字仍正常流式
- [x] RevealScheduler 气泡路径退役（类本身保留备查或移除，选型记 notes）
- 验收：真机日志时序——delta→上屏不晚于播音开始（解耦铁证）；TTS 断端口降级轮文字照常流式；单测绿
- ✅ 真机过（2026-08-05 日志）：bubble show 19:03:12.096 早于 SPEAKING(first audio) 19:03:13.950 约 1.85s——文字彻底不等音频。**P2 竞态窗已修**（delta callback 内 bubble 前 double-check sealed，+1 测 `cancelDuringSinkCallbackDoesNotGhostBubble`，TDD 红绿+变异锚实测）

### S8-6 气泡「看不到开头」补救
- [x] 长按菜单加「查看最近一轮」：打开 App 聊天页并滚动定位到最近一轮对话（气泡 NOT_TOUCHABLE 无法回翻，这是唯一回看通道）
- [x] 打开定位用现有会话路由 + scrollTo 消息 id；无最近一轮时菜单项置灰
- 验收：真机长回复播完后走菜单→App 定位到该轮（截图）；单测绿
- ✅ 真机过（2026-08-05）：s8-6-menu2.png（菜单项非置灰=hasShownBubbleContent 置位）+ s8-6-last-round.png（App 定位最近一轮）

### S8-7 唤醒成功即时视觉反馈
- [x] 先查实：KWS 唤醒→listening 时宠物动画现状（是否切 waiting 行），取证贴 PROGRESS
- [x] 反馈弱则补：唤醒瞬间播一遍明确动作（建议 jumping 一次再进 waiting，对齐 CONVERSATION_VISUAL 别名表），全屏/悬浮窗两模式一致
- 验收：真机唤醒连帧截图（idle→动作→waiting）；单测绿
- ✅ 收口（2026-08-05/06）：单测 +3 变异锚全有效（删入口置位/删 finally 复位/删 cancel 复位皆红）；日志状态链 `keyword hit→SPEAKING(wake-ack wozai)→LISTENING` 多轮稳定；用户多轮目视确认。连帧截图三轮连抓均差数秒未中，用户拍板以此组合证据收口

### S8-8 生成中等候态三点动效
- [x] ChatScreen 消息流：发送后到首 token 期间显示三点打字指示（首 token 到达即无缝替换为流式文字）；首 token 实测 ~3.2s，等候不可消除就包装
- [x] 与现有 sending/streaming/done/error 状态点对齐不冲突
- 验收：真机截图等候期三点可见、首 token 后消失；深色模式各一；单测绿
- ✅ 真机过（2026-08-05）：s8-8-typing-dots-a/b.png（等候期三点呼吸两帧相位不同）+ s8-8-streaming-c.png（首 token 后三点消失、流式替换）；深色模式项随 S8 深色细节撤回不做

### S8 依赖序
- 可并行：S8-1 / S8-2 / S8-4（地界：InputBar / mipmap / BubbleTextStrip）
- voice 域串行：S8-3 / S8-5 / S8-7（手势状态机 + 气泡渲染管线 + 唤醒反馈，同链路同一 builder 串行）
- chat 页域串行：S8-6 / S8-8（同碰 ChatScreen/VM）
- 三组之间可互相并行

## S9 唤醒应答音色跟随 TTS 配置（2026-08-17 立项，方案 C 混合，用户拍板）

> 背景：应答音「我在」= 烤死资产 wozai.wav（平台 cosyvoice 声），对话 TTS 已三厂商自由配——默认配置（cloud+aliyun/longanfengyue）下即错配。
> 方案：三家云默认音色资产内置 + 自定义音色运行时合成缓存；四层兜底链（缓存→厂商资产→wozai.wav→直进聆听），任何失败不劣于现状。
> 硬约束：**唤醒播放路径零网络**（网络只在预取 daemon 线程）；`VoiceSessionManager.kt` 零改动（WakeAck 接口/cancel 语义原样）。
> 设计全文：`C:\Users\Administrator\.claude\plans\tts-tts-tts-tts-serialized-book.md`（含逐参数后处理对齐表与测试清单）。

### S9-1 离线脚本 + 三家云资产 + parity 夹具 + 契约测试 ✅（critic 复评通过 5/5/5/5/4）
- [x] `tools/wozai/`：common.py（迁移 remake_wozai.py 原语逐参数原样 + validate + `--emit-parity-fixture`）/ synth_cloud.py（volc/openai=stdlib urllib；aliyun=WS `uv run --with websockets`）/ synth_platform.py（历史存档，wozai.wav 不重制）/ README.md（key 从 secrets/cloud.properties 读且绝不打印）
- [x] `assets/wozai-aliyun.wav` 生成（22124B/0.46s/峰值 26000；aliyun 探针成功）；**volc/openai 暂缺记偏差**（VOLC key 空 / compatible-mode 404，兜底链覆盖，key/端点到位补跑一条命令+契约测试必备集一行）
- [x] `app/src/test/resources/wakeack/parity-*.wav` 对拍夹具（**已冻结**：sha1 input=06249392/expected=5651d36f，不再重新生成——critic N-1 附条件）+ `WakeAckAssetsContractTest` 5 测
- 验收 ✅：全量 423 绿 skipped=0；双变异锚实测（删资产→5 红/零数据→恰 2 红）；critic 逐参数/逐字段镜像亲证零漂移

### S9-2 纯逻辑五件套 + NetConfig 去重 + 单测 ✅（critic 复评不逊成立 5/5/4.5/5/4.5）
- [x] `net/TtsVoiceDefaults.kt`（默认常量+TEXT）+ `voice/WakeAckSelector.kt`（六层兜底链+cacheKey 长度前缀+fileName sanitize+SHA-256 后缀+路由镜像；platform 永不读缓存）
- [x] `voice/WakeAckAudio.kt`（Python 逐参数移植+金向量逐字节对拍测试；validate 统一 toSamples 解码）+ `voice/WakeAckCacheStore.kt`（ATOMIC_MOVE 原子写+坏缓存删+Regex("v\\d+") 版本目录清理白名单）+ `voice/PrefetchBackoff.kt`（10min 窗全时间注入）
- [x] `NetConfig.kt` 仅 6 处默认值→常量（critic 逐串核对等值）；4 测试文件 46 测；返工闭环 P1 金向量对拍+P2×2
- 验收 ✅：全量 423 绿 skipped=0；变异锚 6 处 critic 亲验全红；critic 动态对拍金向量+8 刁钻 case Python↔Kotlin 逐字节全等

### S9-3 WakeAck 动态化 + 预取接线 ✅（critic 不逊成立 4.5/4.5/4/5/4）（依赖 S9-2）
- [x] `WakeAckResolver.kt`（构造注入可测+Decision 键 memo+坏缓存/坏资产逐层回退+source 日志行）/ `WakeAckPrefetcher.kt`（CAS 单飞+finally 清+五失败路径统一 backoff 静默）/ `DynamicWakeAck.kt`（零 HTTP import 结构守卫+两道 cancel 防线+AssetWakeAck 注释逐字迁入）
- [x] VoiceClientFactory：wakeAck() 装配校验语义逐字保留+lazy resolver 单例+session() 块外挂预取；SettingsScreen 保存后 +4 行挂预取
- [x] ResolverTest 4 测；变异锚 3 处 critic 亲验红
- 验收 ✅：全量 427 绿 skipped=0（423+4）；**VoiceSessionManagerWakeAckTest 12 测原样绿+VSM git 零 diff 亲证**；assembleDebug 绿；P2-1（session 每次触发=自愈重试）已记 notes 言明，P3-1/P3-4 记档待 v3 清理

### S9-4 真机语义验收 + 文档收口 ✅（2026-08-18，证据 docs/verification/v15-wakeack-voice-follow.md）（依赖 S9-3）
- [x] 六 Case 全过：A 默认 aliyun 听感✓+`source=asset:wozai-aliyun.wav` 日志链 / B 自定义音色 `prefetch saved`+`source=cache`+缓存拉回复听（19724B/峰值恰 26000）/ C 401 失败→退避→静默+`source=asset:wozai.wav` 回退（模拟器补验「未配 key」变体）/ D platform 回归（SP 回读+零 prefetch 探针双确认+听感✓）/ E barge-in 旁证链+VSM 零 diff 继承覆盖 / F 连唤 5 次即时✓
- [x] critic 三轮全过（S9-1 通过 5/5/5/5/4、S9-2 不逊成立 5/5/4.5/5/4.5、S9-3 不逊成立 4.5/4.5/4/5/4，返工 2 轮闭环：金向量对拍+两 P2+import 卫生）
- [x] 基线 372→**427** skipped=0（编排者亲算 XML）；CONSTRUCTION/PROGRESS/DEV_LOG/implementation-notes/solutions 全更新
- 遗留：P3 source 日志 platform 模式 provider 标签歧义（小修一行）；**观察项：全屏语音模式概率性白屏（S9 范围外，另立票）**；volc/openai 资产待 key/端点

### S9 依赖序
- S9-1 ∥ S9-2（地界零交叠）；S9-3 ← S9-2；S9-4 ← S9-3
- 真机仅 S9-4 用（de61cd8 互斥锁纪律）

## S10 悬浮窗贴边收纳——半身贴边（2026-08-18 立项，用户四拍板）

> 需求原话：「新对话开发悬浮窗贴边隐藏成半圆功能」——拖拽精灵到屏幕左右边缘 → 平滑滑入只露半个身位；
> 单击滑出恢复，拖拽随时可用（国内桌宠/悬浮球通用「贴边收纳」交互）。
> **用户四拍板（2026-08-18）**：①流程=直接定标出票（不走 PRD/Spec 链）；②形态=**半身贴边**
> （窗口滑出至纵向中线≈屏缘、露半个身位；非真·半圆裁剪，零资产零裁剪，最小充分解）；
> ③召回=**单击滑出+可拖出**（贴边态单击语义从「挥手」改为「滑出」；移动超 touchSlop 即算拖拽不打架）；
> ④持久化=**重启保持贴边**（贴边坐标照常落盘）。
> **红线**：`voice/**` 零 diff（S9 刚收口）；`OverlayClamp` 现有 clamp/clampedBubbleLeft 语义不动
> （新逻辑平行新增，不改既有签名）；非贴边态手势语义不变（单击=挥手/长按=菜单/拖拽=移动）。
> **核心设计（第一性原理）**：贴边坐标（窗中心恰在屏缘 x=±petW/2 偏移）是 `OverlayClamp.clamp`
> 的不动点——持久化、STICKY 重启恢复、`onConfigurationChanged` 重钳位三者**零新机制**自然成立；
> 「当前是否贴边」由几何推导（窗中心 ≤0 / ≥屏宽）而非额外状态位，重启恢复后语义自动正确。
> 吸附触发语义：松手（clamp 后）精灵中心距左右屏缘 ≤ 半个身位（zone=petW/2，即精灵仍有部分
> 贴缘/出屏）→ 吸边；完全离开边缘不吸（反例：屏中松手 → 原位不动）。

### S10-1 吸附几何纯逻辑（模式 C，TDD 红-绿先行）✅ 代码在树（EdgeDockTest 11 测，438 基线内全绿；critic 补审欠账 08-21 后）
- [x] `pet/EdgeDock.kt`（零 Android import，KDoc 风格对齐 OverlayClamp）：
  - `Side { LEFT, RIGHT }`
  - `dockSide(centerX, screenW, zonePx)`：中心距左/右缘 ≤ zonePx → 对应 Side；两侧同时命中（屏不宽于窗）→ null 兜底
  - `dockedX(side, winW, screenW)`：LEFT→`-winW/2` / RIGHT→`screenW - winW/2`（窗中心恰在屏缘，与 clamp 同套整除运算）
  - `slideOutX(side, winW, screenW)`：LEFT→`0` / RIGHT→`screenW - winW`（完全可见贴缘）
  - `dockedSideOf(x, winW, screenW)`：几何推导——中心 ≤0→LEFT / ≥screenW→RIGHT / 其余 null
- [x] `EdgeDockTest.kt`：左/右缘吸附命中；**反例样本：中心=screenW/2 → null**；zone 边界恰含（中心=zonePx → LEFT）；
  宽于屏兜底 null；dockedX 窗中心恰在屏缘（x+winW/2==0 / ==screenW）；slideOutX 完全在屏内；
  **clamp 不动点性质测试：`OverlayClamp.clamp(dockedX,…) == dockedX`（两侧）**；
  dockedSideOf：半身=对应边、全可见贴缘(x=0)=null、屏中=null
- 验收：`./gradlew.bat testDebugUnitTest --rerun-tasks` 全绿 skipped=0（427 只增不减）；
  变异锚 ≥2 亲验（删「宽于屏 null」守卫→红；zone 恒 0（不吸任何人）→左/右命中测试红）

### S10-2 服务接线：吸附滑入 / 单击滑出 / 动画 / 菜单钳位（依赖 S10-1）✅ 代码在树（S10-3 物理验收全绿；critic 补审欠账 08-21 后）
- [x] `PetOverlayService` 拖拽 end：现行 clamp 后加 `dockSide`（zone=petW/2）判定——命中则**动画滑入**
  `dockedX` 并落盘目标坐标；未命中维持现行 moveTo+落盘（屏中松手零变化，反例即验收）
- [x] 单击分支（ACTION_UP 非 dragging 非 longPress）：`dockedSideOf` 命中 → **动画滑出** `slideOutX`+落盘；
  未命中维持挥手（`playOnceThenIdle(WAVING)`）
- [x] 动画 `ValueAnimator.ofInt` ~200ms 逐帧 `moveTo`（气泡窗随 moveTo 联动）。**守卫四问先答再写**：
  新 ACTION_DOWN 取消 / onConfigurationChanged 取消 / onDestroy 取消 / 动画中再触发=取消重起；
  落盘时机=动画发起时写目标坐标（进程被杀也恢复到确定态）
- [x] `OverlayMenu`：锚点半出屏时 popup 横向钳位屏内（左贴→`xoff=max(0,-anchorScreenX)` 右移；
  右贴→右缘对齐左移），与现有纵向翻转逻辑同范式
- 验收：assembleDebug 绿 + testDebugUnitTest 全绿 skipped=0；critic 代码正确型（diff+守卫四问+JVM 可测点变异亲验）；
  日志新增 dock/undock 事件行（真机/模拟器取证用）
- 白名单地界：`PetOverlayService.kt`、`ui/overlay/OverlayMenu.kt`、`pet/EdgeDock.kt`（如需微调仅限 S10-1 已定 API 语义内）、
  对应测试文件；**禁碰** voice/**、OverlayClamp.kt、ChatViewModel/ChatScreen

### S10-3 语义验收：模拟器为主 + 真机一轮（依赖 S10-2）✅ 收口 2026-08-19
- [x] 模拟器 `emulator-5554`（免锁，动前读 `EMULATOR.md`）：拖至左/右缘松手→滑入半身
  （screencap 像素断言：半身在屏外+dumpsys 窗口 x 与 dockedX 对账）；屏中松手不吸（反例取证）；
  半身态单击→滑出完全可见；半身态长按→菜单完整在屏内；半身态气泡钳屏内可读；
  force-stop/重启→半身恢复；`wm size` 收窄→仍半身不出屏（历史 P2 路径回归）
- [x] 真机 de61cd8（**拿 `.scratch/device.lock`**，≤5min）：关键断言一遍（左吸/右吸/单击滑出/重启恢复）
  + 半身态「小朵小朵」唤醒一轮对话（旁证语音零影响）
- 验收：✅ 证据入 `docs/verification/v16-edge-dock/`（原目录随 08-18 事故灭失，本目录为 08-19 重建续写，
  含 A/B/C/A2 补拍）；`git diff --stat voice/` 为空（零 diff 亲证）
  - 模拟器（1080×2400，winW=252）：A 左吸 x=-126 / A2 右吸 x=954 / C·C2 单击滑出 0·828 /
    B 反例屏中松手零 slide 日志 / D 菜单全项左缘=0 钳回+「打开 App」可点 / E 双贴气泡本体完整可读 /
    F force-stop 自愈恢复 x=954 / G wm size 720 收窄重钳 x=594=dockedX(RIGHT,252,720) 不消失
  - 真机（1440×3168，winW=937 scale≈1.22）：RM-1 滑出 972→503 / RM-2 右吸 972 / RM-3 左吸 -468 /
    RM-4 滑出 0 / RM-5 重启恢复 -468 / RM-6 贴边态语音一轮全链（wake→ASR「今天的天气怎么样？」→LLM
    firstToken 456ms→TTS SPEAKING→气泡，全程 x=-468 不变）
  - ⚠️ 验收中揭露并已修复恢复遗留缺口：`secrets/cloud.properties`（gitignored）未随恢复树回迁 →
    新构建三密钥全空云链断；从 device-base.apk 反编译 BuildConfig 取证恢复 → 重建重装 → 真机实证恢复
    （详见 PROGRESS.md S10 节与 real-rm6 证据文件）
  - ⚠️ 欠账：S10-1/S10-2 critic 独立评审（子代理配额墙至 08-21，恢复后补派）

### S10 依赖序
- S10-1 → S10-2 → S10-3 严格串行；S10-1 可立即派
- 真机仅 S10-3 用（互斥锁纪律）；模拟器操作全部显式 `-s emulator-5554`

## S11 唤醒词准确率排查 + 唤醒词打断核查（2026-08-19 立项，用户两拍板：最小修复+取证 / 先重做有效 spike 再定）

> 来源：用户报障「唤醒词准确率不高 + 检查是否支持唤醒词打断」。
> 证据总链：`docs/verification/v17-kws-accuracy/README.md`。结论：三层独立根因叠加，全部修复并上锚。

### S11-1 阈值恢复+锚点 ✅（critic 不逊；文档 P2 编排层已修）
- [x] `KwsEngine.kt:101` 阈值回 0.3（08-10 真机验证值），经新 `KwsDefaults.KEYWORDS_THRESHOLD` 承载 + 精确锚点测试（改 0.5→红亲验）
- [x] KwsEngine KDoc + EMULATOR.md §6 tag 漂移（KwsCore→KwsEngine）更正；CONTEXT.md/spec:71/对账表:45 三处 0.5 假事实修正（编排层，critic P2）
- 验收：439 绿（438+1）；变异锚红绿往返成立

### S11-3 AEC spike 重做（真放行）✅（critic 不逊 + 打回 1 轮：UI 脱钩修复；3 P3 顺手修落）
- [x] `VoiceStateRouter.spikeFeedKws`（@Volatile，会话级）：SPEAKING+spike→KWS，PROCESSING 恒 DROP；VSM 委托属性镜像 kwsPaused 范式
- [x] `AecSpikeSection` 废弃 200ms 捅 kwsPaused 的 no-op 旁路（08-04 空证据根因），开关直写标志；remember 初值读真态（导航离开≠disarm）
- [x] KDoc 记录空证据教训 + 有效性闸（观测期 fed 须在 SPEAKING 期间增长，否则 spike 作废）
- 验收：441 绿（+2）；变异锚（SPEAKING 回退恒 DROP→红）亲验；**真机误触发/检出率观测待做**（双合格才立项实现）

### S11-5 文字轮云端分支恢复 ✅（critic 不逊；3 P3 增强修落）
- [x] jadx 反编译 device-base（S9）+ emulator-base（S10）双源取证（产物入库 docs/verification/forensic-s11-cloud-branch/），按寄存器级伪码复原 08-14 分支
- [x] 下沉纯逻辑 `cloud/TextChatRouter.kt`（路由+历史构建+Result 适配；历史=dropLast(1)+滤 SYSTEM/空文本+role.wire；systemPrompt=null；fileRefs 仅平台路）；ChatViewModel 手术接回（onDelta 两路共用，epoch/气泡/persist/清场下游零分叉）
- [x] 锚：TextChatRouterTest 3 测（双 MockWebServer：云端路平台 0 请求/平台路云端 0 请求/500 透传 rate limited）；变异锚（恒走平台=事故丢失态）双红亲验
- 验收：444 绿（+3）

### S11-6 音频延迟定位探针 ✅（critic 不逊；编排层 KDoc 修正 2 处）
- [x] `AudioProbe`（纯逻辑总开关+shouldLog/lagMs）+ AudioCapture emit 侧 `cap rms=… lag=Nms`（AudioRecord getTimestamp TIMEBASE_MONOTONIC）+ KwsCore feed 侧 `feed rms=…` + AecSpikeSection ③ 开关
- [x] AudioProbeTest 5 测；变异锚（删 armed 判定→红）亲验；未 arm 零开销（每块一次 volatile 读）
- 验收：449 绿（+5）；**探针结论：管道 lag=1-4ms 实时无积压——音频延迟论被证伪，真凶转向门控语义**

### S11-7 KWS 门控时间轴修复 ✅（critic 不逊；2 P3 文档漂移编排层已修；真机验收 10/10）
- [x] 挂起窗：过门后 `KwsDefaults.GATE_HANGOVER_BLOCKS`=8 块（800ms，VAD 判停同源）内低于门仍喂真实块（只消耗不续窗）
- [x] gap-reset：连续低于门超窗→不喂+Work.GapReset sentinel 入队保序，reset 仅 decode 消费线程执行、幂等沿（过门才重新武装，新生流不 startup reset）
- [x] KwsCoreHangoverTest 4 测+锚 1 测；双机制独立变异锚（删挂起窗 4 红/删 gap-reset 3 红）亲验
- 验收：454 绿（+5）；**真机一声唤醒 10/10**（修复前第一声 0/10），每轮命中后 gap reset 准点冲刷
- 留档（critic 观察项）：teardown→ensure 极速交替的理论竞态（后果上限=多/少一次幂等 reset，pre-existing）；6.4s 积压丢 sentinel 不重试（退回旧行为，无新故障）

### S11 依赖序
- S11-1 → S11-3 →（S11-5 取证复原）→ S11-6 → S11-7 串行（gradle 单项目构建锁，地界虽错开仍串行）
- 真机窗口两段：08-19 晚（装机验阈值+10 轮快验）+ 08-20 下午（探针 3 声+S11-7 验收 10 轮）

## S22 播放期影子低阈值 KWS 通道（2026-09-02 立项，用户拍板 A+C 并行）

> 背景：板子播放期「小朵小朵」打断成功率受限——无 AEC 回声掩蔽 + AGC 增益压缩。
> A 方案：第二 spotter 低阈值影子通道（命中只记日志不打断），一晚数据定转正阈值。

- [x] S22-1：KwsDefaults.SHADOW_KEYWORDS_THRESHOLD=0.15（精确锚测试锁定口径）+
  KwsEngine 阈值参数化（主引擎默认仍 0.3）
- [x] S22-2：VoiceForegroundService 双引擎装配——影子引擎仅 SPEAKING/PROCESSING
  期喂流，命中仅落日志（tag=KwsShadow + S22 shadow hit 行，零行为变更）；
  影子加载失败不拖死主引擎；destroy 连带 release。488->489 测绿，板子已装
- [ ] S22-3 板子数据回收：影子命中 vs 主引擎命中对照（真打断漏检补给率 +
  回声自误触率）→ 定转正阈值或全量走 S23

## S23 NLMS 参考回声消除（P4 挂账提前，2026-09-02 立项，根治回声掩蔽）

> TTS PCM 自产=完美参考信号；NLMS 自适应滤波从麦块减回声再喂 KWS（主+影子双受益）。
> 纯 Kotlin 可 JVM 单测；延迟对齐靠滤波窗覆盖（参考领先回声，因果滤波天然吸收）。

- [x] S23-1（TDD）：NlmsEchoCanceller 纯 Kotlin（8192 环形参考缓冲+逐样本 NLMS，
  filterLen 2048=128ms 覆盖板子音频管道延迟 30~120ms 经验值，μ=0.1）；
  单测×4：合成回声 ERLE≥10dB / 目标保真（corr>0.95 能量比[0.8,1.2]）/
  零参考逐样本直通 / 字节路径；参考领先回声因果滤波天然吸收延迟无需标定
- [x] S23-2：VoicePlayer 参考分接头（writeAll 每写回调 referenceListener，
  24k→16k 3:2 重采样入环）+ VSM routeBlock KWS 分支先过消回声（无参考/开关关
  =原数组直通零开销）+ Prefs/工厂/设置页「播放期回声消除（实验）」开关（默认开，
  A/B 对照用）；489->493 测绿，板子已装。wozai 短应答（0.6s）独立 player 未接
  参考（记 notes 挂账）
- [x] S23-2b 根因修复（首晚遥测实证）：参考「写入节奏≠播放节奏」全错位
  （refBacklog 375s）——VoicePlayer 配速器按播放头 10ms 放出参考 + 消回声器
  溢出重同步（目标=最小积压，窗能量跳转重算）；单测+1（溢出重同步后 ERLE≥10dB）
- [ ] S23-3 板子 A/B：消回声开/关各 N 次播放期打断，成功率对照（联动 S22 数据）；
  明证指标=遥测 refBacklog 0~2000 样本级 + winPow 播放期稳定非零

## S21 工具调用后需重新唤醒（2026-09-02 立项收口，用户提案+拍板独立开关）

> 背景：板子多轮续听窗把密集架自带播报（取档/归还）当用户语音收下 → 幽灵对话
> （甚至再触发 MCP 调用）。方案对比：A 工具轮后回 IDLE（采纳）/ B 静默窗（猜时长）
> / C 声学区分（无 AEC+AGC 钉死）/ D 话术黑名单（脆弱）——排除理由记 notes。

- [x] S21-1（TDD）：VSM rewakeAfterToolCallProvider（默认 true）+ 多轮续听决策点门控
  （turnSawToolCall&&开关开 -> backToIdle「KWS 守门」，否则原路径）；e2e×2（开=终态
  IDLE / 关=多轮 LISTENING 重启）；会话线程服务端保留，重唤醒不丢上下文
- [x] S21-2：Prefs.rewakeAfterToolCall + 工厂装配 + 设置页开关（MCP 播报行下）；
  486->488 测绿；既有 mute 双测显式 rewakeAfterTool=false 隔离开关
- [ ] S21-3 真机/板子现场：开架指令后机器播报不再触发幽灵轮；喊「小朵小朵」追问正常

## S20 MCP 调用结果静音开关（2026-08-31 立项，用户拍板）

> 需求（用户）：密集架自带语音播报，手机不需要再念 MCP 调用结果。
> 机制（探针实测事件序）：TOOL_CALL_START/END/RESULT 先于最终文本到达 ->
> SSE 消费者置标记，分句喂 TTS 时「开关关 + 本轮有工具调用」-> 跳过合成播放，
> 文字照上屏/上泡，多轮重启不依赖 SPEAKING 迁移（结构安全）。

- [x] S20-1（TDD）：AguiSseConsumer onToolCallEvent（TOOL_CALL_START）+ AguiClient.chat
  ->runOnce 透传（可选参默认 no-op）；云端 OpenAI 路径无 MCP 恒 false
- [x] S20-2（TDD）：VSM speakMcpResultProvider（默认 true）+ turnSawToolCall 原子标
  （voiceChat 入口归零，TOOL_CALL_START 置位）+ TTS 生产者抑制（跳过合成仅落文字）；
  Prefs.speakMcpResult + 工厂装配 + 设置页开关（端点与账号区，平台智能体行下）
- [x] S20-3 单测：482->486 绿（SseConsumer 回调×1 + e2e×2：开关关=零 TTS 请求+
  文字上屏+多轮重启聆听 / 开关开=TTS 正常合成）；排障留痕=runOnce 调用点漏传参
  （默认参数静默吞回调，二分测试定位）
- [ ] S20-4 真机/板子现场：mjjAgent 开架指令，开关关=手机静默只听密集架自播

## S19 自适应 VAD 阈值 + 空 ASR 续听（P1+P2，2026-08-31 立项，第一性原理路线）

> 本质：非平稳噪声下的端点检测--固定阈值在 AGC 动态搬移噪声底的板子上数学不可收敛。
> 数据锚点（v22 实测）：噪声被 AGC 泵到 max 0.34 缓降、语音削波 1.0、会话语音占空比 <50%。

- [x] S19-1【P1 自适应噪声底】（TDD，v5 EMA 双轨落地）：VadDetector 内 noiseFloor
  EMA--低于阈值块慢跟踪（0.1）；持续 ≥1s 高电平 且（远低于语音峰值 或 电平平台稳定
  (max-min)/mean<0.25）判「抬升噪声」快速上攻（0.35）。判起阈值=max(provider,
  floor×2.0)、判停=max(provider, floor×1.4)；start() 不清 EMA（设备属性跨轮保持，
  泵态多轮重启免无限幽灵轮）。手机安静域 floor≈0.001 -> 阈值=provider 零回归。
  （设计迭代留痕 notes：p20/p50 窗口法两轮死局--自引用偏置/语音污染窗口，
  第三性证据=AGC 平台特征给了第二区分通道）
- [x] S19-2【P2 空 ASR 续听】（TDD）：ASR 空文本（四家统一「ASR 返回空文本」前缀）
  -> 重进 LISTENING 而非落 IDLE；上限 2 次（真文本/新对话链复位）；判定只看纯空，
  非空短文本按现流程（"嗯""好"合法短回复不误伤）
- [x] S19-3 单测：475->482 绿（P1×4：泵态判停/衰减曲线/安静域零回归/幽灵轮两轮免疫；
  P2×3：单次续听/三次超限落 IDLE/真文本复位）；既有 VAD 16 测全绿
  （max-utterance 测试数据改波动幅度--恒定幅度在平台通道下本属「非语音」特征）
- [ ] S19-4 板子现场（用户今晚）：泵态下 utterance 时长 <2s + 幽灵轮不再断链
  （vad diag level 轨迹 + utterance 采样数核对）
- [ ] 挂账：P3 时域形态判别（P1 不够再加）/ P4 TTS 参考 NLMS 回声消除 /
  P5 云端 ASR 端点化（架构级备选）/ 部署层 tinymix 关 codec AGC

## S18 VAD 阈值校准联动 + 板子声学诊断（2026-08-31 S18-1/2 收口，板子终验待回收）

> 起因：用户报板子（rk3568）「唤醒词打断不灵敏」。三层排查实锤：打断检测正常，
> 病灶=板子无 AEC + 底层 AGC 慢释放致聆听期噪声底顶住 VAD 判停（每轮拖满 8.3s）。
> 诊断链与证据见 v22。

- [x] S18-1（TDD）：VAD 说话阈值校准联动--VadDetector.provider 注入 +
  suggestVadSpeechLevel=clamp(p95×1.6, 0.05, 0.15)（手机零变化）+ 校准 UI 双值写入
- [x] S18-2：采麦源三档模式（通话/识别/原始 UNPROCESSED，重启生效）+
  聆听期电平轨迹诊断日志（vad diag level，每秒一行）+ 旧键迁移
- [x] 测试：472->475 绿；板子校准写入/即时生效实证（阈值联动必要非充分：AGC 慢释态
  噪声底高于安静态校准值）
- [ ] S18-3 板子终验（用户）：UNPROCESSED 源受控测试；无效则上自适应阈值终方案
  （聆听期 EMA 跟踪噪声底，电平轨迹日志已备好数据）

## S17 全屏字幕三合一适配（2026-08-29 收口，模拟器+板子验收）

> 需求（用户 08-29）：全屏字幕在不同屏幕适配不一致（板子 1080dp 宽屏行长 60+ 字不可读、
> 宠物占比漂移、横屏宠物顶格压字幕）。原型四方案对比后拍板「跨区字幕+比例系统化+列宽钳制」。

- [x] S17-1（TDD）：纯 Kotlin `FullscreenVoiceLayout` 计算器（宽高 dp -> 9 项锚点），
  99 组设备矩阵不变量测试 + 4 设备黄金值；含「上让位」横屏防护栏
- [x] S17-2：FullscreenVoiceScreen 消费计算器（旧布局常量 4 个删除归一）；
  字幕/状态共享列宽钳制；字号 fontScale 缩放
- [x] S17-3 验证：472 测绿；模拟器横屏无重叠（S14 遗留破损销案）+ 竖屏零回归 +
  旋转零 onCreate；rk3568 板子 caption 落位与公式一致。证据 v21
- [ ] 真机现场：OPPO 手机感知应与 S14 一致（零回归设计），留日常回收

## S16 平台智能体选择（2026-08-25 立项，参考桌面端 PLATFORM_AGENT_ID 机制）

> 需求（用户 08-25）：移动端可选择 agent（不同 agent 配不同提示词/MCP）。
> 拍板：会话记忆保留，切换后能正常查看对话（桌面端语义：agentId 是请求头选择器，
> thread 连续性不动，本地历史不动）；选择器升级为列表单选（平台有现成 /agent/list）。
> 实测：POST https://{host}/ai-server/agent/list 空 body+Bearer，信封 code=200 data.list[]
> 返回 9 agent（yearChatAgent 年报助手 / hkChatAgent 环控 / ragChatAgent 知识库 /
> mjjAgent / langflow / accessAgent 小朵 / archiveAgent / constructionAgent / chatAgent 智能问答）。
> 注：默认 agent 名「智能问答」（agentId 仍 chatAgent，桌面端同默认）。

- [x] S16-1 协议层（TDD）：AguiClient.listAgents()（复用登录/信封/透明重登，401 单次重试）
  + agentId 构造参数改动态 provider（chat/delete/rename/upload 头与 createThreadId body 全部
  改读 provider）+ NetEndpoints.agentListUrl（443 反代范式）
- [x] S16-2 存储与装配：Prefs.agentId（默认 chatAgent）+ agentDisplayName（离线显名）+
  AguiClientFactory 装 provider 与 listUrl
- [x] S16-3 设置页 UI：「端点与账号」区加「平台智能体」行（显当前名）-> 点击弹层单选列表
  （拉取中/失败重试/名称+描述+勾选；标准控件不另做 HTML 原型）
- [x] S16-4 验证：单测全绿 467（461+6）+ 模拟器实测列表拉取（9 agent）/选择保存
  （agent selected -> hkChatAgent）/强杀重启保持（当前：环控）/换 agent 后对话正常
  （thread 复用 + RUN_FINISHED 337 chars，回复知识域吻合）；证据 v20
- [ ] 真机现场：切 agent 后语音对话回复风格变化（提示词生效主观感受，用户日常回收）

## S15 聆听期反馈泡 +「我在」驻留延长（2026-08-25 收口，真机验收过）

> 起因（改进盘点 1+2）：S13 静止化后聆听期悬浮窗零反馈（30s 聆听用户无感知）；
> 「我在」气泡播完（~1s）即收一闪而过。

- [x] 聆听提示泡：进 LISTENING 显「在听…」（低存在感）；离聆听即收（思考期无泡，
  回复/错误泡另行顶替）；多轮回听（from==SPEAKING）不显（S5 回复泡跨轮驻留语义不动）
- [x] 「我在」驻留：wozai 播完不再即收——驻留 2.5s（listeningHintLingerMs 可注入）后
  swap job 内容顶替为「在听…」（无 hide 间隙）；守卫=世代+仍在聆听，被打断轮不补枪
- [x] 测试：WakeAckTest +1 新增 2 改写（驻留时序/降级同走/按住显收/cancel 不补枪）；
  WakeInterruptTest 快照点移至驻留 swap 落地后（linger 注入 150ms+sleep 300）
- [x] 验收：460→461 绿 skipped=0 + assembleDebug 绿；模拟器按住说话实证
  「LISTENING→bubble show 3 chars→release→hide→IDLE」日志链+提示泡截图（v19 目录）
- [x] 真机现场（2026-08-25 验收过）：唤醒路径「我在」驻留 2.5s→「在听…」内容顶替连贯

## S14 全屏语音页交互改版 +「我在」可视化（2026-08-24 收口，真机验收全过）

> 起因（用户报）：应声词「我在」无气泡无动作；全屏页 caption/文字区位置差、三个点不明显。
> 调研：豆包/通义真机实拍（.scratch/research-voice-ui/）= 三段式「顶部功能条→中央主视觉→
> 底部状态区+常驻控制」。原型：designs/xiaoduo-motion-preview/小朵全屏语音页改版预览.html（v6 过目）。
> 拍板：「我在」纯现场反馈不进消息流；只改全屏页文字区；打断+退出双键抽屉式（默认只露把手）；
> 双退出入口保留（左上 ✕ + 抽屉红叉）；抽屉键无文字说明、灰底统一、图标加大（豆包风）。

### S14-1 「我在」应声可视化 ✅
- [x] VSM `playAckThenListen`：status("我在") 后 `bubble.show("我在")` + `bubbleHidePolicy.onBubbleShown()`；
  wozai 正常播完（非 cancel/非世代过期）进聆听前 `bubble.hide()`——cancel 路径 cancelCurrentTurn 已收泡
- [x] FullscreenVoiceScreen caption：SPEAKING && wakeAckActive 分支 →「我在」（19sp 青色 #1298B5 强调样式）
- [x] 测试：WakeAckTest +3（show/hide 时序/失败降级收泡/cancel 不双重收）；
  WakeInterruptTest 快照点后移适配（wozai 正常收 hide 属预期，不变量仍锁残跑零追加）

### S14-2 全屏页三段式重排 ✅
- [x] 布局：顶部栏（左 ✕ 保留 / 右「字幕」开关进场，写回 Prefs.fullscreenTranscript 当场生效）→
  中央宠物（中心上移至屏高 42%）→ 状态区下移下 1/4（脱离宠物正下方，底距 150dp）
- [x] 状态区：caption 15sp→17sp；聆听指示器 16dp 三柱 → 7 柱 52×28dp 声波（RMS/EMA 数据源不变）；
  思考态新增三点错相跳动；文案改「在听，请说…/小朵思考中…/小朵回答中…」
- [x] 转写区随迁：状态区上方渲染，transcriptBaseline 语义不变

### S14-3 底部抽屉（打断/退出双键）✅
- [x] Compose 抽屉：顶圆角面板 + 顶部 30dp 把手区（44×5dp 横线，把手随面板一体移动），
  默认 translateY 只露把手；点把手/上滑（>48dp）展开、下滑/再点收起（250ms）
- [x] 双键无文字：60dp 灰底圆（#EDF0F2）——打断=深色停止块 24dp（state!=IDLE 可点 →
  cancelCurrentTurn，与 OverlayMenu 打断同入口）；退出=红叉 28dp（→ onExit）

### S14-4 验收存证 ✅
- [x] 单测 457→460 全绿 skipped=0（+3 新增零删除）+ assembleDebug 绿
- [x] 模拟器截图：全屏待机三段式（左 ✕/字幕/42% 宠物/下 1/4 caption）+ 抽屉开合（IDLE 打断置灰）
- [x] 真机语音验收（2026-08-24 用户现场全过）：唤醒→悬浮窗「我在」气泡+全屏「我在」大字 /
  聆听声波跟手（卡顿已修）/ 思考三点 / 说话期打断键可用 / 字幕富文本+滑动查看
- [x] 真机首轮返修二项闭环：字幕与宠物重叠（宠物宽改 0.62×屏宽）+ 字幕只露开头（后升级富文本滚动）
- [x] 概率性白屏销案（08-18 观察票）：configChanges 13 项 + singleTask，真机旋转/键盘/通知回拉现场过

## S13 形象动作改造（2026-08-24 收口，用户逐项拍板）

> 流程特例：HTML 原型先行（designs/xiaoduo-motion-preview/ v1 被否 → v2 拍板）后实现。
> 全证据：docs/verification/v18-s13-pet-motion/README.md。

- [x] 帧表重映射：idle/waiting/review/failed = row0 frames=1 静止；talking = row0 6帧 @120ms
- [x] 唤醒跳取消：VSM + FullscreenVoiceScreen 改直接 WAITING（wakeAckActive 门控保留）
- [x] 点击挥手取消：PetOverlayService 单击 + ChatScreen 顶栏；playPetOnceThen/playOnceThenIdle 死链清除
- [x] pet_states.json 同步（偏离桌面镜像记档 notes）；契约测试「行号唯一」→「row0 有意共享」
- [x] 验收：457 绿 skipped=0（+2）；模拟器静止双截图 MD5 一致 + 说话期≠静止帧

## S12 唤醒词打断转正（2026-08-21 收口，用户拍板立项）

> 前置：S11-3 spike 真机双合格（误触发 0/4min 带喂流佐证 + 检出 8/10，v17 README §6.2）。
> 用户两默认值拍板：打断时播「我在」；常开不设开关。

### S12-1 转正实现 ✅（critic 不逊；F1/F3 顺手修，F2 记 notes）
- [x] `VoiceStateRouter`：PROCESSING/SPEAKING 默认 → Sink.KWS（spikeFeedKws 设施退役删除：
  router 属性/VSM 委托/AecSpikeSection ①② 段/VoiceSessionManagerSpikeFeedTest）
- [x] `VoiceSessionManager.startListening`：打断分支扩展至唤醒路径（与 S8-3 按住打断同一
  cancelCurrentTurn 分支）→ 播「我在」→LISTENING；新增 wozaiEpoch 世代守卫
  （唤醒打断唤醒时旧 wozaiJob finally 不覆盖新轮 wakeAckActive，builder 自引入竞态抓修+变异锚③）
- [x] `VoiceForegroundService` onWake 过时注释更正；AecSpikeSection 留 ③ 探针段
- [x] 测试：WakeInterruptTest 3 测（SPEAKING 唤醒全链/PROCESSING 唤醒全链/世代守卫）；
  删 SpikeFeedTest 与 WakeAckTest 废用例（前提失效）；router spike 用例 1:1 置换生产断言
- 验收：455 绿（454−2+3）；三变异锚亲验；真机两轮 8/8（正常+大音量，播放中打断链日志完整）
- 观察票：AEC 偶发失效态（08-21 上午首轮零命中窗，回声持续过门 fed+600/gated+0，重启后消失；
  复发先查 fed/gated 形态定性，频发再评估 S12-2 状态感知门控）；S12 critic F2 全屏连喊视觉塌缩
  （conflation 边缘，进 LISTENING 自愈，记 notes）

## 全程纪律（违反复工）

1. 子智能体编排协议见 CLAUDE.md：fan out builder、critic 盲测严评、loop 到不逊
2. 四类事件立即记 `implementation-notes.html`；每日追加 `DEV_LOG.md`；每票完更新 `PROGRESS.md`
3. 协议/参数只认知识包 + 对账表，实现偏离 = bug；Spec 漂移改 Spec 并记 notes
4. 单测只增不减（当前基线 455，见 CLAUDE.md），skipped=0；禁 mock 被测对象 / 禁 `|| true`
5. 日志禁打 token/密码明文；catch 不静默吞，错误透传真实 msg
