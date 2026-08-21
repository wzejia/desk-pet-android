# CLAUDE.md — 小朵 Android 项目级规则

> 每个会话开工前先读本文件 + `PROGRESS.md` + `CONSTRUCTION.md`；**动模拟器前先读根目录 `EMULATOR.md`**。
> 执行环境：Claude Code（子智能体编排见下节）。pi 会话用项目根 `AGENTS.md`（内容同步）。

## 项目一句话

小朵 Android 版：原生 Kotlin 单 App——**App = 对话式助手主界面**（平台 AG-UI 链路），
**悬浮窗 = 桌面陪伴**（手机精灵式形态 + 气泡回复 + 「小朵小朵」唤醒词语音对话，半双工）。
M1（悬浮桌宠本体）与「助手化」大切片（S0-S8）均已收口；后续工作见 `PROGRESS.md` 接手指引。

## 架构速览

### 包结构（`com.xiaoduo.deskpet`，~65 个 Kotlin 文件）

| 包 | 职责 | 关键类 |
|---|---|---|
| 根包 | Android 入口 | `App.kt`, `MainActivity.kt`, `PetOverlayService.kt`, `VoiceForegroundService.kt` |
| `voice/` | 语音管线（17 文件） | `VoiceSessionManager.kt`(中枢，~33KB 全仓最大), `AudioCapture.kt`, `VadDetector.kt`, `KwsEngine.kt`(sherpa 薄适配), `KwsCore.kt`(门控/decode 线程/检出率统计/release 时序，JVM 可测), `AsrClient.kt`, `TtsClient.kt`, `SentenceSplitter.kt`, `VoicePlayer.kt`, `VoiceStateRouter.kt`, `VoiceClientFactory.kt`(会话单例工厂；RevealScheduler 已退役仅留类备查) |
| `chat/` | 聊天领域（14 文件） | `ChatViewModel.kt`(AndroidViewModel), `ChatTurnLogic.kt`(纯逻辑), `ChatStore.kt`, `MarkdownRenderer.kt`, `ThinkCleaner.kt`, `FunctionKeyLogic.kt`, `FileListRules.kt` |
| `agui/` | 平台 AG-UI 客户端（4 文件） | `AguiClient.kt`(SSE+登录+上传), `AguiSseConsumer.kt`(纯逻辑状态机), `FileMeta.kt` |
| `ui/` | Compose UI（15 文件，4 子目录） | `ui/screens/ChatScreen.kt`, `InputBar.kt`, `FullscreenVoiceScreen.kt`, `SessionDrawer.kt`; `ui/overlay/BubbleView.kt`, `BubbleTextStrip.kt`, `BubbleOwnership.kt`, `OverlayMenu.kt`; `ui/navigation/AppNavHost.kt`; `ui/PetView.kt` |
| `pet/` | 精灵动画/几何（3 文件） | `PetFrameTable.kt`, `FrameAnimator.kt`, `OverlayClamp.kt` |
| `net/` | 网络基础设施（3 文件） | `NetConfig.kt`, `HttpClients.kt`(NO_PROXY+白名单TrustManager), `TokenStore.kt` |
| `util/` | 工具（5 文件） | `PetLogger.kt`, `LogRotator.kt`, `CrashReporter.kt`, `Prefs.kt`, `PrefContract.kt` |

### 语音管线：四态半双工

```
IDLE ──(唤醒词/按住说话)──▶ LISTENING ──(VAD判停)──▶ PROCESSING ──(首帧TTS)──▶ SPEAKING
  ◀────────────────────────────────────────────────────────────────────────────┘
       SPEAKING ──(TTS播完,多轮)──▶ LISTENING    LISTENING ──(30s超时)──▶ IDLE
       任意态 ──(cancelCurrentTurn)──▶ IDLE
```

