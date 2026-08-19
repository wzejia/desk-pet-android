# 小朵 Android 开发总纲（MASTER PLAN）

> **【2026-08-03 注】本文件为 M1 时期总账，已封存**。助手化大切片（S0-S8）已全收口（2026-08-06，358 测绿），
> 现行权威层级以根目录 `CLAUDE.md` 为准（产品行为=plan 002；KTD1「不引 Compose」已被 KD11 推翻、
> 「App=控制台」已被 KD9 推翻、M2/M3 已由助手化切片交付，下文冲突处不再逐条修订）。
> 进度与接手见 `PROGRESS.md`，机构解法见 `docs/solutions/`。
>
> **定位**：本仓库唯一入口文档。汇总产品决策、计划书、协议/参数速查、风险登记、
> wayfinder 地图现状、偏差记录、环境基线。
> 维护日期：2026-07-29 ｜ 维护方式：每个里程碑收口 / 每张决策票关闭后更新对应小节。
> 权威层级：产品行为以 `docs/plans/2026-07-29-001-feat-xiaoduo-android-app-plan.md`（Product Contract）为准，**M1 交互细节的最新补充以 `docs/dev-handbook/06-grilling-decisions-and-deviations.md` 的 grilling 决策 1-10 为准**；
> 协议与语音参数以桌面知识包为单一事实源；实现机制以 plan 的 KTD 为准；本文件是导航 + 汇总，不另立副本权威。

---

## 0. 文档地图（先读这个）

