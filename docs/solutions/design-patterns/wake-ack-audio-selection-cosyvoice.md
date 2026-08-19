---
title: 语音助手唤醒应答音选型——调研先行（原厂=语气词非陈述句）+ CosyVoice 标点是语调遥控器 + 真机试听拍板
date: 2026-08-06
last_updated: 2026-08-18
type: solution
category: design-patterns
module: app/src/main/assets/wozai.wav
problem_type: design_pattern
component: assistant
severity: medium
applies_when:
  - "为语音助手设计/替换唤醒应答音（「我在」类短反馈音）"
  - "用 CosyVoice（或同类 TTS）合成需要特定语调的短句"
  - "预录提示音听感异常（被掐头/尾字突切/不够响亮）需要重制"
tags: [wake-ack, tts, cosyvoice, voice-design, audio-pipeline, wav-normalize, xiaomi-research, user-listening-test, multi-voice, prefetch-cache, python-kotlin-parity]
---

# 唤醒应答音设计：语气词优先、标点控语调、真机试听定稿

## Context

小朵唤醒应答音初版为 TTS 合成的陈述句「我在」，真机用户反馈三连：「在字很短声音很小」「突然中断的感觉」「不够响亮」。分层排除（文件层 vs 播放层）证明播放管线无罪——`VoicePlayer` 写全量 + 播放头等待 + 50ms 收尾；病灶在**音频内容本身**：0.45s 头静音 + 「在」尾字被合成器陡切 + 陈述句语调天然平淡。内容选型问题不能用工程手段修，需要重新选型。

## Guidance

**1. 调研先行，别自己发明应答音。** 主流厂商唤醒应答不是陈述句朗读：

- 小米小爱同学原厂资源 `wakeup_ei_01.wav` = 一声轻快的「**哎**」（语气词），不是「我在」陈述句。
- 语气词应答（哎/嗯/诶）的优势：无字尾可切、语义中性不抢话、语调天然上扬示意「我在听」。陈述句「我在」的「在」是去声短促字，合成器极易把韵尾切陡，听感=被掐。

**2. CosyVoice 的标点是语调遥控器。** 同一文案加不加逗号、句号、叹号，产出的语调/停顿/重音显著不同。选型时对**标点变体**（「哎」「哎，」「哎，我在。」「哎！我在」）批量合成比调 speed 参数更有效。speed 只控整体速率（0.95 微慢显稳，0.85/0.75 用户嫌拖），不控语调形态。

**3. 重制管线四步**（脚本口径在 `.scratch/wozai/`）：

```
TTS 合成（10.0.18.97:9803, serviceName=cosyvoice3, speaker=default）
  → wav 包络分析（RMS / 0.05s 窗，找真实起止点）
  → 头尾静音修剪（治本版 0.45s 头静音即此步砍掉）
  → 峰值归一（本例归一到 26000，治「不够响亮」）
  → 补 44B RIFF 头落地 assets/wozai.wav（PCM mono 24k 16bit）
```

**4. 真机试听拍板，两轮收敛。** PC 上听与手机外放听感差异大；候选推 `/sdcard/Download/` 用真机播。本轮两轮 13 候选（文案×标点×speed 矩阵），用户第一轮全否（「都不符合预期」）→ 调研转向语气词 → 第二轮 e3「哎,我在。」(speed 0.95) 当选。注意 Git Bash 合成中文 body 有 GBK 编码坑，用 Python urllib 全链路绕过。

## Why This Matters

应答音是唤醒链路用户感知的第一个触点，它的听感直接定义「唤醒成功率」的主观判断——本仓实证：「我在」听感异常诱发用户重复喊唤醒词，重复喊又撞 strip-empty 死路（见关联篇），两个缺陷叠加放大成「唤不醒」主诉。单修工程缺陷不修内容，主诉不会消失。

## When to Apply

- 任何语音助手/桌宠的唤醒确认、操作反馈短音设计。
- TTS 合成短句听感异常时：先分层（文件层包络分析 vs 播放层日志）再动刀。
- 预录音频进 assets 前的标准化处理（修剪+归一）。

