# Spec：desk-pet 结构重构与多维度优化（原地重排方案）

> **状态：v0.4（2026-10-10），已认可进入执行（两轮三通道评审收敛）；执行交接见 Temp `HANDOFF-deskpet-spec-exec.md`**。
> **v0.4 领导拍板**：内存/响应指标**暂不设定目标值**，先实测——Phase 0 基线 + 各 Phase 实测数据随进度报告，改善目标待实测后议（§5.4 已改）。
> **v0.3 评审修正**（第二轮 delta 复审）：①§4.2 删 typealias「二选一」——嵌套类型引用语法无法用 typealias 保留（kotlin 1.9.24 实测证伪），唯一路径=全量改写引用；②stale-true 防线补守卫机制定义（活性记账落 PetOverlayState，JVM 可测）；③§5.4 补「播放开始」日志锚（现有日志无起点）；④头注 import android 口径措辞勘正。
> **v0.2 评审修正**（对抗评审 + 事实核查双通道，main 对话逐条实证仲裁）：①一刀清单改判——AsrClient/TtsClient 为具体实现类非接口，下沉对象改为 cloud 实际消费面（嵌套 Result 类型 + LANGUAGE_ZH）；②依赖脚本 root 归并盲区修正（v1→v3），root 回边 8 条白名单化 + BuildConfig 产物边单列，验收口径 = 环清零 + 例外边不增；③§2 锚与例外清单表述自洽化；④§4.5/ocr 引用计数等标注级勘误（详见各节）。
> **口径声明**：本 Spec 全部 LOC 为**非空行**（实测脚本统计，见附录 A）；交接口径 18222 为总行数（已复核一致）。§3.1「import android」列 = 含 android import 的**文件数**（全文正则口径，注释命中计入；严格按 import 语句行计为 ui=11、voice=8 个文件）。
> **硬约束**：行为零变化；单测基线 754 只增不减（skipped=0；2026-10-10 执行期修正——原记 755，fafa28e trampoline 重写退役旧策略 3 测、新策略 2 测补位，文档漏改）；4 条架构红线（见 §8）与 10-09 入口职责划分不得回退。

## 来源

- 领导意图（2026-10-10 拍板）：多维度优化 desk-pet；原地重排包/module 结构（非另起重写）；优化维度 = P3 backlog 择优 + 语音链路体验 + 工程质量 + 安全与发布形态 + **内存/响应速度指标**；多 Agent 协同本轮只定机制。
- 评审（v0.2 修订依据）：对抗评审 + 事实核查双通道子智能体（与起草异构大脑、未收起草论证）；P1×2 经 main 对话逐一实证仲裁后采纳，标注级勘误（§4.5 口径、ocr 计数）照单全收。
- 依赖事实源：`.scratch/dep-analysis-20261010.py` + 同目录 JSON（2026-10-10 实测，重跑命令见附录 A）。
- 优化原料：`docs/audit/2026-10-03-full-audit/`（410 P3 + 40 P2 处置）；待拍板项见附录 B。
- 权威架构叙述以 `AGENTS.md` / `CONCEPTS.md` / `assistant-app.spec.md` 为准，本 Spec 不复制其内容、只引用结论。
- 版本：v0.4（2026-10-10）｜维护纪律：实现偏离时改代码对齐，或改本文件并记 `implementation-notes.html`。

## 1. 目标与范围

**做**：
1. 原地重排包结构：先治包级循环依赖，再立分层，最后按门槛评估是否切 gradle module（§4）。
2. 五维优化选料与排期（§5），全部挂到 Phase 施工顺序（§6）。
3. 多 Agent 协同机制定稿（§7），供 Phase 3 起并行使用。

**不做**（非目标）：
- 任何对外可感知行为的变更；新功能；另起目录重写。
- 待拍板项（附录 B）的**决定**——本 Spec 只列选项与分析，拍板权在领导。
- 密钥/发布形态的具体处置（属 SEC 系列待拍板范畴）。

## 2. 成功标准（总验收锚）

| 锚 | 判定 |
|---|---|
| 单测基线 | 754 绿且只增不减（2026-10-10 实测修正，见硬约束注）；验证命令 `export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks`（不带 `--rerun-tasks` 会假绿） |
| 构建 | `assembleDebug` 绿 |
| 资产哈希 | spritesheet.webp MD5 `53c81ce652518f7fc7bf70554f363f54`、pet_states.json SHA256 `60b45124…4366` 不变 |
| 依赖结构 | 重跑附录 A 脚本（v3）：包级循环清零，**例外边白名单（§4.3，当前 8 条 import）之外 100% 符合 §4.1 分层，例外边计数不增** |
| 语义验收 | 真机三链路各取证一次：唤醒词→应答音→聆听；基础设置直拉悬浮窗（OverlayLaunchActivity trampoline）；唤醒打断播报 |
| 性能不劣化 | §5.4 指标基线测完后，重构各 Phase 结束时复测**不回退**（守门线）；改善目标值暂不设定（领导 2026-10-10 拍板：先实测后议） |