| 文档 | 位置 | 作用 | 权威级 |
|---|---|---|---|
| **本文件** | `docs/MASTER-PLAN.md` | 入口/导航/决策总账/偏差记录 | 导航 |
| 桌面知识包 | `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（镜像：`docs/m1-dev-kit/android-dev-brief.md`） | 四协议字段级契约、语音链路参数、55 条踩坑、Android 专项约束 | **协议/参数单一事实源** |
| 平台集成文档 | 桌面端 `docs/PLATFORM_INTEGRATION.md`（镜像：`docs/m1-dev-kit/PLATFORM_INTEGRATION.md`） | 平台对接字段细节 | 协议细节权威 |
| Product Contract + 实施计划 | `docs/plans/2026-07-29-001-feat-xiaoduo-android-app-plan.md` | KD1-8 / R1-R14 / F1-F3 / AE1-AE6 / KTD1-7 / U1-U6 | **产品行为权威** |
| M1 方案（grilling 共识） | `docs/plans/M1-plan.md` | M1 产品决策与技术默认（部分已被 plan 修订，见 §9 偏差 D10-D12） | 历史参考 |
| sherpa-onnx 调研 | `docs/research-sherpa-onnx-android-2026-07-28.md` | M3 KWS 集成结论 | M3 依据 |
| ROM 权限调研 | `docs/research-rom-permissions-2026-07-28.md` | 6 权限清单 + 分步引导 + OEM 跳转表 | M1/M4 依据 |
| 语音参数对账表 | `docs/voice-param-reconciliation-2026-07-28.md` | 桌面 HEAD 逐项对账 + M3 Kotlin 验收清单 | M3 验收依据 |
| wayfinder 地图 | `.scratch/xiaoduo-android/map.md` + `issues/01-09` | 决策票追踪（见 §7 摘要） | 决策追踪 |
| M1 进度与验收证据 | `PROGRESS.md` + `docs/m1-screenshot.png` | 环境基线、硬指标取证、设计偏差 | 进度事实 |
| 开发手册（工程实操） | `docs/dev-handbook/`（01-05 工程实操 / 06-10 决策与计划书，README 有导航与口径关系） | M1 as-built、验收审计、M2-M4 详计划、环境运维 | 工程实操（从属本文件） |
| 领域术语表 | `CONTEXT.md` | 小朵/气泡/半双工/三件套等共识语义 | 术语 |
| ADR | `docs/adr/0001-m1-scope-and-form.md` | 形态与范围决策记录 | 决策留痕 |
| issue tracker 约定 | `docs/agents/issue-tracker.md` | 本地 markdown tracker 规则 | 流程 |

外部约束：桌面端仓库 `E:\desk-pet-migration\desk-pet` **只读引用**；仅知识包文档允许回写
（协议/素材变动、参数对账发现不一致时）。

---

## 1. 产品定位与目的地

**一句话**：小朵 Android 版是原生 Kotlin 单 App——**悬浮窗是陪伴主角，App 本体是控制台**
（设置/权限引导/网络配置/对话记录），首页内嵌可互动的小朵预览。

** Problem Frame**：小朵只有 Windows 桌面端；库房员工离开工位后无法连平台 Agent 干活。
库房手持设备接公司内网，但**不同内网对应不同网段**，写死 10.0.18.x 换一站就失联。

**wayfinder 目的地（M1-M4 全旅程）**：独立 App 悬浮窗展示小朵并可交互（拖拽/点击/气泡）
→ 接入内网对话链路（M2）→ 语音唤醒对话（M3）→ 目标国产 ROM 稳定运行（M4），达到可日常用。

**总验收门槛（用户既定）**：顺利接入平台（内网 AG-UI）+ 延迟可用。

---

## 2. 决策总账（全部已拍板，重议需用户明确翻案）

### 2.1 前置门（2026-07-28，wayfinder 会话）

| # | 决策 | 结论 |
|---|---|---|
| G1 | 目标手机能否接入内网 | **能**（同网段/VPN）；443 自签证书按内网白名单信任，勿全局放行 |
| G2 | 合规体验成本 | **全接受**：永久前台服务通知 + 麦克风指示点 + 引导开 4-5 个系统权限 |
| G3 | 地图目的地 | 全旅程 M1-M4 |
| G4 | 目标机型含鸿蒙？ | **不含 HarmonyOS NEXT**（纯血鸿蒙不装 APK 的风险排除） |
| G1' | G1 细化（07-29 brainstorm KD6） | 能接内网，但**不同站点网段不同** → 全部端点可编辑 + 内置默认值；配置组/扫码导入延后 |

### 2.2 产品决策 KD（Product Contract，07-29 brainstorm 用户确认）

| # | 决策 | 要点 |
|---|---|---|
| KD1 | 原生 Kotlin 单 App | 否 Tauri（无 overlay）/ Chaquopy（无 sounddevice） |
| KD2 | 独立新仓库 `E:\desk-pet-android` | 不放桌面仓库子目录 |
| KD3 | 控制台+预览定位，悬浮窗是主角 | 否养成类（素材只有 9 行基础动作） |
| KD4 | **链路风险前置**：M1 内插一次性链路调试页 | 第一周上库房真机排雷（多网段/自签/流式三件套）；调试页**永不进化成正式对话 UI** |
| KD5 | 拖拽合规：宠物大小窗口 + 拖拽切 flag | 否全屏 hit 层（Android 12+ 不可行）⚠️ 已被偏差 D1 修订 |
| KD6 | 端点 M1 手动编辑 + 内置内网默认值 | 多站点配置组延后 |
| KD7 | 一套自适应布局，手机全比例 | 平板/折叠屏优雅降级不定制 |
| KD8 | **KWS 门控可配置 + 校准入口 = M3 硬性验收** | 库房嘈杂 × gate 0.015 单机校准值，硬编码必踩 |

### 2.3 技术决策 KTD（Planning Contract）

| # | 决策 | 要点 |
|---|---|---|
| KTD1 | 经典 Views + 自定义 PetView，**不引 Compose** | 对齐已落地基线 |
| KTD2 | SharedPreferences（Prefs 单例），**不引 DataStore** | ⚠️ 修订了 M1-plan/知识包的 DataStore 建议（D11） |
| KTD3 | 协议消费器 = 纯 Kotlin 状态机 + JVM 单测，Android 层只做 IO | AG-UI 三件套收口在无 Android 依赖的 consumer 类 |
| KTD4 | 单一 OkHttp 工厂：内网 NO_PROXY 直连 + 自签按 host 白名单 TrustManager | 知识包 #28/#39 落地 |
| KTD5 | **M1 窗口按精灵区高度开窗，气泡预留区 M2 再加** | ⚠️ 修订知识包 §4.2 的 192×288（D10） |
| KTD6 | 权限引导 M1 仅悬浮窗强制；通知/电池/自启动列出并标注里程碑 | 提前引导徒增负担 |
| KTD7 | 调试页仅 AG-UI 平台路径（含 login + token 25min 缓存） | Ollama 路径是 ticket 04 待决策项，提前做会固化抽象 |

---

## 3. 架构蓝图

```
小朵 App（单进程）
├── 控制台 MainActivity —— 首页预览 / 设置 / 权限引导 / 调试页入口
├── DebugChatActivity —— 纯文本链路调试页（一次性排障工具，R12）
├── PetOverlayService —— WindowManager TYPE_APPLICATION_OVERLAY
├── PetView + FrameAnimator —— App 内与悬浮窗复用（纯 Kotlin 可单测）
├── Prefs（SharedPreferences）—— 缩放 / 开关 / 端点 / 窗口位置
├── NetModule —— OkHttp 工厂（NO_PROXY + 白名单 TrustManager）+ TokenStore（25min）
└── PetLogger —— 文件日志 + 轮转 + CrashReporter
        │
        └── 内网 NO_PROXY 直连 / 443 自签白名单 → 平台（login + AG-UI SSE）
