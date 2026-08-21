# 小朵 Android M1 进度

## 状态：S12 唤醒词打断 ✅ 收口（2026-08-21，spike 双合格 → 转正 → 真机 8/8）

- **唤醒词打断已上线**：TTS 播放/处理中喊「小朵小朵」= 打断并接续（停播→「我在」→聆听新命令）。
  路由转正（SPEAKING/PROCESSING 恒进 KWS）+ startListening 唤醒路径打断接续（与 S8-3 按住打断
  同一 cancelCurrentTurn 分支）+ wozaiEpoch 世代守卫（唤醒打断唤醒竞态）。
  spikeFeedKws 调试设施退役（生产行为取代）；AecSpikeSection 仅留 ③ 延迟探针。
- **验证链**（证据 v17 README §6）：spike 双合格（误触发 0/4min 带喂流佐证 + 检出 8/10）→
  真机两轮 8/8（正常+大音量）。基线 455 绿 skipped=0（454−2 退役+3 新增）；critic 不逊（F1/F3 已修）。
- **⚠️ 观察票（S12 遗留）**：AEC 偶发失效态——2026-08-21 上午首轮打断零命中，该窗回声持续过门
  一分钟（fed +600/gated +0，与常态「回声在门下」相反），疑与刚重装首启有关，重启后消失未复现。
  **复发时先查该窗 fed/gated 形态即可定性**；频发再评估状态感知门控（S12-2，本轮不盲修）。

## 状态：S11 唤醒词准确率排查 ✅ 收口（2026-08-20，一声唤醒 10/10）+ 唤醒词打断核查（spike 真机观测待做）

- **三层根因叠加，全部修复+上锚**（全证据链 `docs/verification/v17-kws-accuracy/README.md`）：
  ①**阈值 0.5 事故回归**（S11-1）：08-10 的 0.3 无锚，转录重放静默回退 → KwsDefaults 常量+精确锚
  （CONTEXT/spec/对账表三处 0.5 假事实修正）；
  ②**文字轮云端分支事故丢失**（S11-5）：ChatViewModel 无锚内联分支被重建丢弃 → jadx 双源取证复原，
  下沉 TextChatRouter+双 MockWebServer 锚（取证产物入库 docs/verification/forensic-s11-cloud-branch/）；
  ③**真凶=门控丢弃语义碎裂 KWS 时间轴**（S11-6 探针定位 → S11-7 修复）：「整块不喂不补静音」
  挖字间气口压分（第一声必漏）+ 冻结部分分不冲刷（第二声秒中假象）→ 挂起窗 800ms+gap-reset
  （sentinel 保序/消费线程单点 reset/幂等沿）。**超越桌面 parity 的增强**（sidecar.py 取证桌面同缺）。
- **验收**：真机 de61cd8 一声唤醒 **10/10**（修复前第一声 0/10、第二声 10/10）；
  基线 438→**454** 绿 skipped=0（56 套件）；builder×5+critic×4 全不逊。
- **唤醒词打断核查**：当前结构不支持（VoiceStateRouter SPEAKING/PROCESSING 恒 DROP + kwsPaused +
  startListening 非 IDLE 早退三重屏障）；**08-04 AEC spike「零误触发」= 空证据**（旁路 no-op，KWS 全程
  没收到音频，BLOCKED.md 已更正）；S11-3 已重做真放行 spike（`spikeFeedKws` + fed 有效性闸 +
  AecSpikeSection ③ 延迟探针旁置）；sherpa 1.13.4 无 AEC（路封），VOICE_COMMUNICATION 硬件 AEC 已在用。
- **⚠️ 欠账/待做**：spike 真机观测（误触发 ≥5min 0 命中 + TTS 中真声检出 ≥8/10，双合格才立项实现）；
  S10-1/S10-2 critic 补审欠账（08-21 配额恢复后）。

### 接手指引（下会话开场必读）

1. **大盘**：S0-S12 全部收口。基线 **455 单测全绿 skipped=0**（`./gradlew.bat testDebugUnitTest --rerun-tasks` 复跑）。
2. **剩余事项**：
   - **观察票：AEC 偶发失效态**（S12 遗留，见上）——用户报「喊打断没反应」时，拉该时段
     `kws stats` 看 fed/gated 形态（回声持续过门=AEC 失效窗）；频发再评估 S12-2 状态感知门控。
   - 观察票：全屏语音模式概率性白屏（08-18 用户报，未查）；全屏连喊唤醒视觉塌缩边缘（S12 critic F2，
     进 LISTENING 自愈）；P3 resolver source 日志标签歧义；P3 VSM else 分支缺直接测试覆盖；
     wozai volc/openai 资产待 key（tools/wozai/README.md）。
   - S10-1/S10-2 critic 补审欠账（配额恢复后补派）。
3. **新增排障资产**：AudioProbe 延迟探针（设置页 debug ③ 按钮，用法见 v17 README §7）；
  机构解法三篇（docs/solutions/：门控碎裂时间轴 / 无锚代码静默丢失 / spike 空证据佐证法）。
4. 先读：本文件 S12/S11 段 → DEV_LOG.md 2026-08-21/20 → v17 证据 README。

---

## 状态：S10 悬浮窗贴边收纳 ✅ 收口（2026-08-19，模拟器+真机验收全绿）

- **S10 三票收口**：S10-1 EdgeDock 吸附几何纯逻辑（+11 测，基线 427→438 skipped=0）；
  S10-2 服务接线（拖拽松手吸附滑入 / 贴边态单击滑出 / ~200ms ValueAnimator 逐帧 moveTo / 菜单横向钳位）；
  S10-3 语义验收（08-19 本条目）。**⚠️ 欠账：S10-1/S10-2 critic 独立评审未派**（子代理配额墙至 08-21，
  恢复后补派；代码+单测+物理验收均已绿）。