## 3. 现状（2026-10-10 实测）

### 3.1 规模与分布

| 包 | 文件 | LOC(非空行) | import android | Compose | 测试文件 |
|---|---|---|---|---|---|
| ui | 23 | 5249 | 12 | 11 | 10 |
| voice | 32 | 4560 | 9 | 0 | 47 |
| chat | 16 | 1898 | 2 | 0 | 15 |
| (root) | 5 | 1241 | 5 | 0 | 1 |
| agui | 4 | 1170 | 1 | 0 | 8 |
| cloud | 9 | 972 | 1 | 0 | 2 |
| util | 9 | 533 | 4 | 0 | 8 |
| device | 4 | 451 | 0 | 0 | 4 |
| net | 4 | 412 | 2 | 0 | 1 |
| pet | 4 | 199 | 0 | 0 | 4 |

主源合计 110 文件 / 16685 非空行（总行数 18222）。`声明包 ≠ 目录位置`异常 **0 条**——目录是干净的，问题只在依赖方向。

### 3.2 包间依赖矩阵（import 语句数，src → dst）

```
(root) -> util(12), ui(9), voice(9), pet(3), chat(1)
agui   -> net(5), util(2)
chat   -> agui(6), device(5), util(3), cloud(3), voice(3), hub(1), net(1), ui(1)
cloud  -> voice(5), net(2), agui(2), util(1), chat(1)
device -> (叶子)
net    -> buildgen(1)           ← BuildConfig 产物边，例外
pet    -> (叶子)
ui     -> util(14), voice(13), chat(10), pet(5), hub(5), agui(4), net(1)
util   -> (叶子)
voice  -> util(11), net(8), device(6), cloud(6), agui(3), hub(2), ui(1), pet(1)
```

**包级循环依赖（脚本 v3 实测）**：环 4 对——`chat↔cloud`、`cloud↔voice`、`voice↔ui`、`ui↔chat`。**例外边两组（登记在案，不计环但计数受 §2 锚约束）**：root 枢纽 8 条（`hub` = `PetOverlayService`/`PetOverlayState`/`VoiceForegroundService` 被下层 import：chat 1 / ui 5 / voice 2，即 v2 口径下的 3 对 root 回边环）；BuildConfig 产物边 1 条（`buildgen`，net——v2 归并实验暴露真链 `root→chat→agui→net→BuildConfig`，module 化时必须改注入，见 §4.4 门槛 2）。叶子 util/device/pet 零外部依赖，agui 只往下（net/util）。

> **盲区声明**：循环问题整体是本次依赖分析的**新发现**——2026-10-03 全量审计 489 条中「循环依赖/包间方向」grep 零命中（审计盲区），本 Spec 首次落档，亦是「先数据后切分」的直接证据。
> **脚本版本注**：v1 把 import 侧 root 类当伪节点，漏检 root 回边（对抗评审发现，main 对话实证）；v2 补对称归并后共见 7 对环；v3 把白名单枢纽边与 BuildConfig 产物边从环检测分离为**登记例外**，验收口径 = 「环清零 + 例外边计数不增」。
> **方法学验证**（对抗评审补测）：全仓通配 import 0 条、反射引用 0 条、全限定内联引用仅存于 KDoc——import 扫描法对本仓环检测可靠；同包直引（如 AsrClient 无 import 直用 WavCodec/VadDetector.SAMPLE_RATE）不影响跨包环判定，但影响**搬迁成本评估**（见 §4.2）。

### 3.3 缠绕点精确定位（断环依据，文件级）