## Examples

本轮选型记录（2026-08-06）：初版「我在」（48044B，头静音 0.45s+尾切）→ 修剪归一版（39884B，仍陈述句语调不达预期）→ 调研后语气词方向 → **e3「哎,我在。」52320B PCM，speed 0.95，去静音+峰值归一 26000**，真机确认听感提升定稿。工程侧注意（2026-08-18 修订）：选型期曾有「唤醒成功 + strip-empty 纯唤醒词重听」两个播放调用点；strip-empty 重听路径已于 2026-08-10 回退为回 IDLE（KWS 接管），现仅剩唤醒成功一个调用点（`playAckThenListen`）——换音频文件不用改代码的结论不变。

**2026-08-07 更新**：用户改判去前导「哎」，wozai.wav 重制为纯「我在」。双路线候选：
- a) 平台 TTS 重合成「我在」（cosyvoice3/default，speed 0.95/1.0），音色与在线链路一致；
- b) 包络分析找 e3「哎,我在。」中「哎，」与「我在」间静音缝（@0.31s/220ms）裁剪，保留现版音色。
用户真机试听选定 **b-trim（25920B PCM，0.54s，24k/mono/16bit，峰值归一 26000）**。这说明选型不是一次性终局：用户主观偏好会随产品上下文回摆，后处理管线（包络分析/静音缝定位/归一/RIFF 头）保持复用价值。