- **S10-3 证据链**（`docs/verification/v16-edge-dock/`；原目录随 08-18 仓库事故灭失，本目录为重建续写，
  含 A/B/C/A2 补拍）：
  - 模拟器（1080×2400，winW=252）：A 左吸 x=-126=dockedX(LEFT) / A2 右吸 x=954=dockedX(RIGHT) /
    C·C2 单击滑出 0·828=slideOutX / **B 反例**：屏中松手零 slide 日志 /
    D 贴边长按菜单全项左缘=0 钳回屏内+「打开 App」点按实测拉起 MainActivity（打断/查看最近一轮 IDLE 置灰=门控正确）/
    E 左贴+右贴气泡本体完整可读（显示层容器可越缘、内容钳屏内）/
    F force-stop→自愈重启恢复 x=954 逐像素 / G wm size 720×1600 收窄重钳 x=594=dockedX(RIGHT,252,720)
    半身不消失（历史 P2 路径回归通过），reset 后几何推导自动脱贴边态（设计内行为）。
  - 真机 de61cd8（1440×3168 @640dpi，winW=937 scale≈1.22）：RM-1 右贴单击滑出 972→503 /
    RM-2 右吸 972 / RM-3 左吸 -468 / RM-4 左贴滑出 0 / RM-5 force-stop 自愈恢复 -468 /
    RM-6 贴边态语音一轮全链（wake 命中→wozai→VAD→ASR「今天的天气怎么样？」→LLM firstToken 456ms→
    TTS SPEAKING→气泡上屏→多轮 LISTENING→超时回 IDLE，全程 x=-468 不变）。
  - `git diff --stat HEAD -- voice/` 为空（voice/** 零 diff 亲证）。
- **⚠️ 验收中揭露并已修复恢复遗留缺口**：`secrets/cloud.properties`（gitignored）未随恢复树回迁 →
  恢复树新构建 BuildConfig 三密钥全空、云链（ASR/LLM）全断。修复：从 device-base.apk 反编译
  BuildConfig 取证恢复（CLOUD_KEY/ALIYUN_SPEECH_KEY len=116，VOLC 原状空）→ 重建
  （新 APK sha1=070d453022f1b82f4a4d6c3412a2963cd869c7c2）→ 真机/模拟器重装 → RM-6 实证全链恢复。
  APK 保全三指纹见 `docs/verification/v16-edge-dock/apk-preservation-2026-08-19.md`。

## 状态：S9 应答音色跟随 ✅ 收口（2026-08-18，真机六 Case 全过）

- **S9 全四票收口**（票据/评分见 `CONSTRUCTION.md` S9 节；证据 `docs/verification/v15-wakeack-voice-follow.md`）：
  方案 C 混合落地——三家云默认音色资产内置（wozai-aliyun.wav 在位；volc/openai 暂缺见下）+ 自定义音色「保存即合成+启动补漏」动态缓存；四层兜底链（缓存→厂商资产→wozai.wav→直进聆听）；**播放路径零网络结构性守卫**（DynamicWakeAck 只持 resolve 闭包）；VSM 零 diff。
- **基线 372→427** skipped=0（+5 契约 +46 纯逻辑 +4 resolver +2 返工守卫）。
- **接手指引（S9 遗留）**：
  1. **观察票：全屏语音模式概率性白屏**（2026-08-18 用户报，无 crash 记录，S9 范围外未查）——复现路径/截图/logcat 待取。
  2. P3 小修：resolver source 日志在 platform 模式 provider 字段带残留云厂商值（选择正确仅标签歧义，一行修）。
  3. wozai-volcengine.wav 待 VOLC_SPEECH_KEY（现为空）；wozai-openai.wav 待可用端点（dashscope compatible-mode /audio/speech 对 gpt-4o-mini-tts 404）。补跑：`tools/wozai/synth_cloud.py --vendor X` + `WakeAckAssetsContractTest` 必备集加一行。
  4. parity 夹具已冻结（sha1 input=06249392/expected=5651d36f），勿再 `--emit-parity-fixture`；改「我在」文案须 Kotlin（TtsVoiceDefaults.TEXT）与 Python 双侧同步并升缓存版本目录。
  5. 真机 UI 自动化教训：Compose 单选的 label 非点击区（圈选在 label 左侧），input keyevent 67 连发会被合并吞——退格要带 sleep 间隔、tap 前必新鲜 dump。

## 状态：S0-S8 全部收口 ✅（2026-08-06）——基线 372 绿 skipped=0；真机逐票验收过；分票提交完成（5 代码 + 1 文档 commit）。真机期四修复全落地：S8-5 P2 竞态窗 / strip-empty 重听 / 多功能键 keyStopActive / wozai e3「哎,我在。」+ 输入框单容器拍板。08-06 续：KWS 检出率日志 + KwsEngine release native 竞态修复（KwsCore 下沉）；续 2：ce-code-review 五修复 + 麦克风孤儿空采 P1（采麦意愿登记制，+6 测，变异锚实测）。**2026-08-07 更新：全屏语音模式精简（删 PhaseHalo/挂断钮/长按菜单/底部 hint）+ wozai.wav 去「哎」重制为纯「我在」（b-trim 0.54s）。**

### S8 真机验收+收口（2026-08-05/06，主会话直接执行，用户拍板免派单）

- **逐票验收**：S8-1 双态截图 ✅ / S8-2 深色壁纸 ✅（浅色跳过）/ S8-3 barge-in 日志链 ✅ /
  S8-4 气泡零裸控制符截图 ✅ / S8-5 bubble show 早 first audio 1.85s ✅ / S8-6 菜单定位 ✅ /
  S8-7 单测变异锚+日志链+用户目视 ✅（连帧截图三轮未中，用户拍板组合证据收口）/ S8-8 三点两相位帧 ✅。
  证据全部入 `docs/verification/`。
- **真机期四修复**（用户反馈驱动，全部 TDD/取证闭环，详见 DEV_LOG 2026-08-06）：
  ① S8-5 P2 delta 竞态窗 double-check sealed（+1 测，变异锚实测）；
  ② strip-empty 死路 → 重播「我在」继续聆听（+1 测；「唤不醒」主诉元凶，19:09 四轮实证）；
  ③ 多功能键 keyStopActive=voiceActive∪VSM非IDLE（边沿事件→状态源，19:46 日志实锤根因）；
  ④ wozai 重制：调研（小米 wakeup_ei=「哎」）→ 13 候选试听 → e3「哎,我在。」入选。**2026-08-07 用户改判去「哎」，b-trim 纯「我在」0.54s 当选。**
  另：输入框单容器拍板（推翻 S8-1 两层形态，R3 功能修复第三次迁移全保留）。
- **提交**：5 代码 commit（图标/气泡/输入框/voice 打包/chat 打包）+ 1 文档 commit，message 附证据路径。
- **观察项**：KWS 真实检出率无数据（漏检零日志，需加近漏检评分日志，暂挂）；wozai 试听候选留设备 /sdcard/Download/wozai-*.wav（可删）。

### S8 代码侧收口（2026-08-05，八票全绿，356 测 skipped=0）

> 执行模式：builder 串行关键路径（S8-3→S8-7）+ 每票派 opus critic 盲审。
> 纪律：单测只增不减（基线 345→356），禁 mock/skip/删测；每守卫必须有变异锚测试（删守卫→红）。

#### S8-1 输入框视觉回滚两层+横线 ✅
- InputBar 从 R3 单容器改回两层独立 Surface + 层间 HorizontalDivider（outline 色 1dp）
- R3 功能修复全保留：行高 48、FunctionBar padding 4dp、isUp 快照、idle 粉底 gate、停止键、上传链
- 无新单测（纯视觉变更，Compose 重组不崩即为验收）
- critic：未派（视觉变更，真机截图验收）

#### S8-2 图标白底 ✅
- 5 密度 mipmap PNG 替换（#FFFFFF 底 + 前景小朵形象）
- `ic_launcher_background.xml` 改 `#FFFFFF`
- 真机桌面截图已抓（`C:\Users\Administrator\s8-2-desktop.png`），待浅色/深色壁纸各一确认可读性
- critic：未派（资源替换，真机目视验收）

#### S8-3 TTS 中按住说话=打断并接续新一轮 ✅
- VoiceSessionManager.startListening：speaking/processing 态按住（playWakeAck=false）→ cancelCurrentTurn → 进 LISTENING 采音
- 3 处 sealed 回归守卫：consumeSentences 循环头 L490 + 合成后二查 L502 + 多轮迁移 L464
- VoiceSessionManagerBargeInTest +2 测（PROCESSING 打断 4 断言 + SPEAKING 打断 stateFlow 闩）
- critic：不逊，4 P2 → 修复 3，1 留档（second-check sealed 守卫在单测中不可达——pipelineJob.cancel 关闭 sentences channel 致 consumeSentences 自然退出；生产环境 TTS 返回 Audio 时仍有价值）
- 变异锚验证：multi-turn !sealed 删→红（有效）；second-check 删→不红（单测局限，已记）

#### S8-4 气泡渲染 markdown 控制符修复 ✅
- BubbleTextStrip 裸 markdown 控制符剥离（`**`/`|`/```/`#`/`>`）
- BubbleTextStripTest +6 测（无空格标题/引用/加粗/斜体/行内代码/表格管道符）
- critic：未派（S8 开工前已由 builder-S8-4 完成，基线 345→351 +6）

#### S8-5 气泡文字改流式直出（撤 reveal 音频同步） ✅
- VoiceSessionManager：AG-UI delta 回调直接 bubble.show/append，与 TTS 播放解耦
- 退役 playAndReveal/reveal 方法（RevealScheduler 类保留备查）
- 流式 holdback/清洗保留（SentenceSplitter 仍逐字符喂，半截标记闭合后才产出）
- TTS 单句失败降级：气泡文字不受影响（delta 已上屏），仅跳过本句播放
- critic：不逊，1 P2（delta callback check-then-act 竞态窗：L438 `if(turn.sealed) return@chat` → L439 `transcriptSink` → L444 `bubble.show`，cancel 可在 sealed 检查与 bubble.show 之间执行，致幽灵气泡）→ **待修**（权限低，真机期顺修）
- critic：1 P3（BargeInTest 注释 "reveal 逐字查岗" 过时，应改为 "delta sealed 拦截"）→ **待修**

#### S8-6 气泡「看不到开头」补救 ✅
- OverlayMenu 新增「查看最近一轮」菜单项（canViewLastRound 门控：无内容时置灰不可点）
- PetOverlayService：hasShownBubbleContent 标记（showBubble/appendBubble 非空置位，永不复位）
- MainActivity：handleViewLastRoundIntent + onNewIntent（EXTRA_VIEW_LAST_ROUND → ChatViewModel.requestScrollToLastRound）
- ChatViewModel：pendingScrollMessageId 一次性事件（哨兵 `__last__`）+ consumePendingScroll
- ChatScreen：LaunchedEffect(pendingScrollMessageId) 消费 → scrollToItem + listState 上提
- critic：不逊，1 P2 → **降级 P3**（sentinel 单测——requestScrollToLastRound/consumePendingScroll 是 AndroidViewModel private，项目无 Robolectric/mock 基建，纯 JVM 不可达；接受为已知局限 + 真机取证替代）
- critic：4 P3（记入 implementation-notes）

#### S8-7 唤醒成功即时视觉反馈 ✅
- VoiceSessionManager：`_wakeAckActive: MutableStateFlow<Boolean>` + 公开 `wakeAckActive` StateFlow
- startListening(playWakeAck=true)：入口置 `_wakeAckActive=true` + `PetOverlayService.playPetOnceThen(JUMPING, WAITING)`
- transition()：SPEAKING + wakeAckActive → 跳过通用 TALKING 映射（JUMPING→WAITING 已接管）
- wozaiJob finally：无条件 `_wakeAckActive.value = false`（critic P2 修复：原 `if(isActive && state==SPEAKING)` 在 onUtterance 抢跑切 PROCESSING 时跳过复位致残留）
- cancelCurrentTurn SPEAKING/PROCESSING 分支：`_wakeAckActive.value = false`
- FullscreenVoiceScreen：LaunchedEffect 订阅 wakeAckActive，gate SPEAKING 映射
- VoiceSessionManagerWakeAckTest +3 测（时序/按住路径/cancel 复位）+ 1 增强（wozaiCompletionDuringPipeline 追加 wakeAckActive 断言）
- critic：不逊，1 P2（residue path）→ 已修复（finally 块），5 P3（记入 implementation-notes）
- 变异锚验证：删入口 set true→红、删 finally set false→红、删 cancel set false→红（全部有效）

#### S8-8 生成中等候态三点动效 ✅
- ChatScreen：TypingDots Composable（三圆点错相呼吸，900ms 周期，三角波 alpha 0.3↔1.0）
- SENDING 状态替换原 CircularProgressIndicator + "小朵思考中…" 文字
- STREAMING 首 token 到达自然切换（状态机 SENDING→STREAMING 触发重组，TypingDots 消失）
- 与现有 sending/streaming/done/error 状态点对齐不冲突
- critic：不逊，2 P3（记入 implementation-notes）

#### S8 收口清单

| 票 | 状态 | 测试 | 剩余 |
|---|---|---|---|
| S8-1 | ✅ 代码 | 无新测（视觉） | 真机截图（语音态/文字态各一） |
| S8-2 | ✅ 代码 | 无新测（资源） | 真机浅色/深色壁纸桌面截图各一 |
| S8-3 | ✅ 代码 | +2（356 绿） | 真机日志时序（TTS stop→listening→ASR→LLM） |
| S8-4 | ✅ 代码 | +6（351 绿） | 真机长回复气泡截图无裸控制符 |
| S8-5 | ✅ 代码 | 无新测（退役 reveal） | P2 check-then-act 竞态窗 + P3 注释更新 + 真机时序（delta→上屏 ≤ 播音开始） |
| S8-6 | ✅ 代码 | P3 降级（JVM 不可达） | 真机交互（长按菜单→App 定位到最近一轮） |
| S8-7 | ✅ 代码 | +3（356 绿） | 真机唤醒连帧（idle→jumping→waiting） |
| S8-8 | ✅ 代码 | 无新测（Compose UI） | 真机截图（等候期三点可见、首 token 后消失） |

**测试基线**：356 绿 skipped=0 failures=0 errors=0（HEAD 345 + S8-4 +6 + S8-3 +2 + S8-7 +3）
**工作树变更**：20 files, +595/-153
**未提交**：零 commit，所有变更在 working tree，待真机验收后分票提交

### S8 开工回执（任务 0，2026-08-05）
- 八票理解：S8-1 输入框视觉回滚两层+横线（R3 功能修复全保留：isUp 快照/idle 粉底 gate/48 行高/padding 4/停止键/上传链）；S8-2 图标白底；S8-3 TTS 中按住=打断并接续新一轮；S8-4 气泡裸 markdown 控制符（|**|```|#|>）修复；S8-5 气泡文字流式直出撤 reveal（KD13 修订，RevealScheduler 类保留备查）；S8-6 长按菜单「查看最近一轮」；S8-7 唤醒即时视觉反馈（先查实再补）；S8-8 生成中三点等候动效。
- 基线已核：BUILD SUCCESSFUL；单测 XML 亲算 tests=351 skipped=0 failures=0 errors=0（HEAD 345 + 工作树 S8-4 BubbleTextStripTest +6）。
- **工作树半成品盘点**（`git diff HEAD` 12 文件 +105 行，逐文件核对无 S8-3/5/6/8 半成品代码）：S8-2 已完成（#FFFFFF+5 PNG）/ S8-4 已完成（围栏+无空格标题/引用 +6 测）/ S8-7 脚手架在零调用方（playOnceThen API 已验可编译）/ S8-1 仅改 KDoc 代码体仍 R3 单容器。执行形态=验证 2 票+接线 1 票+实现 5 票。
- 分组：并行组 S8-1(InputBar)/S8-2(mipmap)/S8-4(BubbleTextStrip) 各一 builder；voice 串行 S8-5→S8-3→S8-7 同一 builder（顺序微调避开 InputBar/PetOverlayService 撞车）；chat 串行 S8-8→S8-6 同一 builder。三组互并行。
- 地界裁决：ChatViewModel/ChatScreen/OverlayMenu/PetOverlayService/MainActivity/AppNavHost 归 chat builder；voice/**/BubbleView/PetView/pet/**/FullscreenVoiceScreen 归 voice builder；越界改动需求报编排层裁决落地。
- 最大风险：S8-1 回滚弄丢 R3 功能修复（派单明示 isUp 快照+idle 粉底 gate 为保命代码，视觉动骨架不动手势/状态逻辑）；次风险 S8-5 改流式后 cancel 时 sealed 须同步屏蔽 bubble.append（回归重灾区）。真机 de61cd8 已在线，真机取证无阻塞。