```

技术栈基线：Kotlin 1.9.24 / minSdk 26 / target·compileSdk 34 / Gradle 8.9 + AGP 8.5.2 /
包名 `com.xiaoduo.deskpet` / Views 体系 / SharedPreferences / OkHttp + SSE / 协程（M2/M3 管线）。

---

## 4. 里程碑路线与现状

| 里程碑 | 内容 | 估时 | 状态（2026-07-29） |
|---|---|---|---|
| M1 | 悬浮桌宠本体 + 控制台骨架 + **链路调试页** | 2-3 人周 | **本地段完成并收口 ✅**（2026-07-30：偏差 A-D 全部裁决——A 改 208 实施取证 / B、C 修订决策追认关闭 / D 滚动+导出补做取证；U1 契约测试已立，验收 9 条裁决后口径全绿）；**链路段 U5/U6 在途** |
| M2 | 对话链路（Kotlin providers + 气泡 + 键盘输入） | 2-3 人周 | 未开工；**阻塞在 ticket 04（provider 路径决策）** |
| M3 | 语音链路（VAD/半双工/TTS 分句/KWS/前台服务合规） | 2-3 人周 | 未开工；前置调研 ✅（02/03/07）；硬要求 KD8 |
| M4 | ROM 加固（权限引导/保活/耗电/多 ROM 真机） | 2-4 人周 | 未开工；ROM 调研 ✅（03） |

### M1 单元清单（plan U1-U6）

- U1 权威文档与素材镜像（含 PetFrameTableContractTest 帧表契约测试，护栏知识包 #47）
- U2 PetOverlayService（拖拽/点击/位置持久化/总开关）——**已完成**（偏差 D1）
- U3 控制台三件套（首页预览/设置/权限引导）——**已完成**
- U4 文件日志轮转与导出——核心已落地，轮转/导出增强在途
- U5 网络配置面 + OkHttp 基础设施（NetConfig/HttpClients/IntranetTrustManager/TokenStore）
- U6 AG-UI 链路调试页（AguiSseConsumer 三件套 + AguiClient + DebugChatActivity）——**M1 验收核心**

### M2 候选范围（依赖 ticket 04 结论）

气泡 UI（知识包 §4.2 参数：精灵正上方 max-height 74dp、圆角 10dp、半透明白底 rgba(255,255,255,0.92)、
11sp×font-scale、打字机 40ms/字、总开关 show_bubble）；Kotlin providers 四协议；键盘输入；
宠物对话状态联动（listening→waiting / processing→review / speaking→talking / idle→idle）；
overlay 与 IME 焦点矛盾解法（ticket 05 原型承接，备选：输入走 Activity 弹层）。

### M3 要点（全部有验收依据）

- VAD/分句/消毒/KWS/状态机参数 → **逐项对照 `docs/voice-param-reconciliation-2026-07-28.md` 验收清单打勾**。
- sherpa-onnx 集成 → `docs/research-sherpa-onnx-android-2026-07-28.md`（AAR v1.13.4 / assets 直载 / API 对照）。
- KWS gate 可配置 + 校准入口（KD8 硬性）；真机校准库房嘈杂环境。
- 麦克风 FGS：Android 14 声明 `FOREGROUND_SERVICE_MICROPHONE` + type；**VoiceService 必须由用户可见动作拉起**（后台拿不到麦）；绿点不可隐藏 → 引导文案预解释；POST_NOTIFICATIONS 先拿。
- 外放 AEC：优先 `AudioSource.VOICE_COMMUNICATION` 硬件 AEC（ROM 调研 §2）。
- 半双工照搬知识包 §3.5（状态分发 + KWS pause + 单采麦流）；打断（barge-in）与否 = ticket 06 待决策。

### M4 要点

ROM 权限引导全量（`docs/research-rom-permissions-2026-07-28.md` §4 OEM 跳转表，全部 try/catch 回退
应用详情页）；电池豁免（标准 Intent + MIUI 省电策略第二层）；自启动/后台弹出界面；
保活无 100% 技术解（最大不确定性，投入深度实测后定）；耗电策略（闲置降帧/熄屏暂停，
需 M1 overlay 实测基数）；多 ROM 真机矩阵（机型清单 = ticket 08）。

---

## 5. 协议与参数速查（单一事实源指针 + 关键表）

> 字段级契约细节一律回知识包 §2/§3/§4 与 PLATFORM_INTEGRATION.md，这里只放高频速查。

### 5.1 内网默认端点（§6.1，全部可配置，R10）

| 服务 | 地址 |
|---|---|
| 登录 | `http://10.0.18.166:8080/login` |
| Agent（AG-UI SSE） | `https://10.0.18.166/ai-server/agent/stream`（443 nginx 自签，**勿直连 :8080**） |
| ASR | `http://10.0.18.97:9802/audio/asr/transcribe` |
| TTS | `http://10.0.18.97:9803/audio/tts/{synthesize,stream}`（**直连，勿经 166 转发**——30s 截断坑 #2） |
| Ollama | `http://10.0.18.24:11434`（剥 /v1，原生 /api/chat + think:false） |

