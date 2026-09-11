# Spec：小朵助手化（对话式 App + 语音悬浮窗）

> **【2026-08-03 注】v1 快照（S0-S4 时点）已封存**。其后导航改判（抽屉式）、N4 多会话
> （推翻 R2）、TranscriptSink v2 流式上屏、BubbleOwnership 气泡所有权等均已落地，
> as-built 以代码 + `DEV_LOG.md` + `implementation-notes.html` 为准；下轮大改前再整体刷新本 Spec。
> **【2026-08-06 补充】S5-S8 亦已收口**：S8-5 reveal 退役（气泡流式直出，下文 L27/L70 行内已注）、
> 唤醒应答音重制为「哎,我在。」、多功能键改 keyStopActive 状态源、输入框单容器化（S8-1 后注）。

## 来源

需求规划：`docs/plans/2026-07-30-002-feat-xiaoduo-assistant-app-plan.md`（KD9-KD16 / R1-R10）
协议字段级契约：桌面知识包 `docs/m1-dev-kit/android-dev-brief.md`（**唯一事实源，本 Spec 不复制字段，只指节号**）
语音参数：`docs/voice-param-reconciliation-2026-07-28.md`（逐行对账，实现对照验收）
版本：v1（2026-07-30 初始）｜ 维护纪律：实现偏离时改代码或改本文件 + 记 implementation-notes.html

## 技术方案

### 架构分层

```
┌─ UI 层（Compose + Material3，KD11）─────────────────────┐
│  ChatScreen（主页）/ SettingsScreen / PermissionScreen    │
│  PetView（Canvas 自绘，AndroidView 嵌入，不动）            │
├─ 状态层（ViewModel + StateFlow）─────────────────────────┤
│  ChatViewModel / OverlayController / VoiceSessionManager  │
│  ConversationState: idle/listening/processing/speaking    │
├─ 领域层（纯 Kotlin，JVM 单测，KTD3 模式）──────────────────┤
│  AguiSseConsumer（三件套状态机）/ SentenceSplitter         │
│  VadDetector / TtsSanitizer / RevealScheduler(已退役,S8-5)   │
│  PetFrameTable（已有）/ LogRotator（已有）                 │
├─ 数据/IO 层──────────────────────────────────────────────┤
│  AguiClient（OkHttp SSE）/ AsrClient / TtsClient           │
│  NetConfig + TokenStore（SharedPreferences，KTD2）         │
│  ChatStore（历史持久化）/ PetLogger + CrashReporter         │
├─ 系统层──────────────────────────────────────────────────┤
│  PetOverlayService（双窗口）/ VoiceForegroundService       │
│  AudioCapture（单条采麦流，KWS/VAD 复用）/ KwsEngine        │
└──────────────────────────────────────────────────────────┘
```

铁律：领域层**零 Android import**（照 FrameAnimator/LogRotator 模式），协议/参数逻辑全部可 JVM 单测。

### 数据模型

| Model | 字段 | 说明 |
|---|---|---|
| ChatMessage | id / role(user,assistant,system) / text / status(sending,streaming,done,error) / ts | 单流水；status 驱动 UI 反馈（R1） |
| NetConfig | platformBase / asrBase / ttsBase / account / password | 全部可编辑 + 内置默认；password 无默认（R4） |
| TokenEntry | token(36-char UUID) / obtainedAt | 缓存 25 分钟，过期重登（知识包 §2） |
| VoiceSession | state 四态 / partialText / sentenceQueue | 半双工状态机载体（R6） |

### AG-UI 链路（R3，契约细节以知识包 §2 + PLATFORM_INTEGRATION.md 为准）

1. 登录：POST {platformBase}:8080 登录接口 → 36 字符 UUID token，缓存 25 分钟（三接口通用）
2. 对话：POST {platformBase}:443 反代 AG-UI SSE；**增量单条用户消息**，消息 id 全局唯一、重试复用同 id
3. AguiSseConsumer 三件套（纯 Kotlin 状态机，逐字节喂入）：
   - 未见 RUN_FINISHED = 断流 → 显式 error，绝不伪装 0 字成功
   - 累计 0 字 = 平台空 run → 同 id 自动重试，共 3 次
   - MESSAGES_SNAPSHOT 末条 assistant 文本长于已产出 → 补尾部（content 兼容 string 与分片）
4. OkHttp 工厂（KTD4 沿用）：内网 NO_PROXY 直连 + 自签证书 host 白名单 TrustManager

### 语音链路（R6-R8，参数逐项照对账表，禁止凭记忆写）