### S7-f 输入框重构+上传 ✅ 代码侧+真机（P1 松手不物化/P3 idle 粉底双修复,双 critic 不逊;剩盲测组 2+震动用户确认）
- 施工：InputBar 迁出 ui/screens/InputBar.kt 两层（InputArea 按住说话满宽区/TextField + FunctionBar 上传键+多功能键）；
  声波纹 24 柱（LaunchedEffect(holding) 订阅 captureBlocks+EnergyGate.rmsOf）；awaitEachGesture 上滑 80dp 取消
  （滑回恢复、系统打断走 CancellationException——compose-ui 1.6 无 PointerEventType.Cancel 留痕）；Vibrator 30ms；
  FunctionKeyLogic 五态纯逻辑（busy||voiceActive→STOP 最高优先＞uploadInFlight→DISABLED＞SEND＞TO_VOICE＞TO_KEYBOARD，
  UI 层 400ms 防抖防 SEND→STOP 误取消）；AguiClient.uploadFile（契约逐字：fileClass=agent----{agentId}----{threadId}，
  401 透明重登 1 次）；FileMeta.buildContent 前缀注入+分隔符 sanitize+localId；voiceActive 改 StateFlow（STOP 档驱动重组）。
- **critic 两轮**：R1 打回 2P2（resend 漏 uploadInFlight 守卫/resetFiles 连坐清空未挂载文件）→
  修复=FileListRules 纯逻辑同源（send/resend 共享 uploadInFlight() 判定点，编译层不可分叉；
  attachedDone 发送一刻快照+clearAttached 只清本轮挂载，STREAMING 中后传 DONE 文件存活）→ R2 **不逊**
  （契约逐字零漂移/变异 4 处红绿齐/泄密 0 命中/守卫四问全过/白名单过）。测试 314→345（+31）skipped=0。
