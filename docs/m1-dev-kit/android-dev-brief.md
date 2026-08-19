# desk-pet Android 开发知识包

> **定位**：Android 版（原生 Kotlin）开发的唯一启动文档。汇总桌面端可复用资产、
> 四协议字段级契约、语音链路工程参数、全量踩坑经验、Android 专项约束与 M1-M4 路线。
> 写作日期：2026-07-28。权威来源：桌面端仓库 `E:\desk-pet-migration\desk-pet`
> （协议细节以 `docs/PLATFORM_INTEGRATION.md` 为准，本文是它的 Android 视角编排）。

## ⚠️ 开工前两个产品决策（须用户先答）

1. **目标用户的手机能否接入内网**（10.0.18.x 同网段/VPN）？平台服务全在内网，
   443 自签证书需 networkSecurityConfig 白名单（勿全局放行）。
2. **能否接受合规体验成本**：永久前台服务通知 + 麦克风指示点 +
   引导用户开 4-5 个系统权限（悬浮窗/麦克风/电池优化豁免/自启动/通知）？

---

## 1. 项目背景与形态决策

桌面端「小朵」：Windows 桌宠（Tauri Rust + WebView 双窗口 + Python sidecar 对话子进程，
MSI 打包分发），语音/键盘对话走内网服务（Ollama LLM / SenseVoice ASR / CosyVoice TTS /
平台 AG-UI Agent）。

**Android 形态（2026-07-24 多端调研结论，全文 `docs/research-multiplatform-2026-07-24.md`）**：
- **Tauri 对本产品不适用**：v2 虽支持 Android，但无 overlay/always-on-top/多窗口概念
  （issue #12140），宠物渲染必须脱离 Tauri 窗口体系。
- **Python sidecar 不可迁移**：Chaquopy wheel 仓库无 sounddevice，PortAudio 无 Android 后端。
- **业界桌宠 App 清一色原生 Kotlin + WindowManager overlay**（Shimeji、Live2DViewerEX）。
- 结论：**原生 Kotlin 单 App**（PetOverlayService + VoiceService + Kotlin providers +
  设置/权限引导页）。可带走资产：spritesheet 素材与状态映射、平台协议文档（价值最高）、
  KWS 模型、交互设计；语音大脑 Kotlin 重写（均为 ~100 行内纯逻辑，参数本文已列全）。

---

## 2. 可复用资产与协议契约（Kotlin 重写蓝图）

> Kotlin 侧建议复刻的接口面（`pet/chat/providers/base.py:34-91`）：
> `LLMProvider.stream(): Flow<String>` / `ASRProvider.transcribe(bytes): String` /
> `TTSProvider.synthesize(): Flow<ByteArray>`（统一流式/整段为序列）。

### 2.1 Ollama LLM（prefer=local 主路径）

| 项 | 契约 |
|---|---|
| 端点 | `POST {base}/api/chat`（base 须剥掉 `/v1` 后缀） |
| 请求体 | `{model, messages, think:false, options:{num_ctx:2048, num_predict:100}, stream:true}` |
| messages | `[{role:"system",content:system_prompt}] + 全量历史`（system 置首条） |
| 认证 | 无（内网 Ollama） |
| 超时 | connect 10s / read 120s |
| 流式格式 | **NDJSON**（非 SSE）：每行一个 JSON，取 `message.content` 增量，`done==true` 终止 |
| 关键参数 | `think:false` 关隐藏思考（首 token 50s→1s）；`num_ctx:2048` 防 VRAM 膨胀；`num_predict:100` 封顶防跑偏 |

### 2.2 平台 Agent LLM（AG-UI SSE，prefer=platform）

**登录/token**：`POST {login_url}` body `{"username","password"}` → 信封 `{code,msg,token}`，
`code==200` 取 36 字符 UUID token。三接口通用（Agent/ASR/TTS），客户端缓存 **25 分钟**
（RuoYi 30min 过期留 5min 余量）。

**AG-UI 流式对话**：
- 端点**必须走 443 nginx 反代** `https://10.0.18.166/ai-server/agent/stream`。
  **不能直连 :8080/agent/stream**（RuoYi 网关对未注册路由统一 401「路由不存在」假象）。
  443 自签证书 → trust 内网白名单。
- 请求头：`Accept: text/event-stream`、`Content-Type: application/json; charset=utf-8`、
  `agentId: {agent_id}`（在 header 不在 body）、`Authorization: Bearer {token}`。
- 请求体：
  - `threadId`：会话 id（服务端按它保存记忆）
  - `runId`：每次调用唯一（如 `deskpet_run_{毫秒}`）
  - `state:{}`、`tools:[]`、`context:[]`
  - `messages`：**增量模式——只发最新一条用户消息** `{id:"u_{毫秒}", role, content}`。
    **绝不能重发全量历史**（平台按 id+内容去重，重发污染 thread、空回复率 ~50%）。重试复用同一消息 id。
  - `forwardedProps: {protocolVersion:"0.1.0", source:"desk_pet"}`
  - system_prompt 请求体无法覆盖（平台侧配置），参数被忽略。