- **半双工两层**：① `VoiceStateRouter.route(state)` 按状态路由麦块（PROCESSING/SPEAKING 丢块）；② `kwsPaused` 对话期暂停 KWS 防 TTS 自声误触发
- **TurnFlags 模式**（`VoiceSessionManager` 私有内部类）：每轮新建实例，`sealed` 封轮（cancel 第一步先置位）+ `terminalFired` AtomicBoolean CAS 防双终态 + `utteranceForwarded` 记用户已转发
- **新旧轮隔离**：`currentTurn` 引用 + `turn !== currentTurn` 检查——旧轮残跑协程持旧 TurnFlags 引用，迟到 delta/终态被拒绝
- **cancelCurrentTurn 分支**：IDLE→no-op / LISTENING→停VAD回IDLE / PROCESSING+SPEAKING→封轮→停播→停管线→收泡→回IDLE
- **S8-3 barge-in 接续**：speaking/processing 态按住说话 → cancelCurrentTurn → 同一次按住直接进 LISTENING 采音
- **S8-7 唤醒反馈**：`wakeAckActive` StateFlow gate 视觉映射（SPEAKING+true → JUMPING→WAITING，跳过通用 TALKING）；应答音入口 `playAckThenListen()`（S12 起对话态唤醒经 cancel 接续也可达）

### 聊天层：文字/语音统一视图

- **ChatViewModel**（AndroidViewModel，Activity 级单例）：消息流 + 多会话 + 文件上传 + 语音转录桥接
- **文字轮**：`send()` → `runChat()` → AG-UI delta → `ChatTurnLogic.applyDelta()` → markDone/markError
- **语音轮**：`VoiceSessionManager` 通过 `TranscriptSink` 桥接写入 ChatViewModel（`_voiceActive` 守卫窗防止切会话/清空吞流）
- **barge-in**：`ChatViewModel.cancelCurrentTurn()` 按 `voiceActive || vsm.state != IDLE` 门控路由到语音/文字路径
- **ChatTurnLogic**：纯逻辑（零 Android import，JVM 可测），处理消息追加/delta 应用/取消标记

### 悬浮窗：双窗口 + 气泡

- **PetOverlayService**：交互层（可触精灵 645×698px）+ 显示层（气泡专用条 260dp 宽，NOT_TOUCHABLE）
- **气泡**：`BubbleOwnership` 纯逻辑 round/owner 令牌 → `BubbleTextStrip` 流式 markdown 剥离 → `BubbleView` 打字机渲染
- **S8-5 流式解耦**：AG-UI delta 回调直接 bubble.show/append，与 TTS 播放彻底解耦（RevealScheduler 退役，类保留备查）

### 导航

单 Activity + Compose Navigation：Chat（主）→ Settings / Permission / FullscreenVoice（全屏语音模式）

## 权威文档层级（冲突时以上层为准）

1. 产品行为：`docs/plans/2026-07-30-002-feat-xiaoduo-assistant-app-plan.md`（本轮 Product Contract）
   + `docs/plans/2026-07-29-001-...`（M1 基线，未推翻部分仍有效）