- 采麦：单条 AudioRecord 流（VOICE_COMMUNICATION 优先，硬件 AEC），协程 channel 分发；音频回调只推队列
- VAD：level≥0.05 / 判起 3 块 / 判停 4 块【2026-08-29 提速，原 8】/ pre-roll deque 容量=6 / timeout=30s 显式传
- ASR：直连 10.0.18.97:9802，language 显式 "zh"，VAD 截尾防静音幻觉
- 分句：切点 `。！？!?;` + `\n`，MIN=4 / MAX=30，逐字符喂入；text 数组每元素一句送 TTS
- TTS：直连 9803（勿经 166 转发），多帧 WAV 剥头拼 PCM，先缓冲全帧再播；合成前白名单消毒
- 半双工：四态分发（idle→KWS 受 pause flag 门 / listening→VAD 主投+KWS 旁路【S33 重唤醒】/
  speaking·processing→KWS 恒进【S12 唤醒打断】）+ KWS pause flag
- KWS：sherpa-onnx-1.13.4.aar（app/libs，abiFilters arm64-v8a），模型 5 文件 assets newFromAsset；
  keywordsThreshold=0.3 **【2026-08-19 修订】**（08-10 真机验证值 IDLE 态 10/10，原 0.5 弃用；锚 `voice/KwsDefaults.kt`）；RMS 门控默认 0.015 **可配置 + 校准入口（硬验收）**
- reveal：`len(pcm)/2/24000` 句级均分逐字；播放阻塞写天然背压；失败降级 4.5 字/秒
  **【2026-08-05 撤销】S8-5 改流式直出**（delta 回调直接气泡上屏，不等音频），reveal 退役仅留类备查

### UI 结构（Compose，参照物截图 docs/research/）

- ChatScreen：顶部小朵状态条（PetView 小尺寸 + 状态文案）→ 消息流 LazyColumn（Markdown 气泡）→
  底部输入条（参考小布：按住说话为主按钮 + 键盘切换 + 发送）
- SettingsScreen：端点/账号（R4）/ 缩放 / 气泡开关 / KWS 门控与校准 / 导出日志（已有）/ 版本号
- PermissionScreen：悬浮窗（已有）+ 麦克风 + 通知 + 电池豁免四项清单（吸收 KTD6 缺口）+ ROM 说明位
- 深色：Material3 dynamicColor 关闭，自定义色板双色；浅色对标 ChatGPT 的留白，深色对标其纯黑

### 悬浮窗双窗口（R5，实证自手机精灵，docs/research-ref-jingling-launcher.png）

- 交互层：宠物尺寸、可触、alpha 1.0，**渲染 PetView + 承载手势集 KD15**
  （2026-07-30 职责翻转：原设计 PetView 在显示层，因 Android 12+ 强制 NOT_TOUCHABLE 窗
  alpha≤0.8 致宠物半透明而翻转，见 implementation-notes 决策条）
- 显示层：气泡专用容器，`NOT_TOUCHABLE`（系统压至 0.8 alpha 对半透气泡无感）；
  气泡 attach 精灵正上方（§4.2：max-height 74px / 圆角 10px /
  rgba(255,255,255,0.92) / 11px×font-scale / 打字机 40ms/字）+ X 向 clamp 到屏内
- 位置持久化（clamp 纯函数可单测）+ 总开关持久化 + 杀进程恢复（吸收 U2）

## 边界条件

| 场景 | 处理 |
|---|---|
| token 过期（25min） | 透明重登 1 次，失败显式报错进设置页 |
| SSE 断流无终止帧 | error 状态 + 重发按钮（不自动重发非空 run） |
| 空 run ×3 | 报错「平台繁忙」+ 保留用户消息可重发 |
| 内网不可达 | 连接超时 5s，错误透传真实 msg（知识包 #43/#44） |
| KWS 误唤醒 | 门控校准入口 + 误唤醒日志（校准数据） |
| TTS 单句合成失败 | 该句降级纯文字 reveal，后续句继续 |
| 无 alnum 清洗句 | 跳过合成仅显示（知识包铁律） |
| 息屏/退后台 | 前台服务保采麦；悬浮窗动画降帧（M4 再优化） |

## 性能要求

| 指标 | 目标 |
|---|---|
| AG-UI 首 token | ≤3s（内网，对齐桌面端观测） |
| ASR→LLM 送出延迟 | ≤500ms（VAD 判停后） |
| TTS 首句出声 | ≤2s（分句首句达 MIN 即送） |
| 帧动画 | 锁 §4.1 帧表，Choreographer 驱动不掉帧 |

## 安全考虑

| 风险 | 防护 |
|---|---|
| 凭据泄露 | password 仅 SharedPreferences + `.scratch/secrets/` gitignore；日志严禁打 token/密码（评审抽查点） |
| 自签证书 | host 白名单 TrustManager，勿全局放行（G1） |
| 麦克风合规 | FGS 类型声明 + 用户可见动作拉起 + 常驻通知 + 指示点不可隐藏 |

## 下一步

施工清单：`CONSTRUCTION.md`（S0-S8 切片，2026-08-06 全部收口）；执行提示词由 leader 流程产出（07-30 会话交付）。