- SSE 事件序列：`RUN_STARTED → RAW×N(忽略) → STEP_STARTED → TEXT_MESSAGE_START →
  TEXT_MESSAGE_CONTENT×N → TEXT_MESSAGE_END → … → MESSAGES_SNAPSHOT → RUN_FINISHED`。
  - 行格式 `data: {json}`；主消费 `TEXT_MESSAGE_CONTENT` 的 `delta` 字段。
  - `MESSAGES_SNAPSHOT` 兜底：取最后一条 assistant 文本（content 可能是 string 或
    `[{type:"text",text}]` 分片），比已产出长则补尾部。
  - `RUN_FINISHED` 正常终止；**累计 0 字 = 平台随机空 run（实测率 ~20%）→ 自动重试共 3 次**。
  - **流结束但未见 RUN_FINISHED = 中间层断流，必须显式报错**（否则伪装成 0 字成功）。
- 评价延迟看 TEXT_MESSAGE_START 不看 RUN_STARTED（模型思考未关时首 token 2.4-84s）。

**thread 会话管理**（前缀 = agent_url 去末段 `/stream`，全部 POST + Bearer + agentId 头，信封 code==200）：

| 功能 | 路径 | body | 响应 data |
|---|---|---|---|
| 创建 | `/createThreadId` | `{threadName, agentId}`（均必填） | `<32位hex threadId>` |
| 列表 | `/threadHistory` | `{pageNum:1,pageSize:500}` | `{list,total,...}`，行字段 `threadId/threadName/agentId/updateTime`，客户端按 updateTime 倒序 |
| 改名 | `/renameThread` | `{threadId, threadName}` | null |
| 删除 | `/deleteThread` | `{threadId}` | null |

陷阱：列表不按请求头 agentId 过滤（全用户返回）；存在 agentId 为字面量 `"agentId"` 的脏数据行。

### 2.3 SenseVoice ASR

| 项 | 契约 |
|---|---|
| 端点 | `POST {endpoint}`（默认 `http://10.0.18.97:9802/audio/asr/transcribe`） |
| 编码 | multipart/form-data |
| 字段 | `file`（文件名 `utterance.wav`，MIME `audio/wav`）、`language`（**必须显式 `"zh"`**，auto 会把中文误判为韩文/英文）、`serviceName`（**`"sensevoice"` 或 `"FunASR"`**，Apipost 旧示例的 SenseVoiceASR 是错的）、可选 `hotword` |
| 音频要求 | WAV ≤50MiB；客户端送 16kHz/16-bit/mono PCM 包 WAV 头 |
| 认证 | 引擎端不验令牌 |
| 超时 | 30s |
| 响应 | 信封 `{code,msg,data}`；`code==200` 取 `data.text`（strip）。空文本视为失败 |
| 模式 | 每句话一次无状态调用（VAD 判停后整段送）。**SenseVoice 对尾部静音产生幻觉文本——必须靠 VAD 截尾规避** |

### 2.4 CosyVoice TTS

| 项 | 流式（主路径） | 非流式（兜底） |
|---|---|---|
| 端点 | `POST {base}/audio/tts/stream` | `POST {base}/audio/tts/synthesize` |
| 请求体 | `{text:[text]（数组每元素一帧）, serviceName, speaker, speed, instruct?}` | 同上但 `text` 传**字符串** |
| 响应 | `application/x-ndjson`；每帧 `{"audioData":"<base64-wav>","format":"wav"}`；结束帧 `{"type":"done"}`；错误帧 `{"type":"error","code":500,"msg"}`；**预校验错误返回普通 JSON 信封（不进 NDJSON）** | `{code:200, data:{audioData:"<base64-wav>"}}` |
| 音频格式 | **24000Hz / 16-bit / mono，每帧完整 WAV（非裸 PCM）** | 同左 |
| 超时 | (10s, 120s) | 120s |

- 默认 base `http://10.0.18.97:9803`；`serviceName`：**`cosyvoice3` 或 `MeloTTS`**；
  `speaker` 默认 `"default"`（可选：doubao_sisi / ertonghuiben / girl_sweet_xiaomei_happy /
  sajiaoxuemei / sdsa / sisi_fear / tianxinxiaomei / yunzhou）；`speed` 默认 1.0（≤4）。