**缠绕 1：契约类型放错层——cloud 的实现复用 voice 客户端的嵌套类型与常量**（v0.2 改判：~~接口放错层~~）
`cloud/AliyunAsrClient.kt`、`OpenAIAsrClient.kt`、`VolcengineAsrClient.kt`、`CloudClientFactory.kt` 均 import `voice.AsrClient`（CloudClientFactory 另 import `TtsClient`）。**实证**：`AsrClient`/`TtsClient` 是具体 OkHttp 实现类**而非接口**（AsrClient.kt:19 `class AsrClient`）；cloud 实际消费面只有——`AsrClient.LANGUAGE_ZH` 常量（Aliyun:41 / OpenAI:29 / Volcengine:42,143）+ `AsrClient.Result` / `TtsClient.Result` 嵌套密封类型（CloudClientFactory asrTranscribe/ttsSynthesize 统一返回值，:54-102）。**搬迁成本警示**：AsrClient 本体不可整体下沉——同包无 import 直引 `WavCodec.pcmToWav` + `VadDetector.SAMPLE_RATE`（AsrClient.kt:37，扫描器不可见），TtsClient 同引 `WavCodec.stripWavHeader`（:118/180）；VadDetector 为有状态 DSP 且被 VSM 重度使用，拖家带口不现实。

**缠绕 2：编排 import 具体实现——voice import cloud**
`voice/VoiceSessionManager.kt` import `cloud.CloudClientFactory`、`OpenAIAsrClient`、`OpenAIChatClient`、`OpenAITtsClient`、`Message`；`voice/WakeAckPrefetcher.kt` import `cloud.CloudClientFactory`。

**缠绕 3：UI 抽象被下层 import——chat/voice → ui**
`chat/ChatViewModel.kt` 与 `voice/VoiceClientFactory.kt` 都 import `ui.overlay.BubbleOwner`（气泡所有权接口，住在 UI 层）。

**缠绕 4：数据类跨界——cloud import chat**
`cloud/TextChatRouter.kt` import `chat.ChatMessage`。

**应用服务枢纽（root 回边，v2 口径下 3 对环的成因；实测 8 条 import 边）**：
- `PetOverlayService` 被 6 文件 import（chat 1：ChatViewModel / ui 3：DebugBubbleReceiver、FullscreenVoiceScreen、SettingsScreen / voice 2：VoiceClientFactory、VoiceSessionManager）；
- `VoiceForegroundService` 被 SettingsScreen import 1 条；
- **`PetOverlayState` 非独立文件**——实为 `MainActivity.kt:122` 的 `object PetOverlayState { @Volatile var running … }`（**可变全局态**），被 SettingsScreen.kt:62 import；写入方全在 PetOverlayService（:243,771）、读者在 SettingsScreen（:527）——即审计 ocr[200] 的归属错放条目，且 service 死于 onDestroy 之外会 stale-true。
- 根包 5 文件：App.kt、MainActivity.kt、OverlayLaunchActivity.kt、PetOverlayService.kt、VoiceForegroundService.kt。

**大文件（>750 LOC，5 个）**：VoiceSessionManager 1208 / SettingsScreen 1055 / ChatViewModel 868 / AguiClient 819 / PetOverlayService 775。

## 4. 目标结构

### 4.1 分层模型（依赖只许向下；例外边=§4.3 白名单）

```
L6  app（root 不变）   App / MainActivity / OverlayLaunchActivity / PetOverlayService / VoiceForegroundService
L5  ui                 Compose 界面（→ 全部下层）
L4  chat               对话编排（→ api, voice, cloud, agui, device, util）
L3  voice              语音会话编排（→ api, cloud, device, util, agui）
L2  cloud              厂商实现（→ api, agui, net, util）
L1  agui / util / device / pet / net   协议客户端与现有叶子
L0  api（新建）         纯 Kotlin 契约类型：密封结果类型 / 常量 / 数据类 / 接口，零 android 依赖、零可变全局态
```

（v0.2 修正：agui 从 L2 下移至 L1——实测 `TextChatRouter` import `AguiClient`/`AguiSseConsumer` 存在 cloud→agui 横向边，agui 本身只依赖 net/util，放 L1 后该边合法向下。）

`api` 包下沉清单（全部从现位置**原样搬入，不改实现**；经对抗评审核实干净可搬）：
`AsrContract`（= `voice.AsrClient.Result` 嵌套密封类型 + `LANGUAGE_ZH` 常量）、`TtsContract`（= `voice.TtsClient.Result`）、`Message`（cloud）、`ChatMessage`（chat）、`BubbleOwner`（ui.overlay），以及搬入后补出的公共类型。**明确不入 api**：`AsrClient`/`TtsClient` 本体（同包直引 WavCodec/VadDetector，见 §3.3 缠绕 1 警示）、`PetOverlayState`（可变全局态，见 §4.3）。此风格与仓内现状一致（业务逻辑件普遍「纯 Kotlin 零 Android import」；构建配置实测**无 Hilt / kapt / KSP / dagger**，装配靠手写工厂 VoiceClientFactory / CloudClientFactory / AguiClientFactory）。