- **真机验收**（2026-08-04 晚）：揪出 **P1 松手不物化**（consume 先于 changedToUp 恒 false，字节码实锤，
  consume 前快照 isUp 修复）+ **P3 idle 永久粉底**（cancelled 未 gate holding），双修复 critic 不逊。
  逐项：松手发送全链 ✅（manual release/utterance→ASR→LLM→SPEAKING，气泡上屏）；声波纹起伏 ✅
  （w7/w8 帧对比）；上滑取消无消息发出 ✅×2；上传 fileId ✅（三连+模型逐字引用附件原文=@file: 铁证）；
  停止键 ✅；上传中禁发 ⚠️ 降级（瞬态四抓未中，逻辑层钉死）；震动 ✅ 用户确认（08-05）；盲测组 2 三连败关账。
- **盲测组 2 三连败关账**（2026-08-05 凌晨，协议规则执行）：R1 负 → v3 单容器返工 → R2 2 胜 2 负
  （hold 对 4 胜 1 负逆转+停止键全胜）→ R3 收容器空洞（120→104dp,critic 不逊）→ R3 1 胜 3 负，
  三连败记 BLOCKED 不再磨。败因分层：hold 对 R3 丢分=抓帧音量漂移材料不可比（R2 同代码曾胜）；
  空闲两对稳定负因=对手能力 chips 属产品功能差异（无此能力可视觉返工）——产品若加能力入口可重赛。
  稳定胜点：生成中停止键三连胜 DeepSeek。~~R3 代码保留不回退~~ **后注:S8-1 拍板视觉回滚两层+横线
  （用户视觉拍板优先于盲测口径）,功能修复保留**。
- P3 留档：clearAttached 读-改-写毫秒级竞态窗（与 R1 resetFiles 同暴露，非本票回归）。

### S7-e 语音行为 ✅（critic 三轮后不逊；真机打断双路径取证全过；AEC spike 半程）
- 施工：wozai.wav（平台 TTS 合成，RIFF 校验）；WakeAck 接口+startListening(playWakeAck) 仅 KWS 路径；
  VoicePlayer.stop；VSM.cancelCurrentTurn（sealed 封轮+TurnFlags 每轮实例+CAS 恰好一次）；VM cancel
  （turnEpoch+chatJob+markCancelledDone 部分 DONE 不重发）；VM 门控=voiceActive||vsm.state!=IDLE（拍板）；
  InputBar onFirstChar；AecSpikeSection（FLAG_DEBUGGABLE 门控，偏离 BuildConfig 记 notes）。
- **critic 三轮**：R1 打回 2P1（synthesize→play 幽灵音频窗/voiceActive 门控死路径）+2P2+2P3 →
  R2 打回（flaky 新测 2/3 红/playAndReveal 注册窗/Error 幽灵泡）→ R3 **不逊**（314 绿、变异锚 6 处红绿齐、
  WakeAckTest 5 连跑证稳）；P3 顺手修落（sealed 上提先于 stop+补枪改查 sealed）。
- **真机取证**：① 「我在」日志链 SPEAKING(wake-ack wozai)→LISTENING(wozai done)（用户真声轮）；
  ② 文字轮打断：barge-in: cancel text turn，部分文本 DONE 无重发、729 字迟到全文未覆盖（epoch 实证）；
  ③ 语音轮打断：TTS 播 24s 中敲首字符→barge-in: cancel voice turn(voiceActive=true,vsmState=SPEAKING)
  →4ms 内 IDLE 停播；④ AEC spike 误触发 2/3 轮 0 命中（累计 ~5.8min ≥5min 口径已达；
  **用户 2026-08-04 晚拍板挂起，检出率未测，见 BLOCKED**）。
- **当前**：S7 七票代码侧全收口（a~g，345 测绿）；剩设备窗口三事：S7-f 真机验收+盲测组 2、AEC spike 续做（误触发还差 2 轮 ≥5min + 检出率 10 次真声）。

### S7-d 全屏语音模式 ✅（功能+盲测双收口，四轮 负负负胜）
- 功能验收全过+真机连续取证（2026-08-03 晚，详见 DEV_LOG）。
- **盲测组 1 四轮全记录**：R1 14:20 负（状态线索零/无退出路径）→返工 X 钮+四态 caption+hint；
  R2 19:23 负（立绘不随状态变化）→返工 PhaseHalo 状态光环（LISTENING 真实 RMS 呼吸/PROCESSING 旋转/
  SPEAKING 波浪）；R3 19:20 负（caption 主语歧义/控制不可见）→记 BLOCKED；**用户拍板放行 R4+白底改判** →
  返工 caption 主语化（正在听你说/小朵正在思考/小朵正在回答）+聆听 mic 图标+迷你声波+红色挂断钮+
  #FAFAFA 白底全配色 → **R4 封盲 21:19 胜千问**（状态可辨 4:3/精致度 5:4，三项平，全项不逊）✅。
- critic nit 留档（不返工）：菜单 hint 与红挂断钮位置过近易误读。

### S7 波次 1（2026-08-03，三并行 builder + 三独立 critic 全不逊）
- **S7-ab 会话管理+顶栏** ✅：a/b 合并（同改 ChatScreen.kt 冲突）；聊天记录页/路由/入口删；titleManual+
  saveMessages 跳过；renameSession（不动 updatedAt）；renameThreadForSession（端点桌面仓查实 `/renameThread`）；
  长按操作菜单+重命名对话框；顶栏 32dp 头像/无「小朵」/~48dp/右上角预留位。单测 +12；变异双红。
  **真机取证全过**：改名后发消息 title 不覆盖（S7RenameProbe 截图）；平台 rename 成功+失败双路径日志；
  顶栏瘦身截图。
- **S7-c 气泡升级** ✅：13f/100dp/260dp；BubbleTextStrip 剥离→纯 Kotlin StyledText 渲染（粗/斜/代码/列表）+
  toSpannable；holdback 未动；critic 打回 1 条（同步滚动旧 layout）→ `post{scrollToBottom}` 返工过；
  单测 18→26 变异双红。**真机取证**：显示层 1040px=260dp、超长滚到底、三 span 同屏、表格不渲染。