- 约束：单段 ≤2000 字、数组 ≤64 元素、总长 ≤20000 字。
- 失败码：`code=500` 常见于**无可发音字符**（纯符号/markdown 分隔行）→ 客户端先清洗（见 §3.1 消毒规则）。
- 降级：流式 404 → 永久记住走非流式；流式中途错 → 非流式兜底一次；
  **必须先缓冲流式全帧再播**（残帧 + 兜底全量 = 内容重复）。
- 转发层陷阱：经 10.0.18.166 的流式有 **30s 总时长超时**（长文本被截断无 done 帧）；
  直连 9803 无此问题。Android 直连内网 9803 即可，勿经 166 转发 TTS。

---

## 3. 语音链路工程参数（Kotlin 重写照抄）

### 3.1 TTS 增量分句 + 消毒（tts_orchestrator.py / sidecar.py）

| 参数 | 值 | 含义 |
|---|---|---|
| 句末切点 | `。！？!?;` + `\n` | SENTENCE_END_CHARS |
| `SENTENCE_MIN_CHARS` | 4 | 短于此不单独切（防碎音） |
| `SENTENCE_MAX_CHARS` | 30 | 无句末也强制切 |
| 长句切点回退 | 切口落在 ASCII 字母/数字中间（3.14159、GPT-4o、URL）时回退到词边界 | 防 TTS 读错 |
| 喂法 | LLM delta **逐字符**喂 splitter（splitter 只看 buf 末尾标点） | |
| 收尾 | token 流结束把残留作为最后一句 | |

**TTS 消毒**（朗读前）：剥除 markdown/emoji，保留白名单标点
`，。！？；：、,.!?;:「」『』""''"()（）【】[]《》〈〉—…·-–/~%℃°+` + 文字数字空白；
清洗后无 alnum 字符 → 跳过合成（不送服务端撞 500）；清洗含**压缩连续空白**一步（`split().join(" ")`，2026-07-28 对账补注，Kotlin 照做）。**气泡显示用原句，朗读用清洗句**。

### 3.2 PCM / 音频格式

| 链路 | 格式 |
|---|---|
| 麦克风采集（KWS + VAD 共用一条流） | 16kHz / Int16 / mono，块 100ms = 1600 样本 = 3200 字节 |
| ASR 输入 | 上述 PCM 包 WAV 头（16k/16-bit/mono） |
| TTS 输出 / 播放 | 24kHz / Int16 / mono；WAV 帧剥头后裸 PCM 直接写播放流 |
| KWS 内部 | Int16 → float32（/32768）喂 sherpa |

### 3.3 边念边出字 reveal

- 三线程管线：LLM 流式→分句（queue 4）→ 串行合成（queue 4）→ 消费（逐句 reveal ∥ 播放）。
- 每句精确时长 = `len(pcm)/2/24000` 秒，按 `duration/字数` 均分逐字显示；
  播放用阻塞 write = 按播放速度天然背压。
- 降级：TTS 单句失败或播放异常 → 纯文字按预估语速 **4.5 字/秒** reveal。
- 播放收尾等 `stream.latency + 0.05s` 再关流。
- Android 可在 Kotlin 内实现同等 reveal（协程 + AudioTrack 阻塞写天然背压）。

### 3.4 VAD 参数（绝对阈值 RMS，无校准期）

| 参数 | 值 | 含义 |
|---|---|---|
| `speech_level` | 0.05 | 归一化音量阈值 = RMS/8000 |
| `speech_start_ticks` | 3 块（**300ms**） | 连续超阈值判说话开始 |
| `silence_end_ticks` | 8 块（**800ms**） | 连续低于阈值判说话结束 |
| `min_utterance_ticks` | 3 块 | 更短当噪音丢弃 |
| `listen_timeout_s` | 30s（toggle 会话余量） | 未说话超时回 IDLE |
| `max_utterance_s` | 8s | 说话超时强制结束（防增益爬升噪声） |
| `pre_roll_ticks` | 3 块（**~300ms**） | 判起成立时补回触发前低电平句首段，**防丢首字** |
| 块 | 100ms @16k | |

行为细节：单次触发（utterance/timeout 后停，调用方重新 start）；未判起时不累积只滚动
pre_roll deque；**说话中只累积说话块、尾部静音不进 buffer**（送 ASR 干净防幻觉）。
**pre-roll deque 容量必须 = speech_start_ticks(3) + pre_roll_ticks(3) = 6**——
与判起同长则触发前保留实为 0（连续 3 块确认期音频含首音节，双栈漂移踩过两次，见 §7.7 #47）。

### 3.5 状态机与半双工

- 四态：`idle / listening / processing / speaking`。麦块分发：IDLE 喂 KWS /
  LISTENING 喂 VAD / processing+speaking **丢弃**。
- 半双工三层：状态分发 + KWS pause flag（对话期间暂停 KWS decode 防 TTS 自声误触发）+
  **单条采麦流复用**（KWS 与 VAD 共用，避免双流抢麦）。