### 4.2 断环三刀（v0.2 按实证修订）

| 刀 | 动作 | 断掉的环 |
|---|---|---|
| 一刀 | `AsrClient.Result` + `LANGUAGE_ZH` 下沉为 `api.AsrContract`；`TtsClient.Result` 下沉为 `api.TtsContract`；voice/cloud 两侧引用改写（cloud 四文件改 import api）。**不动 AsrClient/TtsClient 本体**（同包直引 WavCodec/VadDetector，见 §3.3 缠绕 1 警示） | cloud↔voice |
| 二刀 | `ChatMessage`（chat）下沉 api；`TextChatRouter` 改 import api | chat↔cloud |
| 三刀 | `BubbleOwner`（ui.overlay）下沉 api；ChatViewModel、VoiceClientFactory 改 import api | voice↔ui、ui↔chat（下半环） |

一刀细节（v0.3 定案）：`Result` 密封类型移出后，既有 `AsrClient.Result.Success` 引用语法**无法**用 typealias 保留——类体内别名语法虽合法，但嵌套引用 `AsrClient.Result.Success` / `is …Result.Error` 全部 unresolved（kotlin-compiler-embeddable 1.9.24 实测，companion 内别名对外不可见，顶层别名须改限定名=仍是改写）。**唯一路径 = 全量改写引用**（cloud 侧 CloudClientFactory 约 15 处 + voice 侧；机械替换，编译器兜底）。`LANGUAGE_ZH` 为编译期常量，移动零行为差异。

三刀后 `voice→cloud`（VoiceSessionManager/WakeAckPrefetcher 用 CloudClientFactory）保留为**单向**，语义上 cloud 是语音厂商实现层、voice 是其编排层，方向自然（对抗评审核实一刀后 cloud→voice 零回边，无新环风险）。若 Phase 4 要 voice 与 cloud 完全解耦，再把工厂接口下沉 api + 注入（备选，不默认做）。

**预期效果（脚本 v3 验证，程序化可断言）**：环 4 对全清零；root 枢纽例外边保持 8 条不增；BuildConfig 产物边 1 条（Phase 4 前不治，见 §4.4）；其余跨包依赖 100% 自上向下。

### 4.3 应用服务枢纽处置（root 回边 8 条的归宿）

- **白名单（例外边登记制）**：`PetOverlayService`、`VoiceForegroundService`、`PetOverlayState` 三个 root 类型被下层引用共 **8 条 import 边**（ui 5 / voice 2 / chat 1），登记为架构例外随本 Spec 维护；验收锚=「环清零 + 例外边 **8 条不增**」。彻底接口化（下沉 OverlayApi）列为 Phase 4 备选。**理由**：引用全是取服务句柄/状态，接口化 diff 大、收益低；先以白名单换取 Phase 1/2 小步快跑。
- **`PetOverlayState` 不下沉 api**（v0.2 改判）：实为 `MainActivity.kt:122` 的 `object` + `@Volatile var running`——**可变全局态**，下沉会打破 L0「零可变全局态」定义。它的真问题是 ocr[200] 归属错放（声明在 MainActivity、写入方在 PetOverlayService、读者在 SettingsScreen；service 死于 onDestroy 之外 stale-true）——**Phase 1 同刀处置**：搬出 MainActivity 成独立文件（留 root 包），写入方契约显式化。**stale-true 防线守卫定义**（v0.3）：活性记账落在 `PetOverlayState` 自身（如 markStart / markClear / isStale 活性戳，纯 JVM 可测 object），`PetOverlayService` 侧薄接线——现状唯一守卫 onDestroy 清位（:771）只防正常销毁、不防异常死亡；`PetOverlayService` 不可 JVM 实例化（仓内无 Robolectric/androidTest），故测试对象 = `PetOverlayState` 逻辑本身，删守卫必红（变异锚）。层级不变、8 条例外边计数不变。

### 4.4 gradle module 化：门槛而非默认

**不承诺切 module**。单 module 内分层达标后，按以下门槛**逐包评审**，全过才切、切一包验收一包：

1. 该包出向依赖全部指向下层，且分层落地后 **≥2 周无环回流**（用附录 A 脚本周期复扫）；
2. 该包 + 其测试在临时 settings 里可独立编译（脚本可程序化验证；**net 因 BuildConfig 产物边当前不可独立编译，须先改注入**，见 §3.2）；
3. 装配工厂归属明确：本仓无 DI 框架，跨包装配全靠手写工厂——切 module 前须定清「工厂类随包走还是留 app 层、被装配接口在 api」，且**不引入 kapt/ksp 等新构建成本**；
4. 增量收益可测：修改该包典型文件触发的全量重编文件数有实测基线（切前切后对比）。

