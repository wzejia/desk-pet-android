# Phase 4 module 化门槛评审（spec §4.4 / §6，2026-10-11）

> 评审对象：`api → util/device/pet → net → agui → cloud → voice → chat → ui` 十包切 module 门槛。
> 数据基线：main @ 999154c（859 绿）；依赖复扫 `.scratch/dep-analysis-20261010.py` 同日重跑。

## 结论（verdict）

**本周期否决切换。** 门槛 1 的时间维度（分层落地后 ≥2 周无环回流）客观未满：Phase 1 断环落地
2026-10-10，窗口至 **2026-10-24**，评审日为第 1 天。spec §4.4「全过才切、否决即停」。

**复评时点 ≥2026-10-24**，程序：附录 A 脚本周期复扫（环须持续为 0）→ 按本文前置工程清单核对
→ 逐包按候选序切、切一包验收一包（含真独立编译 + 本文两把尺复测）。

## 四门槛现状

| 门槛 | 现状 | 判定 |
|---|---|---|
| 1. 出向依赖全向下 + ≥2 周无环回流 | 结构 ✓（环 0 / 例外边 8 不增 / buildgen 1 已知）；时钟 ✗（1/14 天） | **未过（时钟）** |
| 2. 包+测试可独立编译（闭包代理） | PASS：api / device / pet / agui（net 修 BuildConfig 后 PASS）；详见下 | **4 PASS / 6 阻** |
| 3. 装配工厂归属 | 模式已一致：工厂随被装配包走（AguiClientFactory@agui、CloudClientFactory@cloud、VoiceClientFactory@voice），调用方向全向下；无 kapt/ksp | ✓（无需新决策） |
| 4. 增量收益基线 | 已建双基线（见附录 A、B） | ✓（已可测） |

### 门槛 2 闭包明细（脚本 `.scratch/phase4-gate2-closure.py`，候选序 DAG 判定）

- **PASS**：`api`（零依赖）/ `device` / `pet` / `agui`（只依赖序前包）
- **net**：仅 BuildConfig 产物边 1 条（`NetConfig.kt`）——修注入后 PASS（见前置①）
- **test 放置违规 3 文件**（主源全净，测试拉了序后包）：
  - `util/NetEndpointsTest`、`util/TokenPolicyTest` → `net`（对账测试，切时随 net 侧）
  - `cloud/CloudClientsExceptionBoundaryTest` → `voice.client.TtsClient`（切时改 api 契约或随 voice 侧）
  - `chat/DrawerTimeLabelTest` → `ui.screens.relativeTimeLabel`（切时随 ui 侧）
- **hub 硬阻断 8 边**（root 类型被下层 import，独立编译不可能）：voice 2 文件（VoiceClientFactory/VoiceSessionManager）、chat 1（ChatViewModel）、ui 4 文件（DebugBubbleReceiver/FullscreenVoiceScreen/SettingsKws/SettingsScreen）

> 口径说明：门槛 2 的「真独立编译」只能在切换验收时以临时 settings 实证；本表为 spec 所称
> 「脚本可程序化验证」的闭包代理（import 全集落在候选序前包+自身）。

## Hub 彻底接口化评估（voice/chat/ui 的硬前置）

8 条边的**面**（grep 全量清点）：
- `PetOverlayService.instance` 句柄 13 处（气泡/播报控制）+ companion 直调 4 法
  （setPetState ×2 / chatNewSession ×2 / chatInterrupt ×2 / chatBusyProvider ×2）
- `VoiceForegroundService` 静态钩 2（kwsSink / onWake，SettingsKws）
- `PetOverlayState` 成员 5（markStarted/markHeartbeat/markCleared/isAlive/HEARTBEAT——Phase 1 已纯 JVM 可测）

评估：接口面 ~20 成员，api 包加 2-3 个接口（OverlayApi / ForegroundControl / state 下沉或接口化）
约 50-100 LOC，root 侧实现 + 8 个消费文件改写，diff 中等、机械。voice 已有 BubbleSink/指示层
抽象缝（S3/S49），剩余 setPetState 直调 4 处顺路收编。**收益 = 解锁 voice/chat/ui 三包
（5,054+1,945+5,423 = 60% 代码量）的切分前置；不做的代价 = 三包永久不可切。**

建议：复评通过后随第一张 voice/chat/ui 切分票**同刀**施工（不单独预研——消费面已清点完毕，
无未知数）。

## 前置工程清单（复评前可做，互不阻塞，均不碰红线）

| # | 项 | 解锁 | 量级 |
|---|---|---|---|
| ① | net BuildConfig 注入改造（spec §4.4 明示前置） | net | 小（NetConfig 单文件+1 测试） |
| ② | 3 个测试放置归位（util×2→net 侧、cloud×1、chat×1） | util / cloud / chat | 小（纯搬迁/改 import） |
| ③ | （可选）hub 接口化 | voice / chat / ui | 中（见上评估） |

## 附录 A · 门槛 4 双基线（仪器说明）

IC 内部脏集文件数无公开读数（build-history.bin 二进制）——以两把可复算的尺作基线，切后同尺复测：

1. **结构传播面**（import 图组级反向闭包，main 125 文件）：

   | 触碰包 | 重编上界（文件） | 传播到 |
   |---|---|---|
   | api | 102 | root/chat/cloud/ui/voice |
   | util | 117 | 全部 |
   | device/pet | 93 | root/chat/ui/voice |
   | net | 106 | root/agui/chat/cloud/ui/voice |
   | agui | 102 | root/chat/cloud/ui/voice |
   | cloud | 98 | root/chat/ui/voice |
   | voice | 89 | root/chat/ui |
   | chat | 53 | root/ui |
   | ui | 37 | root |

2. **实测增量墙钟**（热 daemon，ABI 真实变更探针，`compileDebugKotlin`）：
   - api 层（AsrContract LANGUAGE_ZH 值变更）：**1.9s**（IC modifiedFiles 精确跟踪实证）
   - voice 层（PrefetchBackoff DEFAULT_WINDOW_MS 变更）：**1.5s**
   - 读数：单 module 热态下增量编译本身很快（梯度平缓），module 化的主要收益不在热态墙钟，
     而在冷态/CI 全量与编译隔离——切前切后**两把尺同测对比**，任一显著劣化即 §4.4 否决信号。

   探针即改即还原（git checkout），工作树零残留；测试未受污染（999154c 859 绿基线不动）。

## 附录 B · 否决信号备忘（切时逐包核对，spec §4.4）

切完任一包出现即停、回滚该包：测试运行时间显著上升 / 资源跨 module 耦合（ui 的 res 归属、
Compose 主题共享须切时定案）/ 构建复杂度稀释收益。