- 多轮：TTS 播完回 LISTENING 继续听（重启 VAD + 30s 超时），超时/手动 toggle 回 IDLE。
- 唤醒词前缀兜底：ASR 文本若以 `小朵小朵/小朵/小度小度` 开头则剥除。

---

## 4. 状态机与素材（直接复用）

### 4.1 精灵动画

spritesheet：`pet/assets/xiaoduo/spritesheet.webp`（1536×1872，帧 192×208，8 列 × 9 行）。
**直接打包进 Android assets**，帧动画按下表驱动（行 Y 偏移 = -208×row）：

| 状态 | 行 | 帧数 | 帧时长 ms |
|---|---|---|---|
| idle | 0 | 6 | 1100 |
| running-right | 1 | 8 | 1060 |
| running-left | 2 | 8 | 1060 |
| waving | 3 | 6 | 900 |
| jumping | 4 | 5 | 840 |
| failed | 5 | 8 | 1220 |
| waiting | 6 | 6 | 1600 |
| talking | 7 | 6 | 720 |
| review | 8 | 6 | 1500 |

（无 sleeping 行，视觉退化为 idle。元数据 `pet/pet_states.json` + `pet/assets/xiaoduo/pet.json`。）

**对话状态→精灵行映射**（CONVERSATION_VISUAL）：
`listening→waiting`（等你说话）、`processing→review`（思考中）、`speaking→talking`、`idle→idle`。
别名：`auto→jumping`、`sleeping→idle`、`running→idle`。切换时重置动画从头播。

### 4.2 气泡交互

- 位置：精灵正上方，max-height 74px 超出裁掉不滚动；圆角 10px、半透明白底
  rgba(255,255,255,0.92) + 背景模糊；字号 11px × font-scale，pre-wrap。
- 打字机：40ms/字（25 字/s），新文本重置、流式 append 续打。
- 总开关 `show_bubble`（false 时一切弹泡 no-op 并立即隐藏）。
- 窗口布局：192×288（208 精灵 + 80 气泡）× scale——Android overlay 窗口按此 dp 开。

---

## 5. KWS 唤醒词（sherpa-onnx，官方 Android AAR 一等支持）

模型目录 `prototype/kws-model/`（**直接打包进 assets**）：

| 文件 | 大小 | 说明 |
|---|---|---|
| `encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx` | 4.6MB | int8 量化 |
| `decoder-epoch-13-avg-2-chunk-16-left-64.onnx` | 760KB | 未量化 |
| `joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx` | 87KB | int8 |
| `tokens.txt` | 2.2KB | 音素词表 |
| `keywords.txt` | 40B | `x iǎo d uǒ x iǎo d uǒ @小朵小朵` |

KeywordSpotter 参数（真麦校准值）：

| 参数 | 值 | 依据 |
|---|---|---|
| `keywords_threshold` | 0.5 | 0.25 静音误触发多，0.75+ 漏检 |
| RMS 能量门控 | 0.015 | 真麦静音 ~0.001-0.006 / 说话 ~0.036，居中；rms<gate 整块不喂不判 |
| 采样率 | 16000 直采直喂 | 不触发重采样省 CPU |
| 块大小 | 100ms = 1600 样本 | |
| `num_threads` | 2，provider="cpu" | |
| 用法 | `accept_waveform` → 循环 `is_ready/decode_stream/get_result` → 命中 `reset_stream` | |
| 门控 | 隐私开关 + 半双工两层，丢弃即不 decode | |

⚠️ gate=0.015 是单机校准值，Android 机型麦克风差异大——**做成可配置并留校准入口**。
FAR 兜底方案：换 silero-vad 前端。音频回调只推队列、decode 在消费线程（防 underrun）。

---

## 6. 配置面与内网端点

### 6.1 内网默认端点

| 服务 | 地址 |
|---|---|
| 登录 | `http://10.0.18.166:8080/login` |
| Agent | `https://10.0.18.166/ai-server/agent/stream`（443 nginx，自签） |
| ASR | `http://10.0.18.97:9802/audio/asr/transcribe` |
| TTS | `http://10.0.18.97:9803/audio/tts/{synthesize,stream}` |
| Ollama | `http://10.0.18.24:11434`（剥 /v1） |

### 6.2 15 项配置（Android 用 SharedPreferences/私有文件即可）

`PROVIDER_PREFER`(local|platform) / `LOCAL_LLM_BASE_URL` / `LOCAL_LLM_MODEL`(qwen3.5-9b:latest) /
`LOCAL_ASR_URL` / `LOCAL_TTS_URL` / `LOCAL_TTS_SPEAKER`(default) / `PLATFORM_AGENT_URL` /
`PLATFORM_LOGIN_URL` / `PLATFORM_USERNAME`(admin) / `PLATFORM_PASSWORD`（**空，无默认值**） /
`PLATFORM_AGENT_ID`(chatAgent) / `PLATFORM_ASR_URL` / `PLATFORM_TTS_URL` /
`ENABLE_CHAT`(true) / `DESK_PET_KWS_ENABLED`(1)