候选顺序（风险升序）：`api → util/device/pet → net → agui → cloud → voice → chat → ui`。**否决信号**：切完测试运行时间显著上升、资源跨 module 耦合、构建复杂度稀释收益——任一出现即停，维持单 module 分层。

### 4.5 大文件处置

>750 LOC 的 5 个文件（与 §3.3 口径一致）进「拆分评估清单」，处置原则：只按**已存在的内聚区块**做聚合拆分（如 VoiceSessionManager 按四态迁移/采麦调度/播放分块），**禁止顺手重写**；每个拆分独立提交、独立过测试。≤750 的不动。

## 5. 五维优化选料计划

> 原料 = 2026-10-03 全量审计 410 条 P3（七主题聚类：可维护性-契约-测试缺口 98 / 并发-线程 68 / 风格-文档 61 / 健壮性-安全卫生 61 / 性能-主线程-内存 48 / 错误吞掉 47 / 资源泄漏 14 + 并条独立 13）。审计口径声明：四通道（ocr glm-5.3-flash 机扫 462 条 + codex 5 条 + 人工代码精读 14 条 + 人工非代码 8 条），分诊人 Claude；跨通道独立命中=高置信。逐条可按 `p2-p3-detail.json` 的 ocr_indices 回溯。

### 5.1 结构性选料（与重构同刀，避免二次 diff）

| 选料 | 来源 | 处置 Phase |
|---|---|---|
| 断环三刀（§4.2）+ 4 对包级环 | 本次依赖分析（新发现） | 1 |
| PetOverlayState 归属错放：声明 MainActivity.kt:122 / 写入 PetOverlayService / 读者 SettingsScreen，service 异常死亡 stale-true | ocr[200]（现行码 :122/:243,771/:527） | 1（§4.3：搬出 MainActivity 成独立文件 + stale-true 防线测试） |
| 重复字面量/镜像常量簇约 13 处（口径注：评审 digest 整理清单，非审计原生目录；例证：RMS_FLOOR 逐字镜像、TtsVoiceDefaults 双份兜底、voiceChat 两段 AG-UI 块 F-VSM-6、ShelfNavIntentMatcher page id 四处裸写、ThinkCleaner "<think" 双写、PrefContract 包名字面量重复等）。~~TRUSTED_HOSTS 两份~~ **已收口**（P2 修复期已改单一常量两处消费，HttpClients.kt:25/39/48 实测；残留相关项只有 F-P3-6 代码白名单 vs NSC XML 两处清单，归 §5.5） | 审计重复簇 + digest | 2/3（移动到位后抽公共件，逐处独立提交） |
| 死代码簇 9 处（dead-code-like 口径：未使用导入 3-5 处、FALLBACK 死分支、死参数、死常量；点名为 digest 归纳） | 审计死代码簇 | 2（随搬移 commit 剔除，单独列清单） |
| 线程纪律 3 件：VoiceForegroundService 裸 Thread+手动 @Volatile+TOCTOU（审计行号 :119/:214，P2 修复后漂移至 :127/:225）、VoiceClientFactory 进程级 scope 无 teardown（:146 仍准） | ocr[205/202/53] | 2 评审定案 / 3 修（变异锚测试先行） |

### 5.2 语音链路体验

选料（voice 头部簇，全部带验收取证要求）：
- **KwsCore decode 线程族 7 条**（ocr[13-19]）：GapReset sentinel 被 drop-oldest 静默丢、onKeyword 异常误报 decode error、`Error` 逃出只 catch Exception 致线程静默死亡、running check-then-act、墙钟 now、release 中断传播、String.format locale（[17]）。
- **VoicePlayer 族 6 条**（ocr[424-429]）：stopped 永不复位（实例复用永久 IOException）、refPacerRunning 异常退出（[425]）、join(200) 超时窗、0 字节写当成功、空 PCM 建轨崩溃、drainUntil 墙钟回拨。
- **AmbientCalibrator 4 条**：TimeoutCE 逃逸/captureRunning 竞态/手动校准覆盖/充分性门槛（ocr[381-384]）。
- **真机体验挂账**（PROGRESS 在挂）：底噪、快语速——本维度验收一律**日志时间轴取证**（fed-decoded 水位、唤醒命中→应答音延迟）+ 真机语义验收，不只看单测绿。