**2026-08-18 增补（S9 应答音色矩阵化+动态缓存）**：烤死单资产在「TTS 三厂商可配」时代变成错配源——开箱默认（cloud+aliyun/longanfengyue）下应答就与对话不同人。解法=**四层兜底链**（filesDir 动态缓存 → 厂商默认资产 → wozai.wav → 直进聆听）+ 三条硬纪律：
1. **唤醒应答是即时反馈，播放路径零网络**——运行时合成只能发生在预取 daemon（保存即合成+启动补漏+失败退避 10min），播放侧 `DynamicWakeAck` 只持 resolve 闭包形成结构性守卫（类依赖无 HTTP 客户端，可被代码审查证伪）。
2. **后处理口径跨语言逐字节对拍**：Python 生成管线（tools/wozai/common.py）与 Kotlin 运行时管线（WakeAckAudio）参数逐项对齐（win=240/th=max(peak*0.02,60)/头 3 窗尾 4 窗/归一 26000/**Math.rint 半偶对齐 Python round**），黄金向量夹具逐字节断言锁死（夹具确定性生成后冻结，sha1 入档）。舍入法漂移（rint vs roundToInt）人耳不可辨但会让缓存与资产字节分叉——对拍夹具是唯一防线，且它与手工半偶单测互补（夹具无 .5 平局样本时 rint 变异夹具不红）。
3. **默认值单一真理源**：厂商默认 model/voice 常量（TtsVoiceDefaults）由 NetConfig 与选择器共享——选择器抄字面量必随默认值变更静默错配（本 bug 的镜像）。预取合成复用对话链同一函数（CloudClientFactory.ttsSynthesize），「预取音色=对话音色」由构造保证而非配置同步。

**实现结构（2026-08-18 第二次增补，行号对 9585afa 时点，包 `com.xiaoduo.deskpet`）**：
- 兜底链纯逻辑 `app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckSelector.kt:88-96` `select()`：L91 platform→wozai.wav 永不读缓存；L93 恰等于厂商默认三元组（provider+model+voice 全等，`isBundledDefault` L69）→`wozai-{provider}.wav` 资产；L94 缓存命中→`PlayCache`；L95 厂商资产；L96 →wozai.wav/Mute。Decision 密封 L44-46（PlayAsset/PlayCache/Mute）。
- 预取 `app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckPrefetcher.kt`：caller 线程 CAS 占位（L43 `inflight.compareAndSet`，零 IO）→daemon Thread（L45）内 needsPrefetch→backoff→`CloudClientFactory.ttsSynthesize(app, TtsVoiceDefaults.TEXT)`（L70，与对话链同一函数）→validate→process→tmp+ATOMIC_MOVE 原子写；finally 清单飞标志（L49-50，S9-3 回归守卫）。触发点=session() 装配尾+设置保存后；**每次 session() 触发是有意的自愈重试**（冷启动无网后 backoff 窗过随下次语音交互补合成，无需重启）。
- 播放 `app/src/main/java/com/xiaoduo/deskpet/voice/DynamicWakeAck.kt:18`：`class DynamicWakeAck(private val resolve: () -> ByteArray?)`——只持解析闭包即零网络结构守卫；L26 入口 isCancelled 早退、L27 resolve 落空抛 IOException 走既有 catch 降级进聆听、L34 注册后复查补枪。VSM 零 diff（接口/cancel 顺序原样）。
- 常量 `app/src/main/java/com/xiaoduo/deskpet/net/TtsVoiceDefaults.kt:15-25`：三家 PROVIDER/MODEL/VOICE 常量+`TEXT="我在"`，NetConfig 默认值引用同源。
- 缓存 `app/src/main/java/com/xiaoduo/deskpet/voice/WakeAckCacheStore.kt`：filesDir/wake_ack/v2/，坏读删返 null，旧版本目录清理用 `Regex("v\\d+")` 白名单（防误传 filesDir 清空兄弟）。
- 验收范式：可听性走真机三链（source 日志行+缓存 run-as 拉回复听+用户拍板），冷路径（预取/缓存/回退）先在模拟器全验再上真机。详见 `docs/verification/v15-wakeack-voice-follow.md`。

**陷阱与弯路（本切片实证）**：
- **跨票互相指望=交付物成孤儿**：S9-1 生成 parity 夹具、S9-2 票面写对拍测试——两票都以为对方写消费侧，132KB 夹具零消费者被两位 critic 独立抓住。跨票接口件必须在出票时写明归属票号。
- **探针先行省掉猜测**：aliyun qwen-audio-3.0-tts-flash 对 2 字「我在」能否出声、gpt-4o-mini-tts 在 dashscope compatible-mode 是否 404、VOLC key 是否为空——三条未知都在开工首票实跑探针定谳，而不是写完代码再发现。资产暂缺不是失败：兜底链设计使「缺一层」照常工作，key/端点到位补跑一条命令闭环。
- **真机 UI 自动化三坑**（Compose 设置页）：①单选 label 非点击区（圈选在 label 左侧）②退格 keyevent 连发被远端合并吞（须带 sleep 间隔）③滚动 fling 漂移致 dump 锚点失效（tap 前必新鲜 dump，慢拖无惯性）。
- **掩码输入框会吞误触**：测试串误打进掩码 X-Api-Key 框无法从 dump 察觉——意外换来「key 无效 401」失败变体的真机验证（与模拟器「未配 key」变体互补），但更稳妥做法是 SP 回读验证每步配置。

## Related

- `docs/solutions/logic-errors/strip-empty-wake-word-dead-end-replay-ack.md` — 同批修复②：应答音听感异常诱发重复唤醒，撞上该死路放大主诉。注（2026-08-18）：其描述的「纯唤醒词重播我在」行为已于 2026-08-10 整体回退为回 IDLE（KWS 接管），该篇行为描述属 2026-08-05~08-09 时段史实。
- `docs/solutions/design-patterns/per-modality-multi-cloud-native-ws-clients.md` — 前提架构：TTS 三厂商可配正是烤死资产错配的成因（本篇 2026-08-18 增补节的因果上游）。
- `docs/solutions/design-patterns/tts-synthesis-playback-pipeline-channel.md` — 思想同族：对话侧逐句预合成隐藏延迟与本篇应答侧预取缓存，是同一「即时反馈不许等网络」思想的两场景应用。
- `docs/solutions/logic-errors/volcengine-asr-binary-frame-completion-flags.md`、`dashscope-asr-task-field-tuple-unpack.md` — 方法论亲缘（跨语言移植必须对拍实证），与本篇金向量对拍同纪律。
- 调研原始结论与候选矩阵见 `implementation-notes.html` 2026-08-06 设计决策条（wozai 选型）。