### 6.3 Android OkHttp 落地要点

- **自签证书**：自定义 TrustManager/HostnameVerifier，**仅限内网域名白名单**，勿全局放行。
- **代理绕过**：内网地址（10.0.18.166/97/24）必须直连（Proxy.NO_PROXY）——系统代理会把流式缓冲卡死（实测 TTS 经代理 read timeout）。
- **token**：Bearer 头 + 25min 客户端缓存续期；**引擎端 9802/9803 裸调不验令牌——
  prefer=local（Ollama+直连 ASR/TTS）时 Android 可完全免登录链路**，仅 prefer=platform 的 Agent/会话管理需要 token。
- **响应信封**统一 `{code,msg,data}`，`code=500` 的 `msg` 常带真实原因（可当排障文档）。

---

## 7. 踩坑经验（对 Android 相关性评级）

> 来源：`docs/implementation-notes.html` + `docs/DEV_LOG.md`（续1-46）+ `docs/PLATFORM_INTEGRATION.md` +
> voice-assistant 项目同引擎服务记忆（55 条全量挖掘）。**高 = Android 必踩/必防；中 = 设计参考；低 = 桌面端专属。**

### 7.1 TTS（CosyVoice / 链路编排）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 1 | **纯符号/markdown 必返 500**（wetext 规范化后无可发音字符；内容行带符号则连符号朗读）→ TTS 前逐字符白名单清洗，清洗后为空才跳过；听觉/视觉分离（气泡用原句，只净化合成输入） | 高（chatAgent 爱输出 markdown 表格，Kotlin 必须复刻清洗） |
| 2 | **166 转发层 30s 流式总时长截断**（伪流式逐段转 synthesize，6 段×5s 即掐断无 done；直连 9803 完整）→ Android 直连 10.0.18.97:9803 绕开 166 | 高 |
| 3 | **text 数组=逐句一帧真流式；整段单元素=伪流式**（120 字单元素首帧 ~17.8s vs 切句数组 ~2.5s；预校验错误走普通 JSON 信封不进 NDJSON）→ 客户端先分句再送数组 | 高 |
| 4 | **TTS 首音基线 p50 2.56s / p90 3.0s**；GPU 显存打满退化到 6-41s（关无关 GPU 模型即恢复）；预热前首次连接 6s 是瞬态 → 延迟预算里 TTS ~2.5s 是地板不是 bug | 高 |
| 5 | **分句是降首音核心手段**（远大于 speed 微调）：token 流按 `。！？` 切句首句即送；三线程管线 queue=4 背压 | 高（Kotlin 协程 channel 一一对应） |
| 6 | **断句标点宁少勿多 + 整段必须逐字符喂分句器**（`；;`/`\n` 触发过度切割碎片化；整段单 token 传入绕过句中标点）→ 仅 `。！？` + flush 阈值 200（⚠️ 2026-07-28 对账：本条「仅。！？+200」是 voice-assistant 项目历史形态；**当前桌面端 HEAD 权威 = §3.1 表**（`。！？!?;`+\n、MIN 4/MAX 30），Kotlin 按 §3.1 实现） | 高 |
| 7 | **多帧 WAV 直接 join 夹 RIFF 头**（播放器只播首帧）→ 剥头拼 PCM；流式先缓冲再播防残帧+兜底重复 | 高（AudioTrack 播 PCM 必须剥 WAV 头） |
| 8 | **无字级时间戳**：边念边出字按句级 PCM 精确 reveal（`len(pcm)/2/24000` 均分），跨句仍流水线 | 高（若做字幕同步） |
| 9 | **TTS 丢词三 root cause**：单句 500 清队列截断整轮 / 跨轮迟到回调插音 / 引擎幻读 → 失败句重试 1 次仍败跳过、轮次号挡迟到回调、时长/字数比告警 | 高（Kotlin 队列化同类坑） |
| 10 | **流式端点 ≠ 流式播放**（攒齐再播浪费全部流式收益）→ 边收边播/句级流水线 | 中 |
| 11 | **speed 1.15 听感偏快回退 1.0**——语速类参数必须用户主观确认 | 低 |
| 12 | **TTS 故障先验证链路归属再动手**（曾把平台 TTS 故障误重启本机服务）→ 错误日志必须带 base_url | 中 |