### 5.3 工程质量

- **可维护性 98 簇头部**：PrefContract 族 5 条（键保全集无测试可拦/默认值只存在注释/Sprint 分组漂移/legacy 孤儿键）；「契约靠注释背书」族（TtsSanitizer 白名单、TtsVoiceDefaults、SessionRules timeoutMs）。
- **并发 68 簇头部**：裸 volatile + TOCTOU 族（BubbleHidePolicy、PrefetchBackoff、SettingsScreen 开关失步）；claim-then-use 非原子（VoiceClientFactory voiceRound，ocr[49]）。
- **健壮性补充**：WAV 解析负值/溢出同型族 4 处（WavCodec.kt:47、AliyunAsrClient.kt:163-168、VolcengineAsrClient.kt:177-178 等，审计跨通道并条 P3-WAV-FAMILY）。
- **测试缺口**：随 §5.1/§5.2 每个修复同步补**变异锚测试**（删掉守卫后测试必须实测变红），维持 754 只增不减、skipped=0。

### 5.4 内存与响应速度指标（协议先行；目标暂不设定，实测后议——领导 2026-10-10 拍板）

**测量协议（Phase 0 执行，全程复用）**：
- 内存：生产板 `adb shell dumpsys meminfo` 取 PSS。三态采样定义：空闲 = App 前台静置 5min 后；KWS 常开 = 开关开后 10min；**对话播报中 = 播报开始后第 3s 起连续 5 次快照（间隔 1s）取中位**（PSS 逐秒抖动大，单点不可判）。每态避开 GC 瞬时，记录 TOTAL PSS 与 Native Heap。**「播报开始」无现成日志锚**（VoicePlayer 仅播放后 logged、TtsClient 仅请求前、VSM 无播报开始行）——补一行起播日志锚（VoicePlayer 起点）列为 Phase 0 交付物，与悬浮窗渲染锚同批。
- **抖动带宽前置**：Phase 0 测出每态抖动带宽入基线表——后续任何目标值讨论以此为分辨力依据。
- 响应：
  - 唤醒词命中 → 应答音出声：logcat 内**设备钟**差值（板钟比 PC 慢 ~3.5 分钟，**严禁跨 PC/板钟求差**）。
  - 打开悬浮窗 → 首帧可见：现有日志止于服务启动/addView（无渲染锚），以 addView 为下界 + 录屏逐帧为上界，**两者并报并声明测量噪声**；补一行渲染回调日志锚列为 Phase 0 交付物（最小可观测性补充，行为零变化）。
  - Chat 发送 → 首个 token 上屏：logcat 设备钟差值。
- 基线表入 `docs/audit/2026-10-10-perf-baseline/`。**仓内无 androidTest/benchmark 源集**（构建配置实测），dumpsys/日志/录屏即协议上限，不新建测试基建。

**目标值：暂不设定**（领导 2026-10-10 拍板）。执行口径：
- 唯一守门线 = §2「各 Phase 复测**不回退**」；
- 改善抓手照做（性能簇头部：PetLogger 全局锁逐行 IO、MarkdownContent 每帧重测列宽、PetView 静止逐帧 invalidate、AsrClient 不可取消，以及无界缓冲、AsrClient PCM 双份驻内存等），每项修完随进度**报告实测值**（修复前/后同协议对比）；
- 改善目标值待 Phase 0 基线 + Phase 3 实测数据出来后，由领导拍板再补入本节。

### 5.5 安全与发布形态（施工输入，决定权在附录 B）

候选清单（拍板前**不动代码**）：DebugBubbleReceiver exported 常驻（P3-DEBUG-BUBBLE，三通道高置信）、crash-*.log 无轮转无上限（P3-CRASH-LOG 双通道）、OpenAITtsClient baseUrl 误配 http 明文送 key（ocr[119]）、内网 IP 硬编码进源码（ocr[175]）、内网主机白名单两处手工维护（F-P3-6：HttpClients 代码清单 vs network_security_config XML）、分发三件套（SEC-1/4/5 打包一个安全决策）。SEC 系列 4 项拍板后按附录 B 结论进 Phase 3。

## 6. 施工顺序

