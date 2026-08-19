# CONTEXT.md — 小朵 Android 版领域术语表

> 只放领域词汇与共识语义，不放实现细节与决策理由（决策见 `docs/adr/`）。
> 权威上游：桌面端知识包 `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（只读引用）。

## 角色与本体

- **小朵（Xiaoduo）**：桌宠角色本体。视觉资产为 spritesheet（1536×1872，帧 192×208，8 列 × 9 行），9 个状态行。
- **精灵状态行（Sprite State Row）**：spritesheet 的一行 = 一个状态的全部帧。九行：idle / running-right / running-left / waving / jumping / failed / waiting / talking / review。状态切换时动画重置从头播。
- **宠物窗口（Pet Window）**：悬浮窗中承载小朵的系统窗口，`TYPE_APPLICATION_OVERLAY`，尺寸 = 宠物尺寸 × scale。

## 形态与界面

- **独立 App**：Android 应用本体，含主界面（内嵌小朵 + 设置项）与权限引导。与悬浮窗共用同一精灵渲染器与状态源。
- **悬浮窗（Overlay）**：系统级悬浮层上的小朵，依附于 PetOverlayService。
- **气泡（Bubble）**：宠物正上方的对话文本区，max-height 74px，打字机 40ms/字，总开关 `show_bubble`。气泡显示用原句，朗读用清洗句（听觉/视觉分离）。
- **手势集**：拖拽（移动窗口）/ 单击（挥手）/ 长按（菜单：打开设置、隐藏悬浮窗）。
- **会话（Session）**：一段对话的组织单元，含标题、时间与消息列表；多会话并存，是用户管理对话的**唯一概念**。
- **消息（Message）**：会话内单条 user/assistant 往返。所谓「聊天记录」即某会话的消息列表，**不作为独立实体存在**——看历史＝进入该会话，删除＝删除整个会话（侧栏长按）。独立的「聊天记录」页为单会话时代（契约 R2）遗留，已废弃。
- **全屏语音模式（Fullscreen Voice Mode）**：沉浸式全屏界面，纯语音、唤醒词驱动（无按住说话、默认无转写文字，设置可开）；进入时**隐藏悬浮窗**，与悬浮窗模式**互斥**但**共享同一 KWS 与语音链路**，仅回复呈现载体不同（全屏＝小朵 talking 动画+TTS 无文字 vs 悬浮窗＝气泡文字+TTS）。

## 对话与语音链路

- **prefer=local**：Ollama LLM + 直连 ASR/TTS 引擎的链路，全程免登录免 token。
- **prefer=platform**：平台 AG-UI Agent 链路，需登录取 36 字符 UUID token（三接口通用，客户端缓存 25 分钟）。
- **AG-UI**：平台 Agent 的 SSE 流式协议。必须走 443 nginx 反代；增量发消息（只发最新一条用户消息，绝不能重发全量历史）。
- **流式消费三件套**：① 校验终止帧（RUN_FINISHED / done），干净断流必须显式报错；② 空 run 自动重试（累计 0 字，共 3 次）；③ MESSAGES_SNAPSHOT 兜底补尾部。流式协议消费铁律。
- **分句（Sentence Splitting）**：LLM token 流逐字符喂 splitter，句末切点 `。！？!?;` + `\n`，SENTENCE_MIN_CHARS=4 / MAX=30；喂 TTS 的 text 数组每元素一句（整段单元素 = 伪流式）。
- **TTS 消毒**：合成前逐字符白名单清洗 markdown/emoji；清洗后无 alnum 字符则跳过合成。
- **VAD**：绝对阈值 RMS 语音检测，无校准期。speech_level=0.05、判起 300ms、判停 800ms、pre-roll 300ms；pre-roll deque 容量必须 = 判起块数 + pre_roll 块数 = 6。
- **半双工**：对话期间三层防自激——状态机分发（processing/speaking 丢麦块）+ KWS pause flag + 单条采麦流复用（KWS 与 VAD 共用）。
- **KWS**：唤醒词「小朵小朵」，sherpa-onnx KeywordSpotter，keywords_threshold=0.5，RMS 能量门控 0.015（单机校准值，须可配置 + 留校准入口）。
- **唤醒应答（Wake Ack）**：KWS 唤醒成功后小朵的固定应声「我在」（预录 wozai.wav，仅 KWS 唤醒路径播放，按住说话不播，可被打断（barge-in））。2026-08-07 用户拍板起不含前导「哎」（同日早前「哎,我在。」调研选型已废弃）。
- **Reveal（边念边出字）**：按句级 PCM 精确时长 `len(pcm)/2/24000` 均分逐字显示；播放阻塞写天然背压；TTS 失败降级纯文字 4.5 字/秒。

## 状态机

- **四态**：idle / listening / processing / speaking。麦块分发：idle 喂 KWS，listening 喂 VAD，其余丢弃。
- **CONVERSATION_VISUAL**：对话状态 → 精灵行映射：listening→waiting，processing→review，speaking→talking，idle→idle。别名：auto→jumping，sleeping→idle，running→idle。

## 工程面

- **内网**：10.0.18.x 网段。Ollama=10.0.18.24:11434，ASR=10.0.18.97:9802，TTS=10.0.18.97:9803，平台=10.0.18.166（8080 登录 / 443 反代）。内网地址必须绕系统代理直连。
- **知识包**：桌面端仓库的 `docs/android-dev-brief.md`，本项目的唯一上游启动文档，含 55 条踩坑经验。
- **参数对账表**：语音链路全部参数的单一事实源（`docs/voice-param-reconciliation-2026-07-28.md`），第三栈（Kotlin）实现逐项对照验收，防双栈漂移。
- **文件日志设施**：release 无 console，日志必须文件化 + 真实错误透传 UI，catch 不允许静默吞。