2. 协议/语音参数：桌面仓 `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（**只读**，镜像 `docs/m1-dev-kit/`）
   + `docs/voice-param-reconciliation-2026-07-28.md`（语音参数对账表，M3 验收依据）
3. 技术蓝图：`docs/spec/assistant-app.spec.md`
4. 施工清单：`CONSTRUCTION.md`（票据从这里出）
5. 机构解法：`docs/solutions/`（按类目组织 + YAML frontmatter，实现/调试前可检索同类既往坑）
   + 共享领域词汇：`CONCEPTS.md`（采麦流/KWS 常开/半双工四态/意愿登记制/变异锚等，ce-compound 增量维护）
6. 决策留痕：`docs/adr/`、`docs/MASTER-PLAN.md`、`docs/dev-handbook/06`

## 子智能体编排协议（强制执行，CoD 式质量循环）

- **主编排会话（你）只持票**：从 `CONSTRUCTION.md` 取票、派活、验收、记档、commit。
  共享写入点唯一归属你：`PROGRESS.md` / `CONSTRUCTION.md` / `DEV_LOG.md` / `implementation-notes.html` / `*.gradle.kts`。
- **builder 子智能体**（Task 派发）：每票一个，实现+自验。派单只给：票据路径、白名单地界（可写文件清单）、
  验收命令、必读权威文档路径——不给仓库背景，让它自己读。纯逻辑模块（零 Android import：协议消费器/
  分句/VAD/reveal 等）可并行派多个 builder，地界必须错开。
  **异步管线票（协程/回调/服务生命周期）派单前置「守卫四问」**：谁开窗谁关窗（配对）/异常路径终态/
  取消语义/重复触发——builder 开工先答再写码（审计 P2：守卫类打回占 critic 打回大头，属票据结构性缺口）。
  **返回纪律增补**：三样中的数字必附命令原文或推导过程，估值必须标「估」（审计 P4：自报失实 3 次被抓）。
- **critic 子智能体**（独立派发，严苛评审，只挑刺不表扬）：每票完成后派一个。
  派单先标注**验收类型**：代码正确型（diff+亲跑+变异）或**语义验收型**（可见性/可听性/可触性——
  必须真机像素/物理断言，「组件默认应该有」一律降级为待真机断言，不许从 API 印象推断）。
  - 视觉项走**盲测**：你把小朵截图与参照物截图（`docs/research/`）随机标 A/B（不告诉它谁是谁），
    它逐项（布局/留白/层级/精致度/状态反馈）判胜负+具体理由，判完你揭盲。
  - 工程项：读 diff + 亲跑验收命令，专挑：参数是否照抄对账表、断言是否真、日志是否泄密。
  - 判定「不逊」（每项 ≥4/5 且无 ≤2 分项）才过；不过 → 批评意见原样打回 builder 返工再评，
    **loop 到全项不逊为止**；同项连败 3 次记 `BLOCKED.md` 换下一票，不许无限磨。
- 子智能体返回只接受三样：干了什么 / 证据（命令输出·截图路径）/ 卡在哪。不接受「搞定了」。

## 测试纪律

### 基线（不可退）

- 单测 **455 个**，skipped=0，failures=0，errors=0（56 测试套件）
- 验证命令：`./gradlew.bat testDebugUnitTest --rerun-tasks`
- 增量纪律：`CONSTRUCTION.md`「单测只增不减，skipped=0」

### 测试模式（四种，全 JVM，零 Robolectric/Mockito/MockK）

| 模式 | 适用场景 | 代表文件 |
|---|---|---|
| **A. MockWebServer 集成** | 语音管线端到端（HTTP 全桩、被测对象零 mock） | `VoiceSessionManagerBargeInTest.kt`, `VoiceSessionManagerTranscriptTest.kt`, `AguiClientTest.kt` |
| **B. Fake 注入** | 状态机单测（手写 fake 实现 seam 接口） | `VoiceSessionManagerWakeAckTest.kt`（FakeWakeAck/NoopBubble/MemStreamSupport） |
| **C. 纯逻辑** | 协议消费器/分句/VAD/渲染/门闩 | `BubbleTextStripTest.kt`(32 测), `EnergyGateTest.kt`, `OnceGateTest.kt` |
| **D. 契约测试** | 跨源漂移守卫 | `PetFrameTableContractTest.kt`（读真实 assets/pet_states.json 逐字段对账） |

### 核心约束

- **禁 mock 被测对象**：MockWebServer 只桩 HTTP 层；被测对象（VoiceSessionManager/AguiClient 等）用真实实例
- **禁 Robolectric/Mockito/MockK**：项目硬纪律，不引入任何 mock 框架
- **禁 `|| true`**：断言不得弱化
- **Android import 不可达**：`AudioRecord`/`AudioTrack`/`AndroidViewModel` private 方法在 JVM 上不可测——降级为 critic 代码审查 + 真机取证
- **变异锚**：每个异步守卫必须有测试，删除守卫后测试必须变红。守卫注释格式：`// S8-X 回归守卫：…（删此 → … → 红）`

### 常用 fake/helper（跨测试文件复现，未抽取公共基类）

- `MemTokenCache : AguiClient.TokenCache` — 内存 token
- `MemThreadStore : AguiClient.ThreadIdStore` — HashMap 存储
- `MemStreamSupport : TtsClient.StreamSupport` — `isUnsupported()=false`
- `NoopBubble : VoiceSessionManager.BubbleSink` — 空操作气泡
- `RecordingBubble` — CopyOnWriteArrayList 记录操作 + CountDownLatch
- `RecordingSink : VoiceSessionManager.TranscriptSink` — 事件日志 + latch
- `FakeWakeAck : VoiceSessionManager.WakeAck` — latch 可控播放/停止
- MockWebServer 响应构建器：`asrOk(text)`, `loginOk()`, `threadOk()`, `streamingSse(vararg deltas)`, `synthStreamOk(pcm)` (NDJSON audioData+done), `throttledSse` (throttleBody 拉宽 cancel 窗口)