### 7.2 ASR（SenseVoice / VAD）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 13 | **language 必须显式 zh**（默认 auto 把中文误判韩文/英文）→ multipart 全链路透传并默认 zh | 高 |
| 14 | **尾部静音幻觉**（静音被 decode 出幻觉文本）→ VAD 说话结束处截断，送 ASR 音频不含长段尾部静音（只累积 speech 块） | 高 |
| 15 | **Apipost 文档 serviceName 是错的**：正确 ASR=`sensevoice`/`FunASR`、TTS=`cosyvoice3`/`MeloTTS`；code=500 的 msg 直接告诉你合法取值（错误 msg 当文档用） | 高 |
| 16 | **pre-roll 保首字，容量必须与判起块数错开**（连续 3 块确认期音频含首音节，deque 与判起同长则触发前保留实为 0）→ deque 容量 = speech_start_ticks(3) + pre_roll_ticks(3) = **6** | 高 |
| 17 | **绝对阈值 VAD 够用，别加自适应层**（SNR 自适应引入校准期失聪 + 噪声底污染；复杂度是 bug 温床）→ 最小三要素：level≥0.05 无校准期 + 只留说话块 + 800ms 判停 | 高（Kotlin 重写直接照抄最小解） |
| 18 | **判停 800ms**（中文句中停顿 200-500ms，400ms 会切句中停顿；800ms 是延迟 vs 切句权衡稳妥值） | 高 |
| 19 | **VAD 超时日志带「期间最大 level」一值定层**：≈0.0001 是数字静音流（系统层：设备选错/权限被拒/没开麦）；0.01-0.05 才是阈值/增益问题 → Android 上 RECORD_AUDIO 被拒完全同构 | 高 |
| 20 | **ASR 返回空不是异常**（无 traceback 静默继续，最难调的形态）→ 诊断日志打中间数据 + 留档 wav | 中 |
| 21 | **纯标点 ASR 结果（'。'）不送对话**（防噪音误触发对空气回话） | 中 |

### 7.3 LLM（Ollama / AG-UI 平台）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 22 | **OpenAI 兼容端点静默忽略 think 参数**（qwen3.5 白生成 ~1600 隐藏思考 token，首 token 25-75s）→ Ollama 用原生 `/api/chat` + `think:false`（首 token 1.1s） | 高（走 Ollama 低延迟链路时） |
| 23 | **平台 think 未关 → SSE 被 166 转发层超时断流**（71-101s 不出 token；/no_think 软开关无效，per-agent 思考控制 REST 不可见）→ 客户端三件套：断流显式报错 + 空 run 重试×2 + agentId 可切换（chatAgent 2-6s 可应急） | 高 |
| 24 | **AG-UI SSE 只消费 TEXT_MESSAGE_CONTENT + 终止事件**；RAW 忽略；空 run 两种签名（几千空 chunk=思考吃满 vs 零 chunk=静默死亡）先分型再归因；时延看 TEXT_MESSAGE_START | 高（Kotlin SSE 解析器照此实现） |
| 25 | **thread 服务端追加记忆 → 必须增量发消息**（重发全量历史污染 thread，空回复率 ~50% vs 干净 ~20%）→ 只发最新一条、消息 id 全局唯一、重试复用同 id | 高 |
| 26 | **「干净断流」必须显式报错**（nginx 干净断开无异常无 RUN_FINISHED，与合法空响应无法区分，曾伪装 0 字成功）→ SSE 消费永远校验终止帧 + SNAPSHOT 前缀比对兜底 | 高（流式协议消费铁律） |
| 27 | **num_ctx=2048 防显存分页致 TTS 慢 5-10 倍**；共享 Ollama 有实例驱逐坑（同事大 num_ctx 互相驱逐，16-78s 抖动） | 中（服务端 GPU 共享抖动会传导到 Android 体感） |
| 28 | **Ollama api_key 占位 + NO_PROXY 白名单**（系统代理会把内网请求送进代理致 read timeout）→ Android VPN/代理 App 同构，内网地址必须绕代理 | 中 |
| 29 | **system prompt 平台侧配置不可覆盖 + 换 agent 只改 header**；createThreadId 必须同时带 threadName+agentId；threadHistory 不按 agentId 过滤且有脏数据（字面量 "agentId" 行）→ 客户端须容忍 | 高 |
| 30 | **管理查询接口全 POST 语义**（GET 一律 405/500） | 中 |