### 5.2 流式消费三件套（铁律，R13）

1. 校验终止帧：未见 RUN_FINISHED（AG-UI）/ done（TTS）= 断流，**显式报错**，不伪装 0 字成功。
2. 空 run（累计 0 字，平台实测率 ~20%）自动重试共 3 次，**复用同一消息 id**。
3. MESSAGES_SNAPSHOT 兜底：末条 assistant 比已产出长则补尾部（兼容 string 与分片两种形态）。
另：**增量发消息**（只发最新一条，重发全量历史 → 空回复率 ~50%）。

### 5.3 语音链路核心参数（对账表已逐项核对 HEAD ✅）

- VAD：level≥0.05（rms/8000）/ 起 3 块 / 停 8 块 / min 3 块（9600B）/ **timeout 30s（勿抄类默认 8.0）** / max 8s / pre-roll 3 块 / **deque 容量 6** / 块 100ms@16k。
- 分句：`。！？!?;`+\n，MIN 4 / MAX 30，ASCII 词边界回退，逐字符喂，残留收尾。
- TTS 消毒：白名单标点 + alnum/space + **连续空白压缩**；空则跳过合成；气泡用原句朗读用清洗句。
- KWS：threshold 0.5 / RMS gate 0.015（**必须可配置**，KD8）/ threads 2 / 100ms 块 / 命中 reset。
- 音频：采集 16k/Int16/mono；TTS 24k/Int16/mono，**多帧 WAV 剥头拼 PCM**（#7）。

### 5.4 精灵动画帧表（§4.1，契约测试护栏）

spritesheet 1536×1872，帧 192×208，8 列；行：idle(6f/1100ms) runR(8/1060) runL(8/1060)
waving(6/900) jumping(5/840) failed(8/1220) waiting(6/1600) talking(6/720) review(6/1500)。
状态映射：listening→waiting / processing→review / speaking→talking；切换重置从头播。
MD5（与桌面端一致）：`53c81ce652518f7fc7bf70554f363f54`。

---

## 6. 风险登记册

| 风险 | 等级 | 缓解 | 状态 |
|---|---|---|---|
| 内网多网段写死失联 | 高 | R10 端点全可配 + 内置默认值；库房真机 F3 排雷（KD4） | 配置面 U5 在途 |
| 443 自签 + 系统代理卡死流式 | 高 | KTD4 白名单 TrustManager + NO_PROXY | U5 在途 |
| 平台空 run/断流伪装成功 | 高 | 三件套（R13）收口纯 Kotlin consumer + 单测 | U6 在途 |
| 国产 ROM 杀后台无 100% 解 | 高（M4） | 权限引导做全 + 真机矩阵；投入深度实测后定 | 调研 ✅，M4 |
| KWS gate 单机校准值不适配手机 | 高（M3） | KD8 硬性：可配置 + 校准入口 | 已定案，M3 |
| 库房真机网段端点未提供 | 中 | plan Stop condition：AE3-AE5 挂起不硬闯，不阻塞 U1-U6 | 待用户提供 |
| OEM 私有 Intent 易碎 | 中（M4） | 全部 try/catch 回退应用详情页；目标机型逐一实测 | 调研 ✅ |
| overlay 与 IME 焦点矛盾 | 中（M2） | ticket 05 原型验证；备选输入走 Activity 弹层 | 待原型 |
| TTS 纯符号 500 / 首音 2.5s 地板 / 166 转发 30s 截断 | 中（M3） | 消毒 + 分句 + 直连 9803（知识包 #1-#4） | 参数已对账 |
| 双栈参数漂移第三次重演 | 中 | 对账表 + PetFrameTableContractTest 护栏（#47） | ✅ 护栏已立 |