- **S7-g 图标** ✅：idle 首帧裁切 adaptive icon（#171717 底）+Manifest icon；aapt 坐实；真机桌面图标截图过。
- **编排层并行线** ✅：待查项①关闭（fileId=content 文本前缀 `@file:{id}?{name}` `|` 连接+`\n`，平台 bundle
  静态取证记 notes）；待查项②关闭（renameThread 双源查实）；wozai.wav 已合成（24k/mono/16bit，`.scratch/wozai/`）；
  千问语音模式+DeepSeek 录音态参照截图入 `.scratch/refs/`（ChatGPT 对标用户改判出局）。
- **下一波**：S7-d 全屏语音模式（←S7-b 已就绪）→ S7-e → S7-f 串行。基线 **294 测绿 skipped=0**。

### S7 开工回执（任务 0,2026-08-03）
- 目标：S7-a~g 整盘——会话重命名+废弃聊天记录页 / 顶栏瘦身 / 气泡升级 / 全屏语音模式 / 唤醒「我在」+barge-in / 输入框两层重构+上传 / 图标；终点线=子切片验收条全勾+两组盲测不逊（全屏语音 vs 千问+DeepSeek，输入框 v2 vs 小布；ChatGPT 对标已被用户改判出局）。
- 基线已核：BUILD SUCCESSFUL，单测 XML 亲算 tests=274 skipped=0 failures=0 errors=0；真机 de61cd8 在线；凭据 json 可读（account/password 两键）。
- 波次：①S7-ab/S7-c/S7-g 并行（a+b 合并——同改 ChatScreen.kt 地界冲突）→ ②S7-d ← ab → ③S7-e ← d → ④S7-f ← e。
- 待查项：②rename 端点已从桌面仓查实（POST /ai-server/agent/renameThread，body{threadId,threadName}）；①fileId 接入两仓零命中→编排层 Playwright 抓包回填；③AEC spike 从零搭；④按 spec 直接做。
- 最大风险：VSM cancelCurrentTurn 双终态防线（voiceActive 永挂）；fileId 抓包若平台 web 端无文件入口则需降级记 BLOCKED；千问/DeepSeek 语音模式可能需登录才能截图。

### 2026-07-31 进展
- **基线复核** ✅:BUILD SUCCESSFUL,123 单测全绿 skipped=0(awk 亲算 XML);真机 de61cd8 在线。
- **B3 气泡垂直跟随定案** ✅ **非 bug 关闭**:三连截图+同拍日志+代码复核(runCatching 带日志不静默),
  证据 docs/verification/b3-1/2/3-*.png;原观察判定为串台期截图时序错位。
- **S3 默认外观截图** ✅:docs/verification/s3-pet-default.png;dumpsys 双窗取证
  (交互层 384x416 无 alpha / 显示层 768x312 alpha=0.8 @ (864,648) scale=0.5)。
- 观察项已闭环:设置页悬浮窗开关态(pref)与运行态(UI)分叉 → P1 服务自愈修复(见下,commit 9b2a991)。
- 并行在施:builder-S2 返工 6 项(chat/agui 地界)、builder-S4 P3×3(voice 地界,已交付待 critic)。
- **悬浮窗盲测 v4** ✅:遮挡克制 4:2 胜手机精灵(揭盲 B=小朵,零遮挡+落位留白;对手 2/5 压图标标签)。
  S3 悬浮窗形态组=全项不逊(盲测组 C 收口)。
- **S4 P3×3 收口** ✅(critic 不逊,8 项全过+变异双红):AudioCapture DROP_OLDEST 改判与对账表
  deque 语义一致;启动竞态 Structured Concurrency 修复(AudioCapturePumpTest 4 测);回环留档
  loopback-2026-07-31/(合成「今天天气真好,适合出门散步。」→ASR 逐字命中含标点,wav/响应/复现步骤可审计)。
  voice 域 48 测绿;全量 158 测绿(builder-S2 交付后)。
- **S2 返工 6 项 builder 已交付**(158 测绿、assembleDebug 绿),critic 严评中。
- **S2 返工 critic 不逊**(10/10,变异三杀红绿齐)✅;深色抽查过(s2-rework-dark.png)。
- **导航改判(用户拍板)**:顶栏图标撤下→hamburger+ModalNavigationDrawer;UX critic 清单已出,builder 派工中。
- **导航抽屉式收口** ✅(critic 不逊 9/9 + 真机 6 断言全过):抽屉/聊天记录页/共享 VM/顶栏瘦身;
  159 测绿;证据 nav-1/3/4/5。事故留痕:用户持机期间 tap 误入微信,截图已删无损害,教训记入 DEV_LOG。
- **终版盲测两轮**:输入区 19:14 胜小布(但精致度/状态线索两项 3/5 未不逊→返工);
  聊天页 15:16 输 ChatGPT(富文本 2/5:表格断词+代码块截断两硬伤→返工,连败 1)。