### 7.4 音频设备（采集/播放/打断）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 31 | **AGC 塌陷：设备级增益间歇性塌陷 10x，换库无用**（曾误判"换 Qt 解决音量低"，背靠背实测推翻）→ 诊断看 RMS/max 样本值不看字节数；对比必须背靠背同时刻；存 wav 留档 | 高（手机也有 AGC/权限波动，诊断手法直接搬） |
| 32 | **换音频栈后必须重新验证音量**（别假设同麦同结果） | 中 |
| 33 | **半双工靠 flag 门控 decode，不停 stream**（stream 常开 + Event 门控；采麦+播放双流并存实测不冲突） | 高（AudioRecord/AudioTrack 同开 + 状态机门控照搬） |
| 34 | **Barge-in 防误触发多层过滤**：RMS 门限 + VAD 概率 + 高置信持续 + 最短持续 600ms + 冷却期 800ms + 播放期 ASR 门控 | 高（Android 若做打断） |
| 35 | **外放场景必须 AEC**（外放回声误触发 VAD；原生 WebRTC/SpeexDSP AEC） | 高（Android 有硬件 AEC 可优先试） |
| 36 | **播放失败静默兜底降级纯文字**（TTS/播放失败只 log 仍发 done） | 高 |
| 37 | **语音状态机进程内闭环**（四态 + 后台线程不阻塞消费线程；半双工不跨进程中转） | 高（Kotlin 单 App 天然进程内，设计直接搬） |

### 7.5 平台接入（网络/鉴权/协议）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 38 | **443 nginx 反代必须，:8080 直连 401 ≠ 服务挂了**（RuoYi 网关未注册路由统一 401「路由不存在」假象）→ 排查手法：抓网页真实请求对比 URL/端口/headers | 高 |
| 39 | **443 自签证书**：按 URL 白名单跳过校验，勿全局放行 | 高 |
| 40 | **token 36 字符 UUID 三接口通用，缓存 25min 续期**；**引擎端 9802/9803 不验令牌裸调即可**——Android 直连可免登录链路（仅 Agent 需要 token） | 高 |
| 41 | **平台偶发卡死先等先取证**（平台侧操作触发后端重载；曾两轮把内容触发 500 误判成时段性故障）→ 先验证链路归属再动手 | 中 |
| 42 | **内网可达性是产品级前置决策**（10.0.18.x 需同网段/VPN） | 高 |

### 7.6 打包分发（Android 无 ffi.dll，教训通用）

| # | 坑 — 规避 | 相关性 |
|---|---|---|
| 43 | **依赖收集必漏 + 可选依赖 try/except 必须记日志**（ffi.dll 漏收致 frozen 无声，静默吞是最大敌人）→ Android 等价物：so 库 ABI 核对、ProGuard 误删、权限声明漏配；release 包必须干净机真机验收，打包警告清零 | 中 |
| 44 | **release 无 console 环境日志必须文件化 + 真实错误透传到 UI**（MSI stderr 黑洞致部署排障零线索；固定文案掩盖可操作信息） | 高（Android 文件日志 + logcat 要在 M1 立起来） |
| 45 | **内置默认值按「部署机零配置」定**（不写 127.0.0.1）→ Android 默认值写死 BuildConfig/常量 = 内网生产地址 | 高 |

### 7.7 设计决策（Kotlin 重写迁移价值最高）

| # | 决策 | 相关性 |
|---|---|---|
| 46 | **providers 抽象按协议分不按部署位置分**（ASR/TTS 平台与本地同引擎只换 base_url 各一类；LLM 两种协议两类；流式/非流式统一接口） | 高（Kotlin providers 层直接复刻） |
| 47 | **双栈参数漂移已发生两次，第三次移植必须参数对账**（pre-roll/判停只改一栈忘同步另一栈；删旧栈误删在役测试）→ 开工前把语音链路全部参数列成单一事实源权威表逐项对照 | 高（Kotlin=第三栈，最直接教训） |
| 48 | **错误语义：LLM 中途失败已念出收不回**（放弃全有/全无：已切句照播 + 残句冲刷照播 + 历史记部分 + error 与 done(partial) 都发） | 高 |
| 49 | **KWS：流式小块喂召回 < 整段喂**（别拿整段命中率外推流式）；合成样本验不了门控（只能真麦验） | 高 |
| 50 | **干净机无麦回环验证法**：平台 TTS 合成→WAV 剥头取 PCM→送平台 ASR 转写→比对原文（真麦可用前先验链路） | 高（Android 模拟器验链路同款手法） |

### Android 开发最该记住的 5 条