---

## 7. wayfinder 地图现状（`.scratch/xiaoduo-android/`）

| 票 | 类型 | 状态 | 要点 |
|---|---|---|---|
| 01 开发环境与新仓库初始化 | task | open（事实上已被 M1 执行覆盖大半：环境✓ 骨架✓ spritesheet✓ 日志✓） | 收口时可关闭 |
| 02 sherpa-onnx AAR 调研 | research | ✅ resolved | §4 调研文档 |
| 03 ROM 权限调研 | research | ✅ resolved | §4 调研文档 |
| **04 M2 provider 路径优先序** | grilling | **open — M2 的闸门** | Ollama 免登录 vs AG-UI 平台，先聊这张 |
| 05 气泡与键盘输入交互原型 | prototype | open（原 blocked by 01，01 事实完成后可解封） | M2 形态 |
| 06 语音打断做不做 | grilling | open | M3 范围 |
| 07 VAD 参数对账 | task | ✅ resolved | 对账表 + 知识包回写 |
| 08 分发与装机方式 | grilling | open（鸿蒙已排除记录在 Comments） | 含机型清单 → 解 M4 雾 |
| 09 Brainstorm 决策同步 | task | open | KD 同步进 map/PROGRESS；M1 不再「零网络」 |

迷雾区（Not yet specified）：M4 ROM 加固范围与真机清单 / 保活策略深度 / 耗电预算 /
平台 thread 管理 UI 形态（取决于 04）。
KWS 校准形态条目已被 KD8 升格为 M3 硬性验收（不再是开放形态问题）。

Out of scope：iOS / 桌面端代码修改 / 公网部署 / Tauri·Python sidecar 迁移 /
养成玩法（无素材）/ 全屏 hit 层拖拽（系统限制）/ 调试页进化成正式对话 UI（永不）。

---

## 8. 偏差与发现记录（implementation notes，按日期）

> 纪律：设计决策 / 有意偏离权威文档 / 权衡 / 未决问题才记；cosmetic 不记。

| # | 日期 | 分类 | 内容 |
|---|---|---|---|
| D1 | 07-29 | **偏差（修订 KD5/知识包 §8）** | 拖拽**不切** FLAG_NOT_TOUCHABLE：手势进行中加此 flag 会截断 MOVE/UP 事件致拖拽冻结；Android 12 不受信任触摸限制针对 overlay 遮挡下层窗口，宠物大小窗口自身手势不受限。真机拖拽 4 次验证正常。M2 如涉及知识包 §8 回写。 |
| D2 | 07-29 | 发现（ROM） | ColorOS 禁止 shell 授予悬浮窗权限（appops SecurityException）+ 禁止 am startservice 拉非导出服务 → 权限与悬浮窗开启只能走 App 内真实用户路径（与产品设计一致）。 |
| D3 | 07-29 | 决策（工具链） | gradle wrapper distributionUrl 指向本地 `file:///D:/gradle-dist/gradle-8.9-bin.zip` 防代理变动；依赖下载走 gradle.properties systemProp 代理四项（127.0.0.1:7897）。 |
| D4 | 07-28 | 发现（参数） | `AsrListener` 类默认 listen_timeout_s=8.0 但 sidecar 显式传 30.0 → **Kotlin 硬编码 30s**；sidecar 文档串「8s 超时」过期（桌面只读不回改）。 |
| D5 | 07-28 | 发现 + 回写 | 知识包 §7.1 #6 与 §3.1 自相矛盾（「仅。！？+200」是 voice-assistant 历史形态）→ 已回写知识包澄清：HEAD 权威 = §3.1 表。 |
| D6 | 07-28 | 发现 + 回写 | TTS 消毒在 HEAD 多一步「连续空白压缩」，知识包漏记 → 已回写补注。 |
| D7 | 07-28 | 发现（留档） | voice_listen.py 类文档串 min_bytes「×0.3=2880」是阶段一残留，实际代码无系数（9600B）；Kotlin 按 9600B。 |
| D8 | 07-28 | 流程备注 | wayfinder 会话 commit 7a67054 卷入并行 M1 会话未提交骨架文件（内容完整，无损失）；多会话共用仓库，提交前先 git status。 |
| D9 | 07-28 | 流程降级 | research 票按技能应派发子 agent，pi 无子 agent → 主上下文串行执行（结果等价，成本是会话长度）。 |
| D10 | 07-29 | 偏差（修订知识包 §4.2） | KTD5：M1 窗口**按精灵区高度开窗**（192×208dp×scale），不加 80dp 气泡预留区——空预留区遮挡屏幕且扩大触摸合规面；气泡区 M2 再加。知识包 §4.2 的 192×288 是 M2 目标形态。 |
| D11 | 07-29 | 偏差（修订知识包技术栈建议） | KTD2：配置存 SharedPreferences，不用 DataStore（已落地 Prefs + 迁移无收益）。 |
| D12 | 07-29 | 偏差（修订原 M1 范围） | KD4：M1 不再「零网络」，插入一次性链路调试页（U5/U6）；原 M1-plan「严格 M1 无网络」被用户拍板推翻。 |
| D13 | 07-29 | 未决 | 库房真机网段端点地址 + 机型清单未提供 → AE3-AE5 挂起中，待用户（见 plan Outstanding Questions）。 |
| D14 | 07-29 | ✅ 已裁决（07-30） | 偏差 A：窗口 288→**改 208 已实施**，真机 dumpsys 645x698px @scale0.84 = 192×208dp×scale 取证。与 KTD5（D10）口径统一。 |
| D15 | 07-29 | ✅ 已裁决关闭（07-30） | 偏差 B：用户确认 07-30 grilling 交互集有效 → **修订决策 4 追认现状**（无长按菜单，隐藏走 App 内开关）。 |
| D16 | 07-29 | ✅ 已裁决关闭（07-30） | 偏差 C：同上 → **修订决策 7 追认现状**（拖拽仅移动窗口，不播 running）。 |
| D17 | 07-29 | ✅ 已裁决并补做（07-30） | 偏差 D：追认手写 PetLogger（不引 Timber），已补按天滚动+启动清理 7 天+设置页导出（FileProvider 分享面板），单测 6 条+真机取证。U4 剩 CrashReporter。 |