| Phase | 内容 | 验收锚 | 并行？ |
|---|---|---|---|
| 0 | 依赖分析脚本 v3 + JSON 入库（`docs/audit/2026-10-10-dep-analysis/`）；§5.4 指标基线（含悬浮窗渲染锚 + 播放开始日志锚补点、抖动带宽前置）；PetOverlayState stale-true 守卫机制定案（§4.3：活性记账落 PetOverlayState）+ 其 JVM 测试 + 线程纪律 3 件守卫测试设计（**真守卫可删红**；断环本身无运行时守卫，结构守卫=脚本，不造伪需求） | 基线表落盘；守卫机制定案 + 测试红-绿证明；锚点补点清单；脚本入库 | 否（主对话） |
| 1 | 断环三刀（§4.2 修订版）+ PetOverlayState 搬出 MainActivity（§4.3） | §2 全锚；脚本 v3 环清零 + 例外边 8 不增 | 否（主对话，强耦合） |
| 2 | 包内重组：voice 32 文件子包化（按现有内聚建议 pipeline / kws / wakeack / echo / seam / policy 六组，施工票定稿）、ui screens 梳理、根层 PetView 等归属归位；大文件按 §4.5 拆分评估；§5.1 死代码簇随搬移剔除 | §2 全锚；**搬移 commit 与行为改动 commit 分离**：搬移 commit 含 package/import 行级联（同包直引处必须补 import，故非纯 rename），以 git rename 相似度跟踪、每个 commit 编译必须绿 | 否 |
| 3 | 五维 P3 择优消耗：§5.1 剩余重复簇 + §5.2 语音族 + §5.3 工程族 + §5.4 性能四簇 + §5.5（SEC 拍板后输入）；跨包条目按 §7.1 归主对话 | 单测只增 + 各条目自带验收（修复配变异锚测试；性能条目配 §5.4 指标复测） | **是（地界=包/子包，任务书模式 §7）** |
| 4 | module 化门槛评审（§4.4，net 的 BuildConfig 边须先改注入才可独立编译）；Hub 彻底接口化评估 | 门槛 4 条全过才动；否决即停 | 评审后定 |

**部署约束**（各 Phase 真机复测适用）：测试机 192.168.137.76 离线，语义验收只打生产板 `10.0.14.18:5555`；生产板为 desk-pet+mijijia 双包同机，10-09 入口功能两板均未带——**直拉悬浮窗链路验收须双包成对部署**（回灌法换包 + 逐次授权 + 回滚保险见 §8-6）。

回滚策略：每 Phase 独立分支（`refactor/p0`…`refactor/p4`），验收不过整 Phase 回滚，不补丁式修补。

## 7. 多 Agent 协同机制（本轮只谈机制，定稿供 Phase 3 用）

1. **地界规则**：Phase 0-2 主对话串行（强耦合）；Phase 3 起地界 = 包/子包归属（文件按 `app/src/main/java/com/xiaoduo/deskpet/<包>/` 前缀切分）。**跨包条目例外**（v0.2）：抽公共件落 util/api、镜像常量对齐、断环点等天然跨地界的条目，默认收归主对话或立独立「公共件」票据串行处理，不进并行地界——宁可局部串行，不做地界含糊的伪并行；voice 单包 32 文件负载大，Phase 2 子包化完成后按子包切票。
2. **任务书**（`leader` skill 出）：≤4000 字符硬上限；每条命令须出书人亲手跑过；内嵌可证伪验收标准（测试数/dumpsys/日志原文，不接受「搞定了」）。
3. **隔离**：并行 agent 各用独立 git worktree（gradle 不抢锁），地界按文件前缀 + 程序化验证冲突（两 worktree 同文件即冲突，主对话合并）。
4. **双通道机扫**：`ocr scan`（全文件模式，`--path` 逗号多路径，json 的 `comments[]` 取 findings）+ `codex-security-scan`（exit 2 credential-home = 已知误报视为成功；**Deep 模式有 bug 已降级默认模式**）。429/1302 配额风暴 = 降并发 ≤2 分轮补扫。
5. **评审-修复刹车**：findings 先 `triage` 分级；最多 3 轮；连续 2 轮无进展换思路；机制类矛盾 finding 由主对话一次性实测仲裁。
6. **指标口径纪律**：声称「N 绿」必须带 JDK PATH 前缀 + `--rerun-tasks`；reviewer 用异构大脑（opus + high effort），只收 diff + spec + 测试输出，不收 generator 自辩。

## 8. 红线（重申，全部实测出血过）

1. **OverlayLaunchActivity 透明 trampoline** 是跨应用直拉悬浮窗唯一可靠通道，勿复活 v1 MainActivity-extra 路径。
2. **ColorOS 跨应用 startService 不可信**（前台仍被判后台启动），跨应用拉服务一律走 trampoline。
3. **签名权限墙**：`<permission protectionLevel="signature">` 必须在 manifest 直属；生产包与同事签名包互不可装。
4. **kotlinx 1.8.1**：满 Channel 必须 `cancel()` 不能 `close()`；机制类怀疑先实测依赖版本/ABI 再动手。
5. 10-09 入口职责划分（系统设置拉 APP / 基础设置直拉悬浮窗 / 长按菜单删「打开 App」）不得回退。
6. 生产板破坏性操作（卸载/清数据）逐次授权；回滚保险 `D:\mijijia\.scratch\prod-mijijia-data-backup-20261009\`。

## 附录 A：依赖分析复跑

```
MSYS2_ARG_CONV_EXCL="*" python "E:/desk-pet-android/.scratch/dep-analysis-20261010.py"
```

产物：`.scratch/dep-analysis-20261010.json`（文件级全量 + 矩阵文件链接清单）。Phase 1/2 验收与 §4.4 周期复扫都用同一脚本，口径一致才可比对。

**脚本版本史**：v1 = 首扫（import 侧 root 类成伪节点，环检测漏 root 回边）；v2 = root 归并对称化（暴露 7 对环，含 root 枢纽 3 对与 BuildConfig 假阳性链）；v3 = 白名单例外边分离（`WHITELIST_ROOT_TYPES` 3 类 → hub 边计数 8；BuildConfig → buildgen 边计数 1；环检测只剩强包 4 对）。Phase 0 入库时以 v3 为准，白名单增删必须同步改本 Spec §4.3。

**口径小注**（第二轮复审备案）：矩阵计数 = 每文件去重 import FQN 数（非物理行数）；白名单按短类名匹配，`X.Companion.Y` 形态 import（全仓现 0 条）理论上会逃出白名单成幽灵组——复扫若现未知组名先查此。

## 附录 B：待拍板项（已拍板 2026-10-10 深夜，见下）

> 依据 `fix-phase-dispositions.md` 末节 + 审计 digest 复核。**注意**：handoff 所列「5 项」与实况有出入——其中「af-integration 是否合入 main」已关闭（40 条 P2 已合 main，752 绿），且「分发形态三件套」是 SEC-1/4/5 一个安全决策包，非三项独立议题。以本清单（4 开放 + 2 挂账）为准。

**开放 4 项 → 已拍板（2026-10-10 深夜，领导令「按推荐来，以效果为主」）**：

1. **SEC-1 维持 P2**（按审计推荐）：利用前提=进入板子所在内网网段，且自签白名单属项目文档明示设计；**不施工证书锁定**。重估触发器：分发形态变化（对外部署/离开受控内网）时重开。
2. **SEC-2 关闭（部署事实不成立，实测）**：2026-10-10 PC 侧实测 443 反代——GET 全回 200（前端 SPA fallback），**POST /login = 405**（反代未路由登录 API），切了即坏登录；8080 直连正常。维持 8080；**重开条件 = 反代补 /login POST 路由后**（届时一行改动）。
3. **SEC-3 批准**：引入 `security-crypto` 依赖做 EncryptedSharedPreferences 迁移，Phase 3 施工（效果优先）；迁移须含旧值读取兼容 + 一次性搬迁 + 跨重启验证。
4. **P3-2 静默降级**：磁盘 degraded 时 VM init 降级运行（不崩溃）+ 显式日志 + **降级期禁写防数据损坏**（只读保护，兼顾 DATA-1 初意的数据完整性）；Phase 3 施工（策略纯函数 + 变异锚测试）。

**挂账待核实 2 条**（非决策，需有人查证）：ocr[161] `NetConfig.saveAll` 是否存在部分参数调用方（有则升 P2 数据丢失）；ocr[375] AsrClient JVM org.json CCE 是否因生产构建捆绑 org.json:json 而升 P2（依赖清单未核）。

**销账交代**（审计期第五节复诊清单逐项去向，v0.2 补）：SEC-1/SEC-2/SEC-3/P3-2 = 上列开放 4 项；~~af-integration 合入~~ = 已关闭（合 main 752 绿）；SEC-6 = 证伪关闭（runId 客户端生成，降 P3）；ROB-16 = 证伪关闭（kotlinx 1.8.1 `CoroutineScope(Dispatchers.IO).cancel()` 实测正常，机制教训已入 §8-4）；CONC-1 = 保留 P2 并入 40 条修复，无开放决策；WAV 解析族（4 处同型）= 维持 P3，已列 §5.3 工程质量候选池。