### 时序控制

- `CountDownLatch.await(15-20, SECONDS)` — 终端事件门控
- `throttleBody(50, 100, MILLISECONDS)` — 拉宽窗口使 cancel 可确定性命中
- `setBodyDelay(2, SECONDS)` — ASR 慢响应模拟
- `awaitQuiescence()` — 轮询等 scope 无活跃子协程（50ms 间隔，20s 超时）
- `awaitState(mgr, target)` — 轮询等状态迁移（10ms 间隔，5s 超时）

## 每次会话的工作循环

1. 读 `PROGRESS.md` + `CONSTRUCTION.md` 定位下一张票，写 ≤10 行开工回执再动工
2. TDD：纯逻辑（协议消费器/分句/VAD/帧表）一律纯 Kotlin + JVM 单测先行；Android 层只做 IO
3. 完成 = 可自动验证：单测数 / dumpsys / screencap / 日志原文，不接受「搞定了」
4. **四类事件立即追加 `implementation-notes.html`**：设计决策 / 有意偏差 / 权衡取舍 / 未决问题
5. 每日（或每会话）追加 `DEV_LOG.md`；每完成一项立刻更新 `PROGRESS.md`
6. Spec 漂移即改：实现偏离 `docs/spec/` 时，要么改代码对齐，要么改 Spec 并在 notes 里记原因
7. 跨会话交接：更新 `PROGRESS.md`「接手指引」段

## 真机互斥锁（多 builder 并行时强制执行）

真机 de61cd8 只有一台：任何对设备的操作（install/input/uiautomator/screencap/dumpsys/run-as/am）前必须
`mkdir .scratch/device.lock` 拿锁（忙则 sleep 10 重试），整段办完 `rmdir` 放锁；拿锁 ≤5 分钟、不拿锁写代码。
教训来源：2026-07-30 S1/S3 并行期 install 互杀进程、日志被清两次。

## 模拟器调试（2026-08-17 建成，不接真机可用）

- **AVD `xiaoduo_emu`**：API34 google_apis x86_64，serial `emulator-5554`；SDK/AVD 在
  `E:\Android\Sdk` / `E:\Android\avd`（C 盘满载，旧份退役勿用）。适用：UI/逻辑/文字链/KWS 库加载调试。
- **免设备锁**（真机锁只管 de61cd8），多 agent 可并行用；**声学效果与 ColorOS 行为仍走真机验收**。
- 操作手册（启动/授权/UI dump/日志 tag/避坑，全实测）：根目录 **`EMULATOR.md`**，用前必读。
- 硬规矩：adb 命令显式 `-s emulator-5554`；emulator 启动前当前 shell 显式 export 三个
  `ANDROID_*` 变量指 E 盘（setx 不溯及既往，详见 EMULATOR.md §2）。

## 环境硬事实（实测，勿重新调研）

- **构建**：`export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH"` 后 `./gradlew.bat assembleDebug testDebugUnitTest`
- **Gradle**：8.9（本地分发 `file:///D:/gradle-dist/gradle-8.9-bin.zip`），AGP 8.5.2，Kotlin 1.9.24，Compose BOM 2024.06.00，compileSdk 34，minSdk 26，targetSdk 34
- **真机**：OPPO PJD110（ColorOS），adb serial `de61cd8`，1440×3168 @640dpi
- **模拟器**：`xiaoduo_emu`（API34 google_apis x86_64，serial `emulator-5554`），WHPX 加速；手册见根目录 `EMULATOR.md`；宿主机可达的网络（内网/公网）模拟器 NAT 均可达（2026-08-17 实证 dashscope firstToken 1099ms）
- **adb**：`/e/Android/Sdk/platform-tools/adb.exe`（SDK 权威已迁 E 盘，C 盘旧路径退役）；Git Bash 下先 `export MSYS2_ARG_CONV_EXCL="*"` 防 MSYS 改写设备路径（比包引号可靠，2026-08-06 实证，详见全局 errors.md）——注意它连本地路径一起禁转，install/pull 的本地路径须写 Windows 风格 `E:/...`
- **ColorOS**：禁止 shell 授悬浮窗权限、禁止 `am startservice` 拉非导出服务——权限与开关走 App 内真实用户路径（可 `input tap` 配合 uiautomator dump 定位）
- **内网**：平台 10.0.18.166（8080 登录 / 443 反代）、ASR 10.0.18.97:9802、TTS 10.0.18.97:9803；**必须 NO_PROXY 直连**；443 自签证书按 host 白名单信任
- **平台凭据**：`.scratch/secrets/platform-account.json`（已 gitignore，不入库不引用明文）
- **桌面仓** `E:\desk-pet-migration\desk-pet` **只读**（仅知识包文档允许回写）