1. **流式消费三件套是铁律**：SSE/NDJSON 消费者必须校验终止帧（RUN_FINISHED/done），干净断流显式报错（否则和合法空响应无法区分）+ 空 run 自动重试 ×2 + MESSAGES_SNAPSHOT 兜底。平台 think 未关会超时断流，agentId 必须做成可切换配置——这三层防御桌面端救过至少 5 次场，Android 第一天就要写进去。
2. **同一套引擎，客户端清洗与分句逻辑必须复刻**：CosyVoice 遇纯符号/markdown 必 500 → TTS 前逐字符白名单清洗、听觉/视觉分离；text 必须分句成数组送流式（整段单元素=伪流式 17.8s）；断句只用 `。！？` + 200 字符 flush 阈值。ASR 侧必须显式 `language=zh`（默认 auto 中韩误判）+ VAD 截断尾部静音防幻觉。
3. **VAD 用最小解，pre-roll 容量必须错开**：绝对阈值（level≥0.05）+ 只累积 speech 块 + 800ms 判停，不要加自适应层（SNR 校准期失聪是真实踩过的坑）；pre-roll deque 容量 = 判起块数 + pre_roll 块数（同长则触发前保留实为 0）。超时日志必须带「期间最大 level」——≈0.0001 是系统层（权限/没开麦，Android 上就是 RECORD_AUDIO 被拒），0.01-0.05 才是阈值问题，一值定层省一轮往返。
4. **第三次移植必须先做参数对账表**：pre-roll/判停/阈值/分句标点/buffer 在双栈间漂移过两次（改一栈忘同步另一栈，还误删过在役模块的测试）。开工前把语音链路全部参数列成单一事实源权威表，Kotlin 实现逐项对照验收；测试随被测对象走，删旧栈前 diff。
5. **诊断方法论比代码更值钱，release 可观测性先行**：字节数≠音量（看 RMS/max）、对比要背靠背同时刻、存 wav 留档、错误日志带 URL/上下文、真实错误 msg 透传到 UI（固定文案掩盖可操作信息是部署排障最大敌人）。Android release 无 console，文件日志 + 干净机真机验收（依赖收集必漏：so 库/权限/ProGuard，打包警告清零）要在 M1 就立起来，而不是出事后补。

---

## 8. Android 专项约束（ROM/合规/权限）

- **悬浮窗**：`TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` 权限；ROM 私有权限碎片化
  （小米/vivo「后台弹出界面」、OPPO 自启动、华为 ProtectActivity）需逐厂商引导。
- **Android 12+ 不受信任触摸限制**：穿透遮挡透明度须 ≤0.8 或窗口缩到 UI 大小——
  **全屏 hit 层方案不可行**，改「宠物大小窗口 + 拖拽时切 flag」。
- **后台麦克风**：Android 9+ 禁后台麦 → 常开唤醒 = 永久 microphone 前台服务 + 常驻通知 +
  麦克风指示点；11+ 后台启动 FGS 不能拿麦；14 强制 FGS 类型声明。
- **保活**：前台服务 + START_STICKY + 电池优化豁免 + 厂商自启动白名单——
  **国产 ROM 杀后台无 100% 技术解**（最大不确定性）。
- **电池**：闲置降帧、熄屏暂停策略。
- **热键概念不存在** → 唤醒词（与本项目既定方向一致）/悬浮球/通知按钮/Quick Settings Tile 替代。

---

## 9. M1-M4 路线与工作量

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M1 | 悬浮桌宠本体（overlay 渲染/拖拽/触摸合规/设置页骨架） | 2-3 人周 |
| M2 | 对话链路（Kotlin providers + 气泡 + 键盘输入） | 2-3 人周 |
| M3 | 语音链路（VAD/半双工/TTS 分句/KWS/前台服务合规） | 2-3 人周 |
| M4 | ROM 加固（权限引导/保活/耗电/多 ROM 真机） | 2-4 人周 |
| **合计** | | **8-13 人周（单人 2-3 个月）** |

M1 开工清单：新建工程（Kotlin + minSdk 26+，overlay 需要）→ 悬浮窗权限引导页 →
WindowManager overlay 渲染 spritesheet 帧动画（Canvas 或 ImageView+帧切换）→
拖拽（触摸时 FLAG_NOT_TOUCHABLE 切换）→ 点击挥手 → 设置页骨架（缩放/气泡开关）。

---

## 10. 资产处置速查

| 资产 | Android 处置 |
|---|---|
| `pet/assets/xiaoduo/spritesheet.webp` + `pet_states.json` | **直接复用**（帧动画按 §4.1 表驱动） |
| `prototype/kws-model/` 5 文件 | **直接复用**（sherpa-onnx Android SDK 加载） |
| 4 个 HTTP 协议 | Kotlin/OkHttp 重写，按 §2 契约 |
| 分句/VAD/reveal/半双工 | Kotlin 重写（~100 行内纯逻辑，参数见 §3） |
| sidecar NDJSON IPC、Tauri 事件、热键 | **废弃**（事件改内部回调，配置改 SharedPreferences） |
| `docs/PLATFORM_INTEGRATION.md` | 平台对接唯一权威文档，人手一份 |

**关键风险提醒**：① AG-UI 必须增量发消息 + 空 run 重试 + 未见 RUN_FINISHED 显式报错，
三条少一条就会复现「空回复率 50%」；② TTS 流式必须先缓冲再播（防残帧+兜底重复）；
③ ASR 依赖 VAD 截尾静音防幻觉；④ KWS gate 是单机校准值，需可配置 + 校准入口。