- **并行在施**:builder-气泡所有权上移(#4,S5 前置)/ builder-富文本+输入区返工(#14,地界错开)。
- **N2 状态栏融合** ✅(真机双模式无接缝);**N5 输入区 v4** ✅(pill 配重/scrollToBottom/渐变根因=fillMaxHeight 无界 no-op→matchParentSize,像素实证);
  **N4 多会话** 交付 202 测绿(+34),critic 严评中。
- **输入区盲测 v4 胜 16:14**(逐项不输小布:4>3/3=3/3=3/3=3/3>2),但绝对尺(每项≥4)仍差 4 项——
  状态反馈等项参照物也只 3 分,尺子校准问题待用户裁决。
- **盲测尺子改判(用户拍板)=相对尺** ✅:输入区 16:14 胜小布、聊天页 16:15 胜 ChatGPT,双组逐项不输收口。
- **3-bug 收口** ✅:斜体渲染/渐变 0.2+chevron/顶部遮罩整体撤除(洗白停靠行根因,像素钉死)。
- **真边栏收口** ✅(critic 不逊 11/11 + 真机交互取证):收窄露 scrim/全高延伸/搜索双向/新建/长按删除+回落;
  hamburger 圆形容器;PetThumb 死 dimen 清;vm 默认值删除(P3 引信拆)。
- **N4 多会话** ✅(critic 不逊 10/10,202 测绿;真机迁移自然发生,原文取证完好)。
- **空态引导** ✅(小布形态,真机取证);**deleteThread 接线** ✅(critic 不逊 10/10,幂等/独立重登/放弃语义)。
- **S5 KWS 交付**(引擎+前台服务+门控校准+收泡,14 新测);critic 判不过打回 2 条:
  P1 麦泄漏竞态(init 线程×onDestroy,服务死麦照采)/P2 错误泡不收(绕过 onBubbleShown 置位),返工中。
- **S5 代码全收口** ✅(commit 6abfec8):返工 2 条复评不逊(锁粒度亲核/置位顺序/243 绿);
  真机唤醒验收挂 B2 ⑥-⑩(等设备窗口)。**全部代码票清零,剩真机验收周**。
- **气泡 markdown 剥离** ✅(commit 0878e3c):BubbleTextStrip 流式剥离(粗斜体/行内码/表格管/标题引用),
  语音/文字泡不再裸显 **、|、反引号。
- **P1 服务自愈** ✅(commit 9b2a991):MainActivity.onCreate 按 pref 自愈悬浮窗/KWS 服务,
  force-stop/重装后自动回来(上条「须手动重拨」观察项由此关闭)。
- **P1 语音轮消息入会话历史** ✅(commit 88d2b1e):用户拍板语音/文字同一会话统一视图。
- **语音流式上屏** ✅(critic 三轮后收口,261 测绿):TranscriptSink 流式契约 v2——user 立即上屏/
  回复逐 delta 实时;voiceActive 守卫窗(busy 扩展,防切会话/清空吞流);异常路径 try/catch 兜底
  +terminalFired 防双终态(critic 第 2 轮打回点)。**待真机用户验「边说边上屏」**。
- **voice 域 P3 尾巴(backlog #8+#10)** ✅(critic 不逊,265 测绿 +4):OnceGate 门闩一式两用——
  KWS 双 init 源头堵双载 + 采麦双调竞态原子化;#10 异常路径 sentences channel 补 close
  (PipelineCloseTest 注入异常断言无残留协程);变异双红(tryAcquire 恒 true / 删 close)。
  critic 观察项(过期 release 理论窗,严格收窄未变差)挂 backlog #11 epoch 令牌门闩。
- **OnceGate epoch 令牌(backlog #11)** ✅(critic 不逊,266 测绿 +1):过期 release 自动失效,
  stale-token 测试+变异双证;残余 P3 理论窗(布尔版既有非回归)留档 BLOCKED #11。
- **P3 零星三项(backlog #12)** ✅(critic 不逊,270 测绿 +4):VM 两处 launch 兜底 catch
  (+编排层落地 critic nit:DONE 守卫防双写);BubbleTextStrip 空 span 冻结修复+反引号 48 封顶;
  会话列表零会话占位。**代码类 backlog 全清,剩余全部要真机。**
- **真机验收波次(2026-08-03,装包 dd84eec+抽屉修复)**:
  ① 深色走查五页齐(chat/drawer/settings/history/permission,返回键+对比度合格,取证 s6-dark-*.png);
  ② **抽屉返回键+scrim 双失灵实测发现** → builder 根因修复(material3 1.2.1 javap 双实锤)→
  critic 不逊(独立 javap 复核)→ 真机四断言全过;
  ③ wm size 矩阵:1080 完整;**720 发现 P2 宠物出屏消失**(位置不重 clamp)→ 两轮修复收口
  (一轮窗 margin 被真机打回→二轮窗中心钳屏内,critic 不逊+真机 720 精灵半身可见像素实证);
  ④ 隐私纪律:含短信验证码/私人便签的 5 张误拍全部即删,悬浮窗证据只留裁切区;
  ⑤ **语音流式上屏真机验收过**(用户两轮实测确认「话立刻出现、回复逐字蹦」;日志时序:
  user 上屏=ASR→AG-UI 前,回复流式渲染早于播音 ~2s,firstToken 3254ms 略超 3s 预算留观察);
  ⑥ 服务自愈顺带实证(install -r 后 PetOverlay+VoiceFg 自动回来,日志原文)。

### 在施票状态（2026-07-30 停线快照）
- **S2 聊天主界面** 🔶：交付+critic 过（20 chat 测、双变异坐实、真机 5 轮对话、杀进程恢复、泄密 0 命中）。
  盲测：聊天页 19:16 胜 ChatGPT（排版 3/5：裸 >、顶部裁行）；输入区 13:18 输小布（被裁到键盘次级模式，
  重测须用默认语音模式截图）。返工 6 项已派未动工（清单在 BLOCKED.md backlog #1）。
- **S3 悬浮窗** 🔶：双窗口职责翻转（用户拍板：PetView 回交互层全不透明，显示层=气泡容器）+
  两轮返工代码全绿（菜单 184dp 固定宽+开关态/气泡 X clamp+描边/append 对称 hide/onDestroy 不写偏好/
  receiver DEBUG 门/默认 scale 0.5+右侧落位）。盲测 v3 15:10 胜（遮挡 3/5 已返工待重测）。
  **未定案**：P1 气泡垂直跟随（BLOCKED B3）。
- **S4 语音引擎** 🔶：44 voice 测绿、critic 契约逐字段过、变异坐实；kwsPaused 漏复位 P2 已修（编排层
  一行修复+123 全绿验证）。P3×3 与真机全链路挂 BLOCKED B2。
- **S5** ⬜ 资产+gradle 就绪；**S6** ⬜ 盲测 4 组进度见 CONSTRUCTION.md 总览表。

### 已完成票
- **S1-pure AguiSseConsumer** ✅（9 单测绿 skipped=0）：三件套状态机（断流→Error/空 run→EmptyRun/SNAPSHOT 前缀补尾），
  critic 严评过；变异实验双向钉死（删终止帧校验→红、删前缀守卫→新测试红、还原→绿）。
- **S4-pure 语音四件套** ✅（30 单测绿 skipped=0）：VadDetector/SentenceSplitter/TtsSanitizer/RevealScheduler，
  参数与对账表零漂移（critic 逐字核对 + 桌面 HEAD 逐行对齐）；变异实证：deque 6→3 红、判停 8→4 红、
  阈值 0.05→0.01 曾假绿→已补负样本守护钉死。留档偏差：isalnum No/Nl 类目差（implementation-notes）。
- **S5 资产就绪**：sherpa-onnx-1.13.4.aar 入 app/libs（用户批准 GitHub k2-fsa 源）+ kws-model 5 文件入 assets。
- **S0 Compose 地基** ✅：M3 双色主题（浅 #FFF/#F7F7F8、深 #000/#171717，dynamicColor 关）+ 三页导航 +
  NetConfig/TokenStore/OkHttp 白名单工厂/CrashReporter；util 测试 22 绿（LogRotator 6+NetEndpoints 4+PrefContract 2+TokenPolicy 10）。
  critic 严评过（隔离快照亲验）；TTL 字面量断言钉死（变异 25→250min 双红→还原绿）；真机深浅色/悬浮窗/导出日志回归取证齐。
  留档：白名单不 pin 证书=权衡接受；allowBackup=false 已落（用户拍板）。
- **S1 AG-UI 链路** ✅：AguiSseConsumer 9 测 + AguiClient 7 测全绿。**真机首聊成功**（2026-07-30 17:34）：
  登录→createThreadId→443 SSE→首 token 1860ms（热态，≤3s 达标）→RUN_FINISHED 456 字；
  二次自检证明 token/thread 缓存生效；日志凭据/UUID 形态 grep 双 0 命中（critic 亲测）。
  critic 严评过（协议逐字段对账 + 重试同 id 变异杀活）；P3 收口：msg id 加 seq 后缀、createThreadId 401 透明重登、attempts 断言。

### 机构记忆（ce-compound 沉淀）
- `docs/solutions/ui-bugs/android12-not-touchable-overlay-alpha.md`（NOT_TOUCHABLE 窗被框架压 0.8）
- `docs/solutions/workflow-issues/parallel-builders-single-device-mutex.md`（真机互斥锁协议）
- `docs/solutions/ui-bugs/material3-121-modal-drawer-scrim-back.md`（material3 1.2.1 抽屉双根因）
- `docs/solutions/ui-bugs/overlay-clamp-content-visibility.md`（clamp 对象=内容可视区非窗体几何边）
- `docs/solutions/workflow-issues/wm-size-matrix-privacy-and-page-identity.md`（wm 矩阵三纪律）
- `docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md`（按住说话 consume 抢先 changedToUp 松手不物化）
- `docs/solutions/logic-errors/delta-callback-check-then-act-ghost-bubble.md`（check-then-act 夹可阻塞 sink → bubble 前 double-check sealed）
- `docs/solutions/logic-errors/strip-empty-wake-word-dead-end-replay-ack.md`（纯唤醒词 utterance 死路 → 重播我在；「空」用内容谓词不用串谓词）
- `docs/solutions/ui-bugs/edge-event-missed-keystopactive-stateflow.md`（UI 显示判据用状态源派生、与行为门控同源；边沿事件对晚到订阅者不可见）
- `docs/solutions/design-patterns/wake-ack-audio-selection-cosyvoice.md`（应答音=语气词非陈述句；CosyVoice 标点控语调；重制四步管线）

### 接手指引（下会话开场必读）
1. **大盘**：S0-S8 全部收口（代码+真机验收+提交三清）。基线 **372 单测全绿 skipped=0**；
   `./gradlew.bat testDebugUnitTest --rerun-tasks` 复跑确认。成果 commit 链见 DEV_LOG 最新条目。
2. **剩余事项全部轻量**：
   - B2 主观项（播放听感/句间断音、AEC 误触发、sticky 保活）——随用户日常使用回收，非工单；
   - 气泡盲测组 D——用户挂起，想做时截手机精灵气泡参照物；
   - firstToken 观察项（PC 采样脚本 .scratch/firsttoken_sample.py 就绪，用户拍板跳过未跑）；
   - ~~KWS 真实检出率观察项~~ **已收口**（08-06 续：KwsCore 检出率日志——fed/gated/decoded/hits
     四计数 + 命中流水号 + 60s stats 行 + release 汇总；同日修复 release native 竞态 P1）；
   - 设备 /sdcard/Download/wozai-*.wav 试听候选可删（设备侧垃圾）。
   - **⚠️ 唤醒词改进待验证（2026-08-10，有未 commit 改动）**：阈值 0.5→0.3 + strip 死循环修复（聆听期纯唤醒词回 IDLE）+ VoicePlayer USAGE_ASSISTANT（绕 ColorOS 后台静音）；372 测绿，APK 已装 de61cd8。**综合真机验证未完成**——(a) USAGE_ASSISTANT 是否真绕过后台静音（否则加 AudioFocus 请求）；(b) strip 回 IDLE 真机是否打破循环。验证通过再 commit。详见 implementation-notes.html 最新条目。
   - **✅ 08-14 两改动已真机验收通过（2026-08-14）**：①悬浮窗长按菜单「打断」（SPEAKING 中点→停播+收泡+回 IDLE，日志链完整；IDLE 置灰）；②云端密钥内置包（删 SP 零配置：文字轮 dashscope 662ms + 语音轮阿里 ASR/TTS 内置 key 全链路）。证据 `docs/verification/v14-interrupt-menu-and-cloud-builtin.md`，已分票提交。密钥文件 `secrets/cloud.properties`（gitignored，本机存在；换机分发时随包重建）。
   - **✅ 08-17 模拟器调试基建完成（Android Studio 不接真机调试可用）**：C 盘满 → SDK 迁
     `E:\Android\Sdk`（环境变量 ANDROID_HOME/ANDROID_AVD_HOME 已设）；AVD `xiaoduo_emu`
     （API34 google_apis x86_64，E:\Android\avd）；abiFilters 双 ABI（arm64-v8a+x86_64，
     用户批准，真机 APK +~18MB）。模拟器验收五图 `docs/verification/emu-2026-08-17/`：
     悬浮窗精灵/KWS sherpa x86_64 库零异常/云端对话 firstToken 1099ms 全通。定位：
     模拟器=UI/逻辑/文字链日常调试；真机仍为声学与 ColorOS 行为验收首选。
     adb 模拟器操作显式 `-s emulator-5554`；启动命令见 DEV_LOG 2026-08-17。
     **模拟器操作手册：根目录 `EMULATOR.md`**（agent 用模拟器前必读——启动/授权/UI 自动化/
     日志 tag/避坑，全部实测）。
3. **先读**：PROGRESS.md S8 两段 → DEV_LOG.md 2026-08-06/08-05 条目 →
   implementation-notes.html 最新四条（strip-empty/keyStopActive/wozai 选型/单容器拍板）→
   BLOCKED.md（仅余挂起/观察项）。（三者均在项目根，不在 docs/ 下）
4. **编排纪律**（CLAUDE.md 已增补）：异步管线票派单前置「守卫四问」；critic 派单标注
   代码正确型/语义验收型；builder 自报数字必附证据；真机操作必带 `.scratch/device.lock`；
   设备截图前页面身份文本锚断言+隐私裁切；adb 设备路径加 `MSYS2_ARG_CONV_EXCL="*"` 防 MSYS 改写。
5. **新需求走文档链**：CONSTRUCTION.md 票据制继续；spec 漂移即改 spec 并记 notes。

### 开工回执（任务 0）
- 目标：S0-S6 整盘——App=对话式助手主界面（平台 AG-UI），悬浮窗=手机精灵形态+气泡+「小朵小朵」语音半双工；终点线=功能全通+四组盲测不逊。
- 基线已核：BUILD SUCCESSFUL，单测 17 绿（6+5+6）skipped=0；真机 de61cd8 在线；凭据 json 可读（account/password 两键）。
- 切片顺序：S0 地基（gradle 归我）→ S1 排雷（消费器+真机首聊）→ S2 聊天/S3 悬浮窗并行 → S4 语音 → S5 KWS → S6 终评。
- 并行策略：纯逻辑波次（AguiSseConsumer / VadDetector / SentenceSplitter / TtsSanitizer / RevealScheduler）地界错开并行派 builder。
- 最大风险：AG-UI 首聊链路（443 自签+空 run 20%）；KWS 真麦校准（gate 单机值）；sherpa-onnx AAR 需从桌面仓取。

---

## 状态：M1 完成并收口 ✅（2026-07-30，偏差 A-D 全部裁决落地）

## M1 收口段（2026-07-30）

### 偏差裁决（用户拍板，详见 docs/dev-handbook/06、11）
- **A 窗口高度**：改 208（撤 80dp 气泡预留，M2 加气泡时 updateLayout 扩回）
- **B 长按菜单 / C 拖拽动画**：用户确认 07-30 grilling 交互集有效 → 修订决策 4/7 追认现状关闭
- **D 日志**：追认手写 PetLogger（不引 Timber），补滚动 7 天 + 设置页导出

### 实施与证据链
1. **窗口 288→208**：`PetOverlayService` 撤气泡预留位、`PetView.WINDOW_HEIGHT_DP=208`。
   真机 dumpsys 取证：`mAttrs={(397,770)(645x698) ... ty=APPLICATION_OVERLAY`，
   日志 `overlay window added, size=645x698px scale=0.84000003`（192×0.84×4=645 ✓ / 208×0.84×4=698 ✓）。
2. **日志滚动+7 天**：新增 `util/LogRotator.kt`（纯逻辑：按天命名/过期判定/非日志不动）+ PetLogger 接入
   （按天分文件 `xiaoduo-yyyy-MM-dd.log`、启动清理、跨天自动切文件）。
   真机取证：造 `xiaoduo-2026-07-20.log`（应删）/`xiaoduo-2026-07-25.log`（应留）/`notes.txt`（不应动），
   force-stop 重启后 ls = `notes.txt  xiaoduo-2026-07-25.log  xiaoduo-2026-07-30.log` ✓。
   旧单文件 `xiaoduo.log`（M1 遗产，证据已录本文件）已从真机删除。
3. **导出日志**：FileProvider（`@xml/file_paths`）+ 设置页「导出日志」按钮 → ACTION_SEND/MULTIPLE 系统分享面板。
   真机取证：点按后日志 `export logs, 1 file(s)`，`mCurrentFocus=...intentresolver.ChooserActivityLauncher` ✓。
   截图：`docs/m1-closeout-screenshot.png`（设置区含导出按钮）。
4. **U1 契约测试**：pet_states.json 进 assets（SHA256 `914b11d7e28daad01a32873a4db63abecdc5304a03bea50090d69dc183848e12`
   与上游一致），`PetFrameTableContractTest` 5 条（_meta 常量/default/逐项 row-frames-duration/无多余状态/行界合法性）
   直接读 `src/main/assets` 真实出厂文件，不复制镜像。新增 test 依赖 org.json:json:20240303。

### 构建与单测
`./gradlew.bat assembleDebug testDebugUnitTest` BUILD SUCCESSFUL；
单测 **17 个全绿**：PetFrameTableTest 6 + PetFrameTableContractTest 5 + LogRotatorTest 6（0 failures）。

### 验收口径
9 条按裁决后口径全绿（详见 docs/dev-handbook/07）。M1 剩余：U2-U6 增补单元（U4 剩 CrashReporter）。

---

## 状态：M1 完成 ✅（2026-07-29）

## 开工回执（任务 0）
- 目标：原生 Kotlin 单 App，App 内展示小朵帧动画 + 系统悬浮窗常驻，零网络；真机 adb 验收。
- 任务顺序：0 装环境 → 1 工程骨架 → 2 App 内动画+单测 → 3 悬浮窗 → 4 设置+文件日志 → 双硬指标取证。
- 最大风险（实际均未爆）：真机 adb 接入；测试机 ROM 未知（实测为 OPPO 系 ColorOS，PJD110）。

## 基线数字
- JDK 17.0.20（winget Microsoft.OpenJDK.17）✓
- C:\Android\Sdk：platform-tools 37.0.0 / platforms;android-34 / build-tools;34.0.0 ✓（全程代理 127.0.0.1:7897）
- adb 1.0.41 ✓；真机 `de61cd8`（PJD110 / OP5929L1，ColorOS，1440x3168 640dpi）✓
- 工程：Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.24，minSdk 26 / targetSdk 34，包名 com.xiaoduo.deskpet
- spritesheet.webp 原样复制（MD5 53c81ce652518f7fc7bf70554f363f54 与桌面端一致）
- 单测 6 个：tests=6 skipped=0 failures=0

## 硬指标 1：gradlew assembleDebug testDebugUnitTest 全绿
命令：`./gradlew.bat clean` 后 `./gradlew.bat assembleDebug testDebugUnitTest --no-build-cache --console=plain`
测试结果 XML：`<testsuite name="com.xiaoduo.deskpet.pet.PetFrameTableTest" tests="6" skipped="0" failures="0" errors="0">`
完整输出：

```
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:checkKotlinGradlePluginConfigurationErrors
> Task :app:generateDebugResValues
> Task :app:checkDebugAarMetadata
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:parseDebugLocalResources
> Task :app:extractDeepLinksDebug
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:javaPreCompileDebug
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:mergeDebugStartupProfile UP-TO-DATE
> Task :app:mergeDebugJniLibFolders
> Task :app:mergeDebugNativeLibs NO-SOURCE
> Task :app:stripDebugDebugSymbols NO-SOURCE
> Task :app:checkDebugDuplicateClasses
> Task :app:compressDebugAssets
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:mergeLibDexDebug
> Task :app:writeDebugSigningConfigVersions
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest
> Task :app:processDebugManifestForPackage
> Task :app:mergeDebugResources
> Task :app:mergeExtDexDebug
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics
> Task :app:processDebugJavaRes
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:mergeProjectDexDebug
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes
> Task :app:mergeDebugJavaResource
> Task :app:testDebugUnitTest
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 6s
41 actionable tasks: 40 executed, 1 up-to-date
```

## 硬指标 2：真机验收三条取证（2026-07-29 19:21，悬浮窗开启状态下连抓）

① `adb shell dumpsys window windows` 中的本 App overlay 窗口（768x1152 = 192x288dp @640dpi）：

```
Window #12 Window{d15282a u0 com.xiaoduo.deskpet}:
  mDisplayId=0 mSession=Session{23899f3 15729:u0a10166} mClient=android.os.BinderProxy@d3916cc
  mOwnerUid=10166 showForAllUsers=false package=com.xiaoduo.deskpet appop=SYSTEM_ALERT_WINDOW
  mAttrs={(336,618)(768x1152) gr=TOP START CENTER sim={adjust=pan} ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
    fl=NOT_FOCUSABLE NOT_TOUCH_MODAL LAYOUT_NO_LIMITS HARDWARE_ACCELERATED
```

② 截图：`docs/m1-screenshot.png`（adb exec-out screencap -p，App 内小朵与悬浮窗小朵同屏、动画帧不同，双渲染各自推进）。

③ 日志文件（`adb exec-out run-as com.xiaoduo.deskpet cat files/logs/xiaoduo.log`）含启动、权限结果、悬浮窗生命周期：

```
2026-07-29 19:17:25.523 I/MainActivity: onCreate, app started
2026-07-29 19:17:25.624 I/MainActivity: onResume, canDrawOverlays=true
2026-07-29 19:19:26.091 I/MainActivity: start overlay service
2026-07-29 19:19:26.099 I/PetOverlay: onCreate, building overlay window
2026-07-29 19:19:26.160 I/PetOverlay: overlay window added, size=768x1152px scale=1.0
2026-07-29 19:19:26.162 I/PetOverlay: onStartCommand
2026-07-29 19:19:29.030 I/MainActivity: stop overlay service
2026-07-29 19:19:29.038 I/PetOverlay: onDestroy, removing overlay window
2026-07-29 19:21:08.427 I/MainActivity: start overlay service
2026-07-29 19:21:08.494 I/PetOverlay: overlay window added, size=768x1152px scale=1.0
```

真机交互实测（19:12-19:13 日志）：点击悬浮小朵 → `overlay pet clicked -> waving once`×8；拖拽 → `drag start / drag end at (x,y)`×4 均正常跟随；App 内开关关闭 → `onDestroy, removing overlay window` 窗口消失。

## 设计偏差记录（implementation notes）
1. **拖拽不切 FLAG_NOT_TOUCHABLE**：知识包 §8 建议「拖拽时切 flag」。分析+真机验证：手势进行中给本窗口加
   FLAG_NOT_TOUCHABLE 会截断后续 MOVE/UP 事件、拖拽冻结。Android 12 不受信任触摸限制针对的是 overlay
   遮挡**下层**窗口，宠物大小窗口自身手势不受限——故拖拽期间保持可触摸随手指移动窗口。真机拖拽 4 次全部正常。
2. ColorOS 禁止 shell 授予悬浮窗权限（`appops set` SecurityException）也禁止 `am startservice` 拉起非导出服务
   （"Requires permission not exported"）——权限授予与悬浮窗开启均走 App 内真实用户路径完成，与产品设计一致。
3. `gradle/wrapper` 的 distributionUrl 指向本地 `file:///D:/gradle-dist/gradle-8.9-bin.zip`（防代理变动导致
   wrapper 下载失败）；依赖下载走 gradle.properties 的 systemProp 代理四项。
4. git 仓库与 wayfinder 规划会话共用，任务 1 骨架文件被并行会话 ticket-07 提交卷入 commit 7a67054（内容完整）。

## 下一步（M2 候选）
- 气泡 UI（show_bubble 配置已落，窗口 80dp 气泡位已预留）
- Kotlin providers（Ollama NDJSON / AG-UI SSE / SenseVoice / CosyVoice，契约见知识包 §2）
- 键盘输入对话

## 接手指引
先读本文件 → 桌面知识包 `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（只读）。
构建：`$env:PATH` 加 JDK17 + `local.properties` 已配 sdk.dir；真机 de61cd8。