## 当前状态（2026-08-06）

- **S0-S8 全部收口**：代码+真机验收+分票提交三清；08-06 续 KWS 检出率日志+release 竞态修复（KwsCore 下沉）+ ce-code-review 五修复 + 孤儿空采 P1（意愿登记制），基线 372 测绿 skipped=0
- **真机期四修复**：S8-5 P2 竞态窗 / strip-empty 重听 / keyStopActive 状态源化 / wozai e3「哎,我在。」（**2026-08-07 改判：去「哎」，b-trim 纯「我在」0.54s 当选**）；输入框单容器拍板
- **剩余**：仅观察项（B2 主观项随日用回收、盲测组 D 挂起）——详见 `PROGRESS.md` 接手指引段

## 当前状态（2026-08-18，S9 应答音色跟随收口）

- **唤醒应答「我在」音色实时跟随 TTS 配置**（S9 四票收口，critic 三轮过，真机六 Case 用户试听全过，证据 `docs/verification/v15-wakeack-voice-follow.md`）：
  四层兜底链（filesDir 动态缓存→厂商默认资产→wozai.wav→直进聆听）；播放路径零网络（DynamicWakeAck 只持 resolve 闭包）；
  预取=「保存即合成+启动补漏」（daemon+退避 10min）；Python↔Kotlin 后处理金向量逐字节对拍；VSM 零 diff。
- **基线 372→427** skipped=0。volc/openai 应答资产暂缺（key 空/端点 404，补跑见 `tools/wozai/README.md`）。
- **遗留**：观察票「全屏语音模式概率性白屏」（用户 08-18 报）；P3 source 日志 platform 模式 provider 标签歧义——见 `PROGRESS.md` 接手指引。

## 当前状态（2026-08-17，模拟器调试基建完成）

- **不接真机调试可用**：SDK 迁 E 盘 + AVD `xiaoduo_emu` + abiFilters 双 ABI（arm64-v8a+x86_64，
  用户批准，真机 APK +~18MB）；模拟器验收全通（悬浮窗精灵 / KWS sherpa x86_64 库零异常 /
  云端对话 firstToken 1099ms），372 测绿 skipped=0。手册 `EMULATOR.md`，
  证据 `docs/verification/emu-2026-08-17/`。
- 08-10 唤醒词三项改动已随 `8e64697` 入库（git 核实）；其「综合真机验证未完成」待办仍有效（见下条）。

## 当前状态（2026-08-10，唤醒词诊断，有未 commit 改动）

- **唤醒词「小朵小朵」漏检诊断 + 三项改动**：阈值 0.5→0.3（IDLE 态真机 100%）、strip 死循环修复（聆听期纯唤醒词回 IDLE）、VoicePlayer `USAGE_ASSISTANT`（绕 ColorOS `AudioHardening` 后台静音）。372 测绿。
- **⚠️ 综合真机验证未完成 → 未 commit**：(a) USAGE_ASSISTANT 是否真绕过后台静音（否则加 AudioFocus）；(b) strip 回 IDLE 真机是否打破循环。验证通过再 commit。
- 详见 `implementation-notes.html` 最新条目 + `PROGRESS.md` 接手指引 ⚠️ 项 + `DEV_LOG.md` 2026-08-10 条目。