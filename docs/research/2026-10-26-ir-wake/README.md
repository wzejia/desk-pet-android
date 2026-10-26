# 红外触发小朵唤醒 · 调研报告（2026-10-26）

> **起因**：同事提供两份素材——`20230728-ysapi(3).zip`（易声 API SDK 包：`ysapi.jar` + 开发文档 PDF V5.4 + demo 工程源码 `app.zip` + demo APK + 中文原版协议 doc）与 `API demo.apk.1.1.1`。目标：调研如何实现「红外触发小朵唤醒」。
>
> **方法**：双子智能体并行只读调研（① SDK/APK/文档逆向分析，② 小朵唤醒链梳理），全程未执行素材内任何代码、未触碰设备/模拟器（jar/dex 只 javap/strings/aapt 静态分析）。
>
> **详细证据**：同目录 `appendix-ysapi-sdk-findings.md`（SDK 逆向全报告，逐结论附命令原文/文件:行）。原始素材隔离存于 `.scratch/ir-research/`（不入库）。

---

## 结论速览

1. **ysapi 定位**：深圳易声科技（www.yishengtec.cn）**瑞芯微板卡系统控制 SDK**（文档号 YS-API-001，V5.4-20230728）。本体是纯 binder 客户端，绑定固件内置系统应用 `com.ys.ys_receiver/.AIDLService`（回退 `com.yishengkj.achieve`）；product 类覆盖 RK3288/RK3328/RK3368/RK3399/PX30/RK3566/RK3568 等——与本项目密集架板卡（rk3288/rk3568 系）硬件族吻合。能力面=系统控制（开关机/显示/网络/存储/GPIO/定时开关机/休眠/串口路径查询/开机自启/守护进程），与音频无关。
2. **关键否定结论：SDK 不存在红外事件通道**。`jar tf` 全部 55 类零 ir/infrared/remote 命名；唯一回调接口 `ServiceConnectedInterface.onConnect()` 仅通知连接成功；`Constant` 约 20 个广播 action 全是**应用→系统**控制指令（如 `com.ys.set_static_ip`）、零事件上报；demo APK manifest 仅 4 个 activity、零 receiver 零 service。**「收到红外码」这一事件抽象在本平台不存在**，因此「App 监听红外广播/SDK 回调」这类捷径全部不可行。
3. **可行路径 = 把「红外」还原为 GPIO 电平**：红外感应器（PIR 人体感应/红外对射，输出电平信号）接板卡保留 IO → `setGpioDirection(n, 1)` 置输入 → 应用**轮询** `getGpioValue(n)`（AIDL）或 `GPIOUtils.readGpioPG("/sys/...")`（sysfs 直读，需 root）——官方 demo `GpioActivity1.java:82-146` 即同款点按式读取，GPIO 无中断回调。触发后可叠加 `awaken()` 唤醒休眠中的系统。次选：串口模块接 UART 自读（SDK 只给 `getUartPath` 路径查询、读写自实现，需 root，工作量大）。
4. **小朵侧注入点已勘明，外部唤醒是单点接入**：任何线程调一行 `VoiceClientFactory.session(context).startListening(playWakeAck = true)` 即获得与喊「小朵小朵」**完全同构**的四态行为（见第五节）。守卫红线三条：勿直接碰 `kwsPaused`、勿绕过 `cancelCurrentTurn` 封轮、勿手写复位 `wakeAckActive`。
5. **推荐集成方案**：`VoiceForegroundService` 内独立协程做 GPIO 轮询（独立 pref 开关 + try-catch 隔离防 SDK 异常连坐唤醒链）→ 电平跳变去抖 → `startListening(playWakeAck = true)`。落点理由：VFS 的存在语义就是「可被唤醒」（START_STICKY + MainActivity 自愈，进程活性最强）；PetOverlayService 可被用户合法停服（隐藏悬浮窗），挂它会导致外唤醒随隐藏失效。

## 待同事确认（阻塞选路的三件事）——✅ 已全部确认（2026-10-26 用户回话）

> **确认结果**：①硬件=**PIR 人体感应**，接**第 1 路保留 IO**（GPIO1 输入）→ 路径 A 落定，AIDL 调用传 `1`（V5.2+ 语义=第 N 路，无需底层 GPIO 编号；仅 sysfs 直读备选路才需要向易声索取具体编号）；②目标板=**易声固件**（`com.ys.ys_receiver` 在位）→ 方案前提成立。三问清零，具备立票条件。

| # | 问题 | 为什么重要 |
|---|---|---|
| 1 | 「红外」硬件实物形态是什么？**PIR 人体感应/红外对射**（电平信号→GPIO）？**红外遥控器**（走 kernel 输入子系统按键，与 ysapi 无关且无任何证据支持）？**串口红外模块**（走 UART 自读）？ | 三种形态对应三条完全不同的路，SDK 证据只支撑第一种 |
| 2 | 目标板卡是否**易声固件**（内置 `com.ys.ys_receiver`）？ | 非易声板卡 bind 必失败、全部 API 不可用，方案前提崩塌 |
| 3 | 目标板**保留 IO 路序表**？V5.2+ 起 `getGpioValue` 传 1..N 表示第 N 路保留 IO（非真实 GPIO 编号）；文档仅举例 RK3288-M8：139/138/68/69 → 1/2/3/4，未给全表 | 不知道感应器接哪路、轮询传什么参数 |

---

## 一、素材清单

| 素材 | 内容 | 备注 |
|---|---|---|
| `ysapi.jar` | SDK 本体，55 类（`com.ys.rkapi.*` / `com.ys.myapi.*`），零第三方依赖（纯 framework binder） | javap 全量分析 |
| `Yisheng API Development Document.pdf` | 英文版开发文档 V5.4，10 章 3042 行 | pdftotext 全文提取 |
| 两个乱码名 .doc/.pdf | 同一文档的**中文原版**（YS-API-001 同修订史 V1.0-20180602→V5.4） | UTF-16LE 明文扫描取证；中文字体子集化致 PDF 文本层不可提取 |
| `app.zip` | demo 工程源码（6 个 Java 文件） | 与 dex 字符串一致，以源码为准 |
| `API demo.apk`（包内）与 `API demo.apk.1.1.1`（同事单发） | 同包名 `rkandroidapi.ys.com.rkandroidapi` v1.1，minSdk 17 / target 30 | md5 不同（`55199572…` vs `132a8b1e…`）但 badging 完全一致——重签名/重打包关系，内容同源 |

## 二、ysapi SDK 是什么

- **架构**：App 依赖 ysapi.jar → binder 绑定固件内置系统应用 `com.ys.ys_receiver` 的 `AIDLService` → 系统侧代执行 sysfs/属性/root 命令。初始化三步（文档第 1 章）：`MyManager.getInstance(ctx)` → `bindAIDLService(ctx)` → `setConnectClickInterface(onConnect)`；销毁必须 `unBindAIDLService`。
- **鉴权/配对**：无。任何应用 bind 即用；root 能力经 `execSuCmd` 由系统侧代执行。
- **关键方法**（javap `com.ys.rkapi.MyManager`）：`awaken()`（软件主动唤醒休眠系统）、`setDormantInterval(ctx, ms)`、`setGpioDirection(n, 0/1)`、`getGpioValue(n)`、`writeGpioValue`、`exportGpio`、`getUartPath("TTYSx")→"/dev/ttySx"`、`execSuCmd`、`selfStart`（开机自启）、`daemon`（进程守护）。

## 三、「无红外通道」的四条证据

1. **类面**：`jar tf` 55 类 + 全量 javap，无任何 IR/红外/遥控语义命名；唯一监听器是连接成功通知。
2. **文档**：英文 PDF 3042 行 grep `infrared|IR|remote|红外|遥控` 零命中（仅休眠唤醒 awaken/setDormantInterval 与软键盘）；中文 doc 二进制双编码计数：红外=0、遥控=0。
3. **广播**：`Constant` 约 20 个 action（javap 反汇编 static 块）全部是**应用→系统**控制指令，无一个事件上报型。
4. **demo**：manifest 零 receiver 零 service，唯一 intent-filter 是 LAUNCHER；dex 字符串无红外语义。

## 四、触发类输入的三条到达路径对比

| 路径 | 做法 | 前提 | 评估 |
|---|---|---|---|
| **A. GPIO 电平轮询**（推荐） | 感应器接保留 IO → `setGpioDirection(n,1)` → 循环 `getGpioValue(n)`（或 sysfs 直读） | 设备=易声板卡；感应器物理接线；需保留 IO 路序表 | **官方唯一贴合路径**；轮询非中断（demo 同款）；AIDL binder 多客户端无独占问题 |
| B. sysfs 直读（A 的变体） | `GPIOUtils.readGpioPG("/sys/class/gpio/gpioN/value")` | root（或固件放开权限） | 与 A 等价但更脆；仅 AIDL 不可用时考虑 |
| C. 串口自读 | `getUartPath` 拿路径 + 自实现 UART 读 | /dev/ttySx 权限（root）；模块须串口协议输出 | SDK 零支持、工作量大；仅串口型模块才有意义 |
| D. 监听广播/SDK 回调 | — | **不存在**（第三节证据） | 不可行 |
| E. 非易声板卡用 ysapi | — | bind 失败全 API 不可用 | 不可行 |

## 五、小朵侧：唤醒链与注入点（已勘明）

**唤醒链**（KWS 检出 → 聆听）：

```
KwsEngine.onKeyword → VoiceForegroundService.onWake()（VoiceForegroundService.kt:203-226，
  destroyed 守卫 + VoiceStateRouter.decide 多词路由）→ VSM.startListening(playWakeAck=true)
  （voice/VoiceSessionManager.kt:422-460）→ playAckThenListen()（:469-523：wakeAckActive
  → WAITING → 播「我在」→ 尾音衰减窗 → vad.start → LISTENING）
```

**外部触发复用同一单点的四态现成语义**（零新增状态机逻辑）：

| 触发时状态 | 行为 | 依据 |
|---|---|---|
| IDLE | 播「我在」→ 进 LISTENING | VSM:446-459 |
| LISTENING | S33 重唤醒：取消本轮→重播「我在」→重新聆听 | VSM:440-442, :542-545 |
| SPEAKING/PROCESSING | S12 barge-in：封轮→停播→「我在」→听新命令 | VSM:432-434, :535-563 |
| 点按路径对照 | `startListening()`（playWakeAck=false）不播「我在」；LISTENING 态为 no-op | VSM:443；ChatScreen.kt:668-689 |

**守卫红线**（外部触发代码不得触碰）：
- `kwsPaused`（只门 IDLE 态麦块路由）：置位仅在 startListening:446，复位只准经 backToIdle；
- `TurnFlags.sealed`：封轮必须在 stop 之前，且 cancelCurrentTurn 幂等（IDLE 分支 no-op），重复触发无害；
- `wakeAckActive`：只能经 playAckThenListen/cancelCurrentTurn 生命周期管理。

**候选落点对比**：

| 方案 | 落点 | 评估 |
|---|---|---|
| **B. IR 轮询挂 VoiceForegroundService**（推荐） | VFS 内独立协程，触发后与 `onWake()` 同款调 `session.startListening(playWakeAck=true)`（:225 同款） | 与 KWS 回调同源同生命周期，进程活性最强；需 try-catch 隔离防 SDK 异常连坐 |
| A. BroadcastReceiver 收自定义 action | 复刻 `DebugBubbleReceiver` 先例（AndroidManifest.xml:74-82），签名权限把守 | 先例最完整、与 VFS 解耦；进程被杀时广播不达（VFS START_STICKY 缓解）；本场景多此一跳（轮询就在本进程） |
| D'. startService 命令 action | PetOverlayService onStartCommand 加 action | 调用方需前台；且悬浮窗可被用户停服——不可靠 |
| C. KeyEvent 拦截 | — | 悬浮窗非 focusable 收不到；需 FLAG_FOCUSABLE 破坏触摸语义。不可行 |

## 六、推荐集成设计（草图，未实施）

```
VoiceForegroundService（生命周期驱动）
 └─ IrWakeController（新组件，独立可关）
     ├─ 生效条件：Prefs.irWakeEnabled 且 bindAIDLService onConnect 成功（非易声板静默禁用）
     ├─ init：setGpioDirection(N, 1) 置输入
     ├─ 轮询协程（100–200ms）：getGpioValue(N) → 电平跳变 + 去抖（连续 2 次同值认定）
     │    └─ 触发：[若休眠态先 awaken()] → session.startListening(playWakeAck = true)
     ├─ 可选产品口径：仅 IDLE 态响应（防 PIR 反复触发打断播报）——四态语义现成，加不加是产品题
     └─ onDestroy：取消协程 + unBindAIDLService
```

**风险与对策**：

| 风险 | 对策 |
|---|---|
| GPIO export/方向多应用互踩 | 小朵前台服务**独家管理**所用 IO（AIDL 本身多客户端共享无独占） |
| SDK 异常连坐唤醒链 | 轮询全程 try-catch，失败降级=红外通道静默禁用，KWS 主链不受影响 |
| **深度休眠期轮询失效**（重要边界） | 系统休眠时 CPU asleep，用户态轮询协程停止——「休眠中靠红外唤醒整机」需要固件级 GPIO 中断唤醒支持，SDK 轮询做不到。若需求含休眠唤醒，须与易声确认专用 wakeup IO/方案（`awaken()` 只是软件调用，前提是 App 还活着） |
| PIR 误触发/反复触发打断播报 | 去抖 + 冷却窗；或产品口径「仅 IDLE 生效」（pref 可配） |
| 与 KWS 开关耦合（VFS 停则红外灭） | 产品口径：红外唤醒是否要求「语音唤醒」总开关开启；必要时拆独立 pref |

**验收口径建议**（可证伪）：
- 可见行为：易声板上触发感应器 → 小朵播「我在」进聆听，logcat 出现 `wake-ack`/`transition LISTENING`；
- 反例样本：①播报中触发（预期=产品口径定义的行为）②非易声板（预期=轮询静默禁用零 crash）③连续快速触发（预期=去抖/冷却生效）④`irWakeEnabled=false`（预期=完全不轮询）；
- 禁改约束：半双工状态机与三条守卫零改动；
- 可接受权衡：轮询延迟 ≤200ms（中断不可得）。

## 七、证据索引

- SDK 逆向全报告（本目录）：`appendix-ysapi-sdk-findings.md`（含全部命令原文与文件:行）
- 原始素材与中间产物：`.scratch/ir-research/`（ysapi 解包 / apk 解包 / app.zip 源码 `app-zip-src/` / 分析脚本 `scripts/`；不入库）
- 小朵侧 file:line 证据：见第五节各标注（VoiceForegroundService.kt / VoiceSessionManager.kt / AndroidManifest.xml / PetOverlayService.kt）
- 附带勘误：PROGRESS.md 2026-10-09 条目所记 `extra_launch_overlay_only` 在当前代码已不存在（全库 grep 无命中），实际直拉入口为 `OverlayLaunchActivity`（签名权限 trampoline）