---

## 9. 环境与构建实测基线（PROGRESS.md 摘要）

- JDK 17.0.20（winget）/ SDK：`C:\Android\Sdk`（platform-tools 37 / android-34 / build-tools 34）
  / adb 1.0.41 / Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.24——全程代理 127.0.0.1:7897。
- 真机：`de61cd8`（PJD110 / OP5929L1，**ColorOS**，1440×3168 @640dpi）。
  overlay 窗口实测 768×1152px = 192×288dp @640dpi（含旧气泡预留，D10 后按精灵区）。
- **BIOS 虚拟化未开 → 模拟器不可用，全程真机联调**（Docker/WSL2 同不可用）。
- 验收命令：`./gradlew.bat assembleDebug testDebugUnitTest`（单测 6/6 绿，含帧表）；
  真机三证据：dumpsys overlay 窗口 / 同屏截图 / run-as 读日志文件。
- 网络硬约束：外网走 Clash 代理；**内网必须 NO_PROXY**；本机服务写 127.0.0.1 不写 localhost；
  脚本/日志一律 UTF-8（GBK 管道坑）。

---

## 10. 下一步行动（按优先级）

1. ~~**用户裁决 M1 偏差 A-D**~~ ✅ 已完成（2026-07-30，见 D14-D17）。下一步：U2-U6 增补单元（`docs/dev-handbook/04`），依赖序 U2→U3→U5→U6。
2. **用户提供**：库房网段端点地址 + 目标机型清单（解 D13、推进 ticket 08、F3 执行前提）。
3. U5/U6 落地（M1 收口）→ 库房真机 F3 + AE3-AE6 取证。
3. 关闭 ticket 09（决策同步）与 01（事实完成）。
4. **聊 ticket 04（M2 provider 路径）**——M2 开工的闸门；随后 05（气泡原型）、06（打断）、08（分发）。
5. M3 前：sherpa-onnx AAR 体积实测 + ProGuard keep 规则（调研 §5 待验证项）。

> 文档分工（07-29 用户拍板）：**本文件 = 唯一总入口**（决策总账/偏差编号 D 系列/风险/票态）；
> `docs/dev-handbook/` = 施工手册（工程实操细节、M1 as-built、M2-M4 详计划、运维坑），从属本文件；
> handbook 06 的 grilling 决策 1-10 是 **M1 交互细节的最新权威**（晚于 plan，含偏差 A-D 裁决流程）。
