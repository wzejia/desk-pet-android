# 小朵 Android 开发日志


## 2026-10-26（三）S70 板测触发率偏低根因修复：冷却只在真触发时起表（839 绿=816+23，M6 锚真红）

**根因**（板测实锤+代码对账）：被 idleGate 丢弃的上升沿也经 filter 记 30s 冷却起点（首轮 critic P3-5 判「保守无害」，板测证伪为主凶）——用户在板前持续挥手时每个被吞边沿都重起冷却表，唤醒间隔实测 33-65s ≈ 聆听周期+30s 冷却，与「连续 30s 无新边沿才放行」模型吻合（用户手动 cancel 的「cancel listening」已排除非嫌疑）。**修复**：arming 契约反转——`PirEdgeFilter.onSample` 只判定不起表，新增 `armCooldown(nowMs)` 由 controller 在过 IDLE 门、真调 onWake 时才调；被吞边沿零副作用。仅 IDLE 口径/30s 冷却/2 样本去抖/200ms 轮询全部不动。TDD 先红（旧实现下「门丢弃边沿#1→IDLE→边沿#2 立即触发」用例红）后绿；**M6 变异锚**（armCooldown 移过门=旧缺陷形态）真红；M1-M5 不退化。终局 **suites=110 tests=839 failures=0 errors=0 skipped=0**（编排亲跑 XML 复算与 builder 一致）。新包 md5 `b3783de1` 上板 140，14:39:44 轮询复位、14:39:46.9 即触发（修复前同场景 30s+ 死区）。用户现场复测待回收。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
verify_claim: suites=110 tests=839 failures=0 errors=0 skipped=0；VSM diff 0 行；M6（arming 移过门）真红；新包 b3783de1061012de18e9782f869b314a 上板 140 后 2.3s 即见 rising edge 触发
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ir/PirEdgeFilter.kt, app/src/main/java/com/xiaoduo/deskpet/ir/IrWakeController.kt, app/src/test/java/com/xiaoduo/deskpet/ir/PirEdgeFilterTest.kt, app/src/test/java/com/xiaoduo/deskpet/ir/IrWakeControllerTest.kt, docs/DEV_LOG.md, docs/implementation-notes.html, PROGRESS.md, CONSTRUCTION.md]
```

## 2026-10-26（二）S70 PIR 红外唤醒全链收口：调研→引库→实现→评审→板测 e2e 当日闭环（836 绿=816+20，五变异锚真红）

**链路**：同事供易声 ysapi SDK → 双子智能体逆向实锤 SDK 无红外事件通道（可行路径=PIR 接保留 IO 电平轮询，报告 `docs/research/2026-10-26-ir-wake/`）→ 用户拍板四口径（仅 IDLE 响应/30s 冷却/开关默认关/引库批准）→ ysapi.jar 入库（md5 `aa2f3892e99f3691e5e262f5bd166335` 锚定换必重审）+ dependency-auditor P1=0（P2 execSuCmd 本地 root 脚枪→源码扫描契约测试落地）→ builder TDD 实现（`ir/PirEdgeFilter` 纯逻辑三道防重 + `ir/IrWakeController` GpioReader seam 全链 JVM 可测 + VFS/Prefs/SettingsScreen 接线；**VSM 零 diff**）→ critic（opus，代码正确型）不逊 8 项全≥4/5 → 修复轮 P2-1 竞窗（stop/onConnect 并发孤轮询）一行修+M5 确定性锚 + P3×4 落地（P3-4 strip 状态机三引号限制记 notes 不修）→ 编排亲跑终验 **suites=110 tests=836 failures=0 errors=0 skipped=0**（python XML 复算与 builder 自报一致）。

**板测（192.168.137.140 rk3568_r 易声固件）**：IO1=gpio154（厂商 demo 日志实锤）；PIR 电平活体翻转（10s 窗 1×9→0×5→1×7，免 root 可读）；终局包 md5 `a8558b16` 上板，日志链 `IR PIR rising edge, -> startListening → state -> SPEAKING (wake-ack wozai) → WakeAck` 端到端通；修复包装板后 `IR polling started (pin=1, interval=200ms)` 复位正常。挂起：用户现场验收回收（仅 IDLE 口径/冷却窗/误触发率）+ 非易声设备降级真机验证（单测四分支已覆盖，pref 默认关零暴露面）。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
verify_claim: suites=110 tests=836 failures=0 errors=0 skipped=0；git diff --stat VoiceSessionManager.kt 空；板上日志 IR PIR rising edge→SPEAKING(wake-ack wozai)；包 md5 a8558b16e0a1388a14dc2695b268041b
files_touched: [app/libs/ysapi.jar, app/build.gradle.kts, app/src/main/java/com/xiaoduo/deskpet/ir/PirEdgeFilter.kt, app/src/main/java/com/xiaoduo/deskpet/ir/IrWakeController.kt, app/src/test/java/com/xiaoduo/deskpet/ir/PirEdgeFilterTest.kt, app/src/test/java/com/xiaoduo/deskpet/ir/IrWakeControllerTest.kt, app/src/test/java/com/xiaoduo/deskpet/ir/YsApiUsageContractTest.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/main/java/com/xiaoduo/deskpet/util/PrefContract.kt, app/src/main/java/com/xiaoduo/deskpet/util/Prefs.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt, app/src/test/java/com/xiaoduo/deskpet/util/PrefContractTest.kt, docs/research/2026-10-26-ir-wake/README.md, docs/research/2026-10-26-ir-wake/appendix-ysapi-sdk-findings.md, docs/DEV_LOG.md, docs/implementation-notes.html, PROGRESS.md, CONSTRUCTION.md]
```

## 2026-10-26（一）红外触发唤醒调研：ysapi SDK 逆向 + 小朵注入点勘明——SDK 无红外事件通道，可行路径=GPIO 轮询→startListening(true)

同事提供易声 ysapi SDK 包（ysapi.jar + 开发文档 V5.4 + demo 源码 app.zip + demo APK）。双子智能体并行只读调研（全程未执行素材代码、未触碰设备）：①SDK 逆向实锤 **ysapi（深圳易声，RK 板卡系统控制 SDK，binder→固件内置 com.ys.ys_receiver/.AIDLService）无红外事件通道**——55 类零 IR 命名/唯一回调 onConnect 仅连接通知/约 20 个广播 action 全是应用→系统控制指令/demo manifest 零 receiver；可行路径=红外感应器（PIR/对射）接保留 IO **电平轮询**（setGpioDirection+getGpioValue，官方 demo 同款点按式，无中断；串口自读需 root 为次选）。②小朵侧注入点勘明：外部唤醒单点收敛 `VSM.startListening(playWakeAck=true)`（四态语义现成：IDLE 唤醒/LISTENING S33 重唤醒/SPEAKING·PROCESSING S12 barge-in；守卫红线 kwsPaused/TurnFlags 封轮/wakeAckActive 三条），推荐落点 VoiceForegroundService 独立协程轮询（与 KWS onWake 同源同生命周期，进程活性最强）。报告 `docs/research/2026-10-26-ir-wake/`（README + SDK 逆向附录）。**阻塞选路三问待同事确认**：①红外硬件实物形态（PIR 电平/遥控器/串口模块）②目标板是否易声固件 ③保留 IO 路序表（V5.2+ 传 1..N）。另勘误：PROGRESS 2026-10-09 条目 `extra_launch_overlay_only` 已不存在，现实际为 OverlayLaunchActivity。

```evidence
verify_cmd: 双子智能体只读调研（无代码/配置变更，无测试面）；报告落盘 docs/research/2026-10-26-ir-wake/（README.md + appendix-ysapi-sdk-findings.md）
verify_claim: ysapi 无红外事件通道（附录第三节 4 条证据）；小朵注入点 file:line 勘明（VoiceForegroundService.kt:203-226 → VoiceSessionManager.kt:422-460 单点 startListening）
files_touched: [docs/research/2026-10-26-ir-wake/README.md, docs/research/2026-10-26-ir-wake/appendix-ysapi-sdk-findings.md, docs/DEV_LOG.md, PROGRESS.md]
```

## 2026-10-22（一）待办②借/还/盘点工具名核对关闭：用户核实其余工具族无改名，票A 别名表维持现状

用户核实结论：平台密集架 Agent 仅 `archive_query` 改名（票A 已修，PLATFORM_TOOL_ALIASES 归一），**借/还/盘点工具名没有变化**——别名表无需扩充。至此 desk-pet 待办清零（待办①AGC 增益收益/②工具名核对/③量纲统一全部关闭）；剩挂账 2 项纯观察（熔断 peak 污染——出现「utterance+hold 每 ~1.3s 循环」日志形态即落地修法；0.19 底噪定源——已排除 VC 链，剩环境噪声 vs ROM 底层增益二选一）。PROGRESS 同步。

```evidence
verify_cmd: 用户现场核实（口头结论，无代码/配置变更）
verify_claim: 借/还/盘点工具名无变化，票A 别名表维持现状；待办清零
files_touched: [docs/DEV_LOG.md, PROGRESS.md]
```

## 2026-10-21（十七）票D 合入推送+终局包装板：main=dc150e0 @ origin，板载现=mic-dc150e0 终局包+VC 档

**收口序列**：`fix/mic-source-4th-mode`（b4a169a 票D 主体 + e82e55e UI 终局回锁定+advisory 处置）`--no-ff` 合入 main（**merge `dc150e0`**）推 origin（`3141368..dc150e0`）；终局包 `dist/xiaoduo-mic-dc150e0-20261021.apk`（md5 `47edf63a`）`install -r` 上生产板 10.0.14.18，pid 22917 `capture start, source=7`（VC 档 pref=0）+ 零漂移告警 ✓；板上旧 mic-b4a169a 包（可点击 UI）被覆盖。

**票D 总账**：②C 裁决裁掉的第 4 档恢复（映射层），UI 终局 S57 只读锁定（当日拍板演进：解锁循环→回只读，均在（十六）留痕）；AGC 增益收益验证定性关闭（待办①销）；量纲统一随之失去 AGC 维度关闭（待办③销）；挂账②参数对账表回填销账（S69 系 6 参数+上攻语义+采麦源 4 档全量入表）。**剩余待办 1 项**（借/还/盘点工具名核对）+ **挂账 2 项**（熔断 peak 污染观察[出现「utterance+hold 每 ~1.3s 循环」日志形态即落地]/0.19 底噪环境 vs ROM 增益二选一定源[今日对照已排除 VC 链]）；校准拓扑残余风险操作纪律=改档后先重启再校准（评审 finding#2）。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: total=815 failures=0 errors=0 skipped=0；merge dc150e0 推送 origin/main（3141368..dc150e0）；终局包装板 pid 22917 capture start source=7 零漂移告警
files_touched: [docs/DEV_LOG.md, PROGRESS.md, dist/xiaoduo-mic-dc150e0-20261021.apk]
```

## 2026-10-21（十六）票D 采麦源第 4 档 MIC 恢复（映射层，UI 终局回只读锁定）：fork 锚组 4 移植回上游（815 绿）

**背景**：AGC 增益收益验证（待办①）需切 MIC 低电平档（fork 17.242 实证 nf~0.003、g 3.6-8.0 域，参考 `docs/verification/agc-tap-to-talk-acceptance-20261019.md`）；上板实锤上游包 pref `mic_source_mode=3` 落 `expectedSourceFor` else 归 source=7——**②C 裁决（2026-10-20）裁掉的第 4 档在上游不存在**。用户拍板（终局）：恢复 MIC 档映射层 + **UI 保持 S57 只读锁定**（会话中曾短暂拍板恢复 fork 可点击循环，后改拍板回只读——终局=映射层 4 档可用（adb run-as 改 pref + 重启），UI 无切换入口，2026-09-21 生产事故防线原样保留）。

**变更**（fork HEAD 7bef28c 移植+UI 终局回锁定，5 文件 +83/−27 → 含后续注释还原与 UI 回锁约 +90/−30）：①`AudioCapture.expectedSourceFor` 加 `3 -> 1`（MIC 板载麦链路）+ 构造点注释按 fork 还原 mode=3 fallback 诊断（评审 finding#1）；②`Prefs` 加 `MIC_SOURCE_MIC=3` 常量 + `coerceMicSourceMode` 纯函数（coerceIn(0,3)）+ setter 接线；③`PrefContract` 键注释补 3=MIC；④`SettingsScreen` 采麦源保持只读锁定 Text（**标签 when 补 MIC 分支三方对齐**；曾短暂改 TextButtonRow 循环后按终局拍板回退）；⑤`AudioCaptureSourceDriftTest` 锚组 4 移植（fork ②C 剔除的 4 测原样回归）。

**验证**：TDD 先红（编译失败=f2p）后绿；全量 **815 绿**（811+4，XML 复算零失败零跳过）；变异 M1（删 `3 -> 1` 映射行→2 测红）/M2（coerce 上界回 2→1 测红）双真红后还原复绿。**独立评审挂起**（用户令直接装板测试，实验后补跑 focused）。

**板侧实况**：生产板 pref 已预写 `mic_source_mode=3`（旧包下落 else 归 7 行为等同 VC 档，无风险态）；新包装板后重启即 source=1 生效。

**板测实验（22:45 装 `dist/xiaoduo-mic-b4a169a-20261021.apk` md5 `2de387c3`，22:51 金线抓流，用户 5 轮真声）**：
- **票D 功能全绿**：`capture start, source=1` 生效零漂移告警；4 轮喊话+1 轮 multi-turn 全链通（唤醒→LISTENING→VAD utterance parts=1+2 合并派发→ASR 全对：「给我讲个笑话。」「今天天气怎么样？」「查询档案。」×2→LLM→SPEAKING→barge-in 正常）；S69 判停链 MIC 档零失败（合并 5/5、零死锁、零 FATAL；22:58 listen timeout 为空轮超时正常形态）。
- **AGC 增益收益定性关闭（待办①）**：g 恒 1.00 全程（nf 0.18-0.25 > 目标）——14.18 为响环境，**任何采麦源档位增益通道数学上不触发**；17.242 fork 板 g=3.6-8.0 是安静环境（nf 0.003）效应。AGC 在生产配置恒直通=零风险面，P1#3 削波担忧在此板无暴露面；待办③量纲统一随之失去 AGC 维度。
- **0.19 底噪来源对照（挂账③推进）**：MIC 档（无通话预处理链）底噪 0.183 ≈ VC 档 0.19 → 实锤非 VC 链产物，来源=环境噪声本体/ROM 底层增益二选一（22:57 说话期 nf 爬 0.25 示环境声贡献大）。
- **削波观察（无危害）**：MIC 档说话 maxLevel=1.0000（4/4 轮首段钳位）——ROM 增益下 MIC 链电平本高，非 AGC 叠加（g=1）；ASR 全对证容忍。
- **生产配置恢复（用户拍板回 VC 档）**：force-stop → pref 3→0 → 重启，新 pid 23586 采麦正常（nf=0.184 VC 域、漂移告警零响、pref cat 验证）。**板载现=mic-b4a169a 包 + VC 档**（含票D 功能，MIC 档留设置页可点击随时可切；回 VC 理由=保留硬件 AEC 防播报期回声自触发，MIC 档回声抑制仅剩 NLMS）。本地金线存档 `/tmp/mic-agc-experiment-20261021.log`。

**独立评审（focused，本侧 correctness + 1 本地对抗评审员；跨模型路由全 CLI 缺席实锤 codex/grok/cursor/composer/opencode，本地兜底）**：verdict=**行为级还原忠实**（与 fork 7bef28c 逐语义块比对：映射 3→1/coerce 0..3/setter 全同）；**漂移 WARN 网四条采麦启动路径全覆盖**（正常启动/fallback-DEFAULT/零块自愈重建/校准临时采麦——同走 readLoop 漂移检查点）。**2 P3 advisory**：①构造点注释停留 3 档纪元——**已修复**（按 fork 原文还原 buildRecord 注释）；②**「校准拓扑==采麦链拓扑」不变量残余风险**（切档不重启即点校准，AmbientCalibrator.collectAmbient 现场读 pref 新建临时采麦走新源，gate 可测在异拓扑流上，重启后漂移网必静默）——**操作纪律：改档后先重启 App 再校准**；UI 回只读后该面收窄（无 UI 入口，变更仅走 adb 开发操作）；最小加固候选=AmbientCalibrator 临时分支比对期望源 vs usedSource 打 WARN（本轮不做留观察）。评审报告 `/tmp/compound-engineering-197108/ce-code-review/20261021-230124-c132b242/report.md`。

**UI 终局回只读（评审期间用户追加拍板「将采麦源设置为只读」）**：SettingsScreen TextButtonRow 循环回退为 S57 只读锁定 Text（标签 when 保留 MIC 分支），AudioCapture/测试类注释同步终局口径；复跑全量 **815 绿**。板载 mic-b4a169a 包（可点击 UI）由终局包覆盖（见（十七））。本地金线存档 `/tmp/mic-agc-experiment-20261021.log`。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: total=815 failures=0 errors=0 skipped=0；板测 5 轮金线零异常（MIC 档 source=1 生效、ASR 4/4 全对、合并 5/5、FATAL 0、g 恒 1.00 实锤）；生产配置已恢复 VC 档（pref=0 验证+漂移告警零响）
files_touched: [docs/DEV_LOG.md, docs/PROGRESS.md, dist/xiaoduo-mic-b4a169a-20261021.apk]
```

## 2026-10-21（十五）fork 移植双验收收口：点按 5 步 5/5 + AGC A/B 生产配置一致性确认

**点按交互 5 步验收（S69c 包，聊天页 12+ 轮点按金线）**：5/5 全过——①点按开始（LISTENING 迁移形态「VAD start, timeout 30s」与唤醒路径可区分）②说完点结束（「用户明确说完=续听窗即刻耗尽」守卫生效，hold→dispatch 0.06-0.15s）③多轮续听（multi-turn 循环多轮稳定）④播报中点按（22:09:55 barge-in cancel 打断 → 立即接续聆听 → 再点退出，全链正确）⑤说完秒点（stopListening 直通派发两轮，<1s 进识别，ASR「你好。」完整零丢字——StopReleaseGuard 吞语音守卫实锤生效）。**T2 移植功能验收闭环**。

**AGC A/B（生产配置一致性确认版）**：实验设计修正——生产板 mic_source_mode=0（VC 硬件增益档）+ 环境响（nf=0.19 > 目标 0.08）→ **AGC 恒 g=1.00 直通（agc nf= 行连续四行实锤），两臂数学上必然零差异**；本次验证目标改为「关闭 AGC 是否安全一致」（回应评审 P1#3 默认开风险关切）。执行：OFF 包 `dist/xiaoduo-agcOFF-c5d80f4-20261021.apk`（md5 6151f419，基 c5d80f4 翻闸构建后源还原 diff 零残留）装板 → 用户 2 轮喊话（正常「给我讲个笑话」合并完整/轻声「今天天气怎么样」合并完整）→ 与 ON 臂（S69c 40+ 轮）逐轮对比行为完全一致。**结论：生产配置下 AGC 零风险面（P1#3 关闭）**；增益收益的真验证需切 MIC 低电平档（nf~0.003，g 3.6-8.0 域），动现场配置挂待办。生产包已恢复 S69c（22:16:49，pid 12114）。PROGRESS 待办 5→3 项。

```evidence
verify_cmd: adb logcat -s VoiceSession:V 金线逐轮核对（点按 12+ 轮 + AGC OFF 臂 2 轮 vs ON 臂 40+ 轮）
verify_claim: 点按 5/5 全过零丢字；AGC 两臂行为一致零异常；生产包已恢复（lastUpdateTime 22:16:49）
files_touched: [PROGRESS.md, docs/DEV_LOG.md, dist/xiaoduo-agcOFF-c5d80f4-20261021.apk]
```

## 2026-10-21（十四）S69c 三轮板测收口：票C 闭环——S69 系三包装板 33 轮真声，判停链重构完成

**三轮板测（S69c 包 `dist/xiaoduo-s69c-a64bcb8-20261021.apk`，21:36:01 上生产板 pid 23034）**：用户 13 轮真声（21:38-21:43）——**「的档案」弱尾/长句场景全数救回，零失败**。金线亮点：片段 2 峰值 0.3239（恰在 0.32-0.35 修复区间，旧钳 0.35 下必丢的边界样本）判起合并；**parts=3 三段合并**首次实战（跨双气口 utterance×3 → 75200 采样合并派发 → ASR「查询有关科技的档案。」完整）；9.6s 双 clause 超长句单段零切（「帮我找一个关于科技的档案，我要两个档案就行。」）；8.8s「帮我查询2022年的前30条档案。」完整——S60 时代「档案尾丢」历史病灶场景零复现。barge-in 打断链、multi-turn 续听、工具轮回唤醒全程正常。

**S69 系战役总账**：3 个包（S69 d4752da / S69b b70d4e2 / S69c a64bcb8）× 板测 3+11+13=**27 轮用户真声**（外加 S69 包前 9 轮取证），票C「判停过早/死锁」从双向失败到闭环——floor 跟踪三失败模式（死锁/提前判停/全聋）全部根除，S51 合并语义首次板上实战（合并 12+ 次 part2/3 零失败）。S58 上攻通道说话期退役（S52 诉求回归），判停兜底改由熔断（不依赖 floor）+有界续听+空 ASR 裁判三层接管。

**遗留挂账**：①熔断基准跨轮 peak EMA 污染（评审 finding #1，板上未观测到 churn 形态，留观察）；②新参数回填语音参数对账表（待补）；③0.19-0.27 域极轻尾词物理极限（用户端对策=尾词稍加重音）；④板测派发延迟听感（窗=10 固有 +0.9s，用户现场未提出异议）。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: total=811 failures=0 errors=0 skipped=0；S69c 板测 13 轮金线零异常（弱尾救回 5/5、parts=3 合并 1 次、FATAL 0）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt]
```

## 2026-10-21（十三）S69c 弱尾盲区修正：判起线钳 0.35→0.32（811 绿，三轮板测复验待跑）

**用户二轮板测后现场反馈**：「有概率提早判停，没有检测到『的档案』」。diag 取证（212031.log）：缺尾轮次尾段电平 0.2519（21:22:31）——落在判停线 0.266 之下被切、又够不到判起线钳 0.35——**0.266-0.35 盲区**（S69 评审报告预警项的概率性表现）；成功合并轮片段 2 峰值全在 0.38+（0.3818/0.3848/0.3889/0.4581/0.5284），失败样本 0.25-0.29——概率性=「的档案」音量分布。

**修正**：START_LINE_MAX 0.35→**0.32**——0.32 保持 end(0.30)<start 不变量、底噪上沿 0.27 安全边 0.05；救回 0.27-0.35 域弱尾；0.19-0.27 域贴底噪属物理极限（VadDetector 类注释既录，用户端对策=尾词稍加重音）。TDD：S69c 测试（floor 污染域 0.34 弱尾判起）先红后绿，变异验证（钳回 0.35 → 红）后还原；全量 **811 绿**。三轮板测复验场景=含「的档案」尾的查询句多轮。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: total=811 failures=0 errors=0 skipped=0；S69c 变异（钳回 0.35）单测红后还原
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt]
```

## 2026-10-21（十二）S69+S69b 板测收口：11 轮真声全健康，合并语义 6/6，票C 闭环

**二轮板测（S69b 包 `dist/xiaoduo-s69b-b70d4e2-20261021.apk` md5 `679252dc`，21:20:45 `install -r` 上生产板 10.0.14.18，pid 10527）**：用户 11 轮真声（21:22-21:28）——**零死锁、零全聋、零超时、零 FATAL**。金线汇总：
- **合并语义 6/6 实战全成**（S51 窗=10 首次板上实战）：第 1/3/5/6/8/9 轮跨 1.4-1.5s 气口 parts=2 合并，片段 2 电平 0.38-0.53 全部正常判起（S69b 判起线钳 0.35 生效实证——S69 包同场景判起线被污染到 0.4+ 判不起）。
- **完整成句 8/11**：「帮我查询当前有哪些。」「帮我查询一下C款密下的档案。」「帮我查询一下当前有哪些档案。」「帮我查询所有档案。」「有没有关于教学的？」「给我讲一个。」「我想查一下。」「当前有哪些关于印度？」（末两句个别字 ASR 层误识，句子结构完整）。收句机制 11/11 正常（含 1 轮轻声段 ASR 误识「今天的天今」——板噪 0.19 下 0.03-0.05 域轻声物理判不起，VadDetector 类注释「物理极限」既录；1 轮未说话空轮）。
- S69 包首轮板测（21:07-21:08）暴露的提前判停/全聋两病在 S69b 包下零复现。

**S69 包一轮板测（21:07-21:08，3 轮）**：第 1 轮完整句切「帮我查」（判停线被推到说话弱音节域）；第 3 轮说话判不起 30s 超时（floor 推到 0.5 封顶判起线 1.0 全聋）——直接催生 S69b 三连修（DEV_LOG（十一））。

**评审**：ce-code-review focused 路径（本侧 correctness + 1 本地对抗评审员；codex 跨模型路由 CLI 缺席未运行）。verdict=**Ready with fixes**——2 findings（P3 跨轮 peak EMA 污染熔断基准[advisory，挂板测观察 churn 日志形态]；P3 新参数未回填语音参数对账表[待补]）+ 并发审计干净 + ghost 周期有界终结确认。报告 `C:/Users/Administrator/AppData/Local/Temp/compound-engineering-197108/ce-code-review/20261021-203727-s69/report.md`。

**收口序列**：`fix/s69-stall-fuse-continuation`（d4752da + b70d4e2）合入 main 推送 origin（见下条 evidence）；PROGRESS/AGENTS 状态同步；ce-compound 沉淀；板载现=**S69b 包**（b70d4e2 源）。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: total=811 failures=0 errors=0 skipped=0；板测 11 轮金线零异常（VAD utterance 11/11、continuation dispatch 11/11、合并 6/6、FATAL 0）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt]
```

## 2026-10-21（十一）S69b floor 污染三连修：说话期禁上攻 + 判起/判停线绝对钳制（811 绿，二轮板测待跑）

**板测取证（S69 包，生产板 21:07-21:08，用户三轮喊话）**：S69 三件套在真实说话期暴露 **floor 污染的另外两个失败模式**——①第 1 轮完整句被切「帮我查」：判停线（floor×1.4）被推到 0.3+ 贴住说话弱音节，句中弱音节计静音 600ms 即切，续说也因判起线（floor×2）抬到 0.4+ 判不起而无片段合并；②第 3 轮 30s 超时零 utterance：diag 实锤说话期电平 0.33-0.42+削波 1.0 全部判不起 → 判起线 ≥1.0 → **floor 被 S58 上攻通道推到 NOISE_FLOOR_MAX=0.5 封顶**（弱音节半峰判据 level<peak×0.5 在真实说话期高发误触发，floor 0.35 速率爬向说话电平）。至此 floor 跟踪三失败模式全部实锤：钉底噪稳态=判停死锁（（七）/S69 已修）、推中间域=提前判停、推封顶=全聋。与昨晚 9 轮真声不炸的差异：响度分布不同，上攻误触发是概率性的。

**三连修**：①**说话期禁用上攻**（`!isSpeaking` gate）——floor 只在未判起期学习（S19-1 原场景），上攻原职能由未判起期慢跟随+S56 空 ASR 裁判+**S69 熔断**（不依赖 floor）接管；②**判起线钳 START_LINE_MAX=0.35**（max(provider, min(floor×2, 0.35))）——floor 无论被谁污染正常说话必判起；③**判停线钳 END_LINE_MAX=0.30**（贴板噪上沿 0.27）——不再切说话弱尾音。S58「判停可用性优先」的取舍被新证据推翻：S58 复活上攻的理由（判停死锁）已由 S69 熔断以更安全方式覆盖。

**测试**：+3 S69b 锚（说话期 floor 恒净/S56 raise 后判停线 ≤0.30/floor 污染后 0.4 说话仍判起）——实现前三条全红=天然变异证据；S58 两个既有锚按新语义改写（`pump noise now held to MAX_UTTERANCE`：早滤废止改锁 8s 兜底；`S52 sustained stable tone NOT gated`：**S52 诉求回归**——长元音不再被 plateau 通道中途切）。全量 **811 绿**（808+3）。

**遗留观察**：泵态 ghost 判起改由 8s 强制收句（比 S58 早滤慢 6s）——S19-2 空轮预算 2 次内自愈，板测观察「我在」播完后的恢复速度。S69 评审 finding #1（跨轮 peak 污染熔断基准）仍挂板测观察。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL in 1m 19s；total=811 failures=0 errors=0 skipped=0（基线 808+新增 3，S58 两锚语义改写净零）；S69b 三测实现前全红=变异证据
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt]
```

## 2026-10-21（十）S69 票C 三件套：底噪熔断判停 + 续听窗恢复 10 + 有界总上限（808 绿，板测定值待跑）

**取证先行（生产板 20261021_190723/192937 日志，9 轮真声）**：票C 双向失败的统一根因实锤=**floor（噪声底）逐轮漂移在临界带，判停行为逐轮不可预测**——19:12:25「放」轮（floor>0.136）判停线>底噪 → 600ms 气口即切（病2 过早判停）；19:33:08 轮（floor≤0.136，diag 连续 4 行 0.19 低谷）4 秒气口不切（判停死锁常态，说完靠上攻收敛才收句）；19:14:35 轮 3 秒气口同型。floor 稳态钉死底噪（0.19，板自噪恒定）后慢跟随推力归零、判停线骑底噪带（0.19×1.4=0.266），底噪块级电平绕线摆动令 belowTicks（判停）与 sustainedAbove（上攻）双计数器互相清零——**慢跟随/判停/上攻三通道同时失效**。（七）DEV_LOG 归因「S52 停用 floor 上行」系注释假锚勘误：S58 已复活上攻（NOISE_ATTACK_ENABLED=true），真机制如上。15:40 死锁时段日志已被轮转覆盖（板留最近 11 文件≈2h）。

**三件套（用户拍板按方案施工）**：
1. **底噪熔断判停**（`VadDetector`）：说话态下证据窗（复用 plateauWindow 最近 10 块）全部块 < 语音峰值 EMA×0.6 且 speechTicks≥10 → 强制收句。「窗内无语音」强证据，对摆动鲁棒（不再依赖 floor 跟踪）；低峰比对持续音自锁（peak 含当前块，长元音 level≈peak 恒不满足）——S52 误伤免疫；说完到收句 ≈证据窗填满 ~1s（替代旧 8s 强制结束/永不派发）。TDD 实录：测试构造三轮迭代（恒定块→触发说话期上攻假死锁；T 密集→上攻自愈判停非死锁；**T 稀疏 [F,F,F,T] 循环才是死锁域**，Python 全保真模拟定位），v5 构造先红后绿。
2. **续听窗恢复 1→10**（`CONTINUATION_TICKS`）：判停切尾片段窗内合并（救「放」轮）；S55 暂关决策（09-23「勿再当悬案翻出」）因（九）新证据（句中气口>600ms 切尾，推翻「S60 尾垫已覆盖」假设）由 handoff 待办①重新拍板废止。
3. **有界总上限 30**（`CONTINUATION_MAX_TICKS`，3s）：hold 期冻结块也计数，到顶强制派发——S55「冻结永不解除 → hold 无限累积 parts=8/66s」死锁硬顶。**变异锚抓到实现 bug**：有界 fire 后被 `dispatchContinuation` 的「倒计时未耗尽」守卫拒发并清空挂起，修=有界到顶时 ticks 归零（强制耗尽语义）过守卫。S55 注释预留正解候选「冻结也有总上限」落地。

**测试**：+3 熔断锚（死锁域收句/波动说话不误伤/短气口不切）+ VSM 三用例改写（合并语义锁还原/有界纯路径构造/轻声冻结合并还原）；**变异双锚真红**（删熔断→熔断用例红；删有界→有界用例红）；全量 **808 绿**（805+3，BUILD SUCCESSFUL 1m19s，JDK PATH+--rerun-tasks 前缀齐全）。有界用例构造曾两轮失真（0.25 恒定块被上攻自愈、非有界路径）——S69 后「冻结永不解除」近乎不可构造（四重机制互相封死），有界上限是末位保险。

**参数与板测**：STALL_MIN_SPEECH_TICKS=10 / STALL_PEAK_RATIO=0.6 / CONTINUATION_TICKS=10 / CONTINUATION_MAX_TICKS=30 全为 JVM 机制锁初值，**板上调准待跑**（金线=`VAD utterance`/`S51 continuation hold|dispatch`/`ASR text`，验收场景=轻声+气口+正常三型）。

```evidence
verify_cmd: cd /e/desk-pet-android && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL in 1m 19s；JUnit 汇总 total=808 failures=0 errors=0 skipped=0（基线 805+新增 3，零回归）；变异双锚真红实证（删熔断/删有界各复跑对应测试类 1 failed）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt]
```

## 2026-10-21（九）票A 合入推送+出包上板：真声验收 6/6 弹卡，弹窗链正式恢复

**合并与推送**：`fix/platform-tool-alias`（ad5352e）--no-ff 合入 main → **merge `845db5e`**，推送 `43d943e..845db5e`（origin 零偏离）。**出包上板**：`dist/xiaoduo-main-845db5e-20261021.apk`（md5 `0113768a`，96MB）19:07:58 `install -r` 上生产板 10.0.14.18（Success，保数据）。

**弹窗链本体先行验证（绕开语音）**：`adb forward tcp:18848→8849` + curl 直发 `mcpUiResult`（样例行 `data_id/archive_id=0` 走只读「去检索页操作」分支）——弹卡完美渲染（题名/档号/年度/密级/位置/「已上架」徽标/高度随内容收放）。**坑沉淀**：Windows curl `--data '<内联中文JSON>'` 经 GBK 码页转码致板上全成 ◆（JSON 结构仍可解析，极具迷惑性）——中文 body 必须 UTF-8 文件体 `--data-binary @file`（已记 `~/.claude/docs/errors.md` GBK 主题）。

**真声验收（用户现场共 8 次唤醒，6 轮派发）**：**6/6 全弹，零 FATAL**。覆盖五形态：too_many 小结果（45 条）/too_many 大统计（6827 条 8 类表）/no_result（143px 提示卡）/success 大列表（90 行，内容高 8391px→窗口正确压顶 1491px 内滚，S46 高度逻辑按设计）/filtered（`in_shelf` 参数）。每轮双金线齐全：`shelf mcp sync ok tool=archive_query` + `shelf mcp result ok tool=archive_query`，密集架侧收到的均为归一名 `c_archive_query`（带 runId）。**平台 rows 不带 `data_id/archive_id`** → 卡片全部「去检索页操作」只读形态（借阅直操键不出，防双控面无恙）。

**判停过早实锤（票C 新证据）**：第 1 轮话语被切成 1.8s、ASR 只出单字「放」——600ms 判停窗 + S51 续听窗 S55 暂关（`window=1 blocks`）副作用：句中气口 >600ms 即切且无续听兜底；其余轮次语速连贯则完整成句。与（七）判停死锁构成同组参数**双向失败**（说话轻→永不判停；响+气口→过早判停）。

**收口杂项**：密集架侧同日部署其合并包（票B NPE 防护+同事电子查阅/扫码枪/皮肤，mijijia 仓 b0fb20c），拉起冒烟零崩溃；`install -r` 装包前后配置对比实证零写入（唯一差异 `area_number` 1→10 系同事 10-09 后在板上改的本区列数，用户确认正常）。

## 2026-10-21（八）板测续取证：平台工具改名致 mcpUiResult 静默丢弃（票A 修复）+ 判停过早与跨仓 NPE 取证

**18:03-18:04 板测时间轴（10.0.14.18，生产板）**：18:03:47 唤醒「你好小朵」→ 18:03:50 utterance 派发（32000 采样恰 2.0s，`S51 continuation hold: parts=1 window=1 blocks` 实锤续听窗处于 S55 暂关态）→ ASR「帮我查询当前有哪些。」→ 18:03:59 平台执行工具 → 18:04:00 TOOL_CALL_RESULT 正常返回（too_many，6827 条统计）→ **`MCP tool result captured name=archive_query` 后戛然而止**：桥白名单只认 `c_archive_query`，`resultBodyFor` 对未知工具按守卫口径返回 null，mcpUiResult 静默丢弃，密集架零弹窗（板侧全天仅收到 getShelfStatus/getAir 轮询）。**SSE 原始证据行**（归档，P3）：

```
10-21 18:03:59.986 I/AguiClient(29039): SSE raw: data:{"type":"TOOL_CALL_START","rawEvent":{...,"tool_calls":[{"name":"archive_query","args":{"query":"帮我查询当前有哪些","query_scope":"all","keyword":""},"id":"KCamJynjSuvFAItcx6cFVYc6qJEWAhtx","type":"tool_call"}],...},"name":"ChatOpenAI","tags":["seq:step:1"],...}
10-21 18:04:00.635 I/AguiClient(29039): SSE raw: data:{"type":"TOOL_CALL_RESULT","messageId":"KCamJynjSuvFAItcx6cFVYc6qJEWAhtx","content":"{\n  \"success\": true,\n  \"status\": \"too_many\",\n  \"message\": \"根据您查询“帮我查询当前有哪些”，共检索到 6827 条结果...（统计表略）",...}
10-21 18:04:00.649 I/VoiceSession(29039): MCP tool result captured name=archive_query
```

**票A 修复（本条目对应代码）**：`ShelfMcpBridge` 增 `PLATFORM_TOOL_ALIASES = {archive_query→c_archive_query}` + 顶层 internal 纯函数 `canonicalToolName`，`callFor/resultBodyFor` 双点（白名单判定+请求体 tool 字段）归一——密集架侧 `mcpSyncedTools` 与 `McpResultDialog.titleFor/statusFor` 均锚旧名，归一是语义必需（直发裸名会落「小朵助手」错题+绿字「操作成功」）。SYNCED_TOOLS 契约锚不动、未知工具仍 null、防双控口径不变；仅 archive_query 有实证，工具族其余改名待与平台核对。+5 测（805=800+5 绿）。独立评审 PASS（零 P1/P2；跨仓契约核实/调用方收口无旁路/独立重跑 805 绿）。

**同板取证另两案（不在本仓修）**：①**判停过早**——600ms 判停窗+S51 续听窗 S55 暂关（window=1）副作用，句中气口 >600ms 即切尾且无续听兜底；与（七）判停死锁系同组参数双向失败，待办①扩版为「恢复续听窗+冻结条件抗噪（空 ASR 裁判门控+上攻限速）」，需板测调参。②**密集架 App NPE 崩溃**（mijijia 仓修）：18:04:35 新 MainActivity onStart 经 forwardPass 派发滞留 `globalLockStatus` OneOff 事件，`MainActivity.kt:386 config!!` 在异步 config 加载前被击穿 → 进程死亡重启（8608→425）；GuardService 看门狗叠 4 实例放大竞态。

```evidence
verify_cmd: cd /e/desk-pet-wt-fixtoolname && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL in 1m 22s；JUnit 汇总 tests=805 failures=0 errors=0 skipped=0（基线 800+新增 5，零回归）；独立评审员复跑单类 20 绿+全量 805 绿同结果
files_touched: [app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt, app/src/test/java/com/xiaoduo/deskpet/device/ShelfMcpBridgeTest.kt]
```

## 2026-10-21（七）上板取证挂起：判停死锁实锤（新票）+ A/B 双包就绪

**板态与双包**：目标板=10.0.14.18（rk3568/Android 11，双 App 生产板，deskpet 旧包 10-11）。A/B 双包构建归档 `dist/`：**AGC-ON `81f9ef7d`**（xiaoduo-agc-ON-abtest-20261021.apk，AGC=true 主线码）/ **AGC-OFF `8d0cf3ef`**（xiaoduo-agc-OFF-abtest-20261021.apk，翻闸构建后源文件 md5 对拍零残留）。A 包 `install -r` 上板成功（签名 22d84350 同 debug 键免卸装，prefs 备份 /tmp/abtest-20261021/），lastUpdateTime 15:35:58，`agc nf=` 状态行与 `capture start, source=7` 金线验证通过。**生产板现跑 A 包=main 同源 800 绿代码（AGC 开）；旧包（10-11）无留存，回滚需从 refactor/p0 重建。**

**判停死锁实锤（新 P1 票，阻塞 A/B）**：两轮人声时间轴（15:40:32/15:40:58）——唤醒✓ wozai✓ 判起✓（峰值 0.40-0.50 跨判起线）、声波纹指示出、**判停✗**：说完电平回落到环境底 **0.19** 持平，判停线 max(0.03, floor×1.4) 被锁在其下，600ms 判停窗永不凑齐 → utterance 永不派发 → 两次均由用户双击宠物取消（15:40:52.1/15:41:12.4 `overlay pet double tap -> cancelCurrentTurn`）。根因链=`VadDetector.kt:38-44` 自记已知病（S52 停用 floor 上行通道，环境噪声阶跃到判停线之上时 floor 永远学不到）+ **空轮收敛死锁**（S19-2 兜底需要「判停→空 ASR 轮」才触发，判停不发生则 floor 永不收敛）。环境因子：板载底噪 **0.19-0.27 恒定不随房间安静度变化**（板自噪声：风扇/采麦链×source7 硬件增益——用户报告「环境安静了」后 nf 序列仍平坦，16:53-17:00 实证）。**与 AGC 无关**（全程 g=1.00，环境已响过目标 0.08）。另实锤聆听窗语义：`state -> IDLE (listen timeout)` 10 秒无活动自动关窗。

**A/B 挂起原因**：判停死锁吞掉全部 utterance，两臂都会量出「0 派发、全靠超时/手动关窗」——量到的是死锁不是增益效应。**解锁路径**：判停兜底超时票（方案 B：已判起后累计超 8s 强制判停派发 ASR → 若空文本则 S19-2 空轮收敛被激活 → floor 上爬 → 判停线越过底噪 → 死锁自愈），代码票不依赖设备，修复后 A/B 立即可跑。

**板自播路线调查（四路全灭，防重趟）**：tinyplay（板 ALSA 设备限 ≤8kHz，48k wav 拒）✗；VIEW audio/wav（系统无注册 handler）✗；RockVideoPlayer/.VideoPlayActivity（起播 0.5-3.6s 即死，logcat 静默；但 0.5s 残响被 KWS 认成「小度小度」#7——**板喇叭→板麦声路本身是通的**）✗；PC 喇叭（板拾音无反应）✗。wav 已留板上 `/sdcard/Music/calib-x3.wav`（48kHz 立体声 14.1s，2.7MB）。

## 2026-10-21（六）port 4cap 战役收口：合入 main 并推送 origin

**合并**（用户拍板「合并main，推origin」）：`port/baseline-4cap-upstream-47b240a`（b978900）--no-ff 合入 main → **merge `678ea8f`**（32 文件 +2341/−216，11 新文件；merge 后 main 与 port 分支 `git diff` 为空=内容一致）。推送前拓扑核对：origin/main（b233920）=本地 main（47b240a）的**父代**、port 分支包含二者——纯线性链（首轮「origin/main 领先」系方向误读），推送为 `b233920..678ea8f` **fast-forward**，`main...origin/main` 零偏离，工作树停 main。

**挂账移交**（真机窗口 4 项，见（五））：#3(A) AGC 开/关 A/B 取证 → #10 量纲统一（随裁决）、#8 点按交互 5 步验收、#2 板验 APM 账目。port 分支与 tag `port-4cap-merged-20261020` 留本地（历史经 merge commit 全可达，需要归档随时补推）。

**文档同步**：PROGRESS 顶态改「已合入并推送」、过期警戒线（评审全过前不推不合）移除；CLAUDE.md/AGENTS.md「当前状态」节更新至 2026-10-21；姊妹仓索引补战役闭环注记。

## 2026-10-21（五）ce-code-review 收口与修复轮：11/11 confirmed 3 P1，用户四项拍板后修复面落地 800 绿

**评审结论**（run 20261020-183118-6a1b4b2a，8 评审员 33 原始发现 → 合并 13 主项 → 验证批 11/11 confirmed）：verdict=**Ready with fixes**。P1×3——#2 形态 A 重试环丢 fork 的 release-before-retry（每次启动失败泄漏 native AudioRecord，memory-safety）；#1 采麦链装配面零覆盖（注释宣称「删此→测试组红」的三处锚经 6 轮变异实测全部恒绿）；#3 软件 AGC 装配级默认开启（自述「×8 不抬误触」不变量被本仓底噪 0.0068 突破，0.0068×8=0.0544>判起线 0.05）。完整报告 `C:/Users/Administrator/AppData/Local/Temp/compound-engineering-197108/ce-code-review/20261020-183118-6a1b4b2a/report.md`。

**用户四项拍板**（2026-10-21）：①#2+#1 按 fix order 立即修+补定向测试；②#3=(A) AGC 保持默认开、先上生产板做开/关 A/B 对照取证；③#5=(A) 补回 readLoop 漂移 WARN 接线（fork 原语义）；④#8 真机交互验收挂待办等设备窗口。

**修复落地**（主代理亲执行，fork 原文照抄防漂移）：
- **#2**：`startRecordWithRetry` 失败分支照 fork `:347-349` 补回 `runCatching { r.stop() } / r.release() / record = null` 三行（WARN 之后、attempt 裁决之前，重试与终态两出口共用）；KDoc 同步改为 fork 的「未进录音态→stop/release→delay 重建」时序描述（原「finally 释放」为假声明）。
- **#1**：三处裁决收敛为顶层纯 seam——`startRecordRetryOutcome(attempt, maxAttempts)`（RETRY/GIVE_UP）、`onCaptureStartOutcome(guard, startedOk)`（成复位/败攒证）、`onZeroBlockOutcome(guard, block)`；readLoop 三调用点与重试裁决点全部改走 seam。新建 `AudioCaptureWiringSeamTest`（8 测：重试序列 1→R/2→R/3→G + 边界含等、三常数钉死、连败三次恰一次 fire、败败成复位后重计满、孤成不 fire、29 块不触发/30 块触发、非零打断重计）。**残留如实记**：readLoop 调用点本身仍 Android 域 JVM 不可达（定向测试钉的是裁决序列语义；调用点删除由守卫注释+审查+真机兜底）——record 工厂 seam（构造/释放计数）留待后续票。
- **#5(A)**：readLoop 采麦启动日志后补回 fork 漂移 WARN 块（`isSourceDrift(sourceMode, usedSource)` → 「mic_source 漂移：pref mode=…期望 source=…实际 active source=…」，每次启动至多一行不去重）；注释指称写「本仓 e5b6e11/593595f gotcha」（fork 原文「上游」系 fork 视角残留，两 hash 经 `git cat-file` 证实为本仓 S52/S55/S56 提交）。
- **#9**：`buildRecord` 收回单表——`val preferred = expectedSourceFor(sourceMode)`（fork 同形，删二次 when），注释注明「返回值与 AudioSource 常数逐一相等，不设二次映射——二次映射会在补档位时静默分叉」；`:389`「映射单点维护」KDoc 回归真命题。
- **#4**：`isSourceDrift` KDoc 与 SourceDriftTest KDoc 的「有意偏离上游 1723d7b：不把设置页麦源改只读」假陈述改写为本仓实况（1723d7b=本仓加只读锁的提交，锁仍生效，变更走 adb 改 pref+重启）。
- **#7**：InputBar TO_VOICE contentDescription「切换到按住说话」→「切换到点击说话」；同源旧语义清理（AppIcons:55 注释、SettingsScreen:170 区块注释、VSM wakeAckActive/startListening KDoc 三处按住措辞→点按）。
- **#6**：spec 框图行改「点击说话」+ InputArea 段落改 Tap-to-Talk 语义（标 2026-10-18 拍板来源）；Product Contract KD15 行加改点按沿革；implementation-notes.html 新增 Tap-to-Talk 设计决策条目（含②C 连带裁决与验收待办）。
- **#11**：T2 三票 evidence 块勘正（补记不抹史：保留原命令原文，前置勘正标记说明缺 PATH export/虚标前缀，指向本条目复跑覆盖）。
- **#12**：CLAUDE.md 包结构表——总数 105→111（find 实测校准）、voice/ 32→33 文件并补 `VoiceTapRouter.kt` 关键类条目。
- **#13**：四处「本仓口径以 docs/DEV_LOG.md:28 为准」改指 `PROGRESS.md:12`（sister-repo-index + 三搬运档注记头，各 1 处，搬运档 fork 正文零触碰）。

**验证闭环**（全套件带完整 JDK PATH 前缀 + `--rerun-tasks`）：**BUILD SUCCESSFUL，XML 复算 tests=800 failures=0 errors=0 skipped=0（107 套件）= 792 + 8 条接线 seam 测**；p2p 十一关键词逐一持平基线；probe4 三符号 app/ grep=0；MIC_SOURCE_MIC grep=0、`coerceIn(0, 2)`@Prefs.kt:170 在位。**变异锚 3 条实红**（M1 重试上限比较 `>=`→`>`：重试序列组红；M2 删成功复位分支：复位组红；M3 归零转发恒 false：全零裁决组红），还原 md5 对拍 `b494095b` 一致零残留。

**真机窗口待办**（用户拍板挂起，设备窗口可用时执行）：
1. **#3(A) AGC A/B 取证**：生产板 mode 0/source 7，`SOFTWARE_AGC_ENABLED` true/false 两包各 10 轮同场景，比对 maxLevel 分布与 VAD 判停成功率（验收档已记 source=7 上 12/12 `manual release, no utterance`+maxLevel 爆表形态），结论落 `docs/verification/`，据证据裁决是否保留默认开；
2. **#10 量纲统一**（随 #3 裁决联动，若保留 AGC）：校准统计改增益前口径或复用会话 AGC 同域；
3. **#8 Tap-to-Talk 真机交互验收**（5 步：IDLE 点按进 LISTENING / 聆听中再点走有货送货无 `manual release` 金线 / SPEAKING 点按 barge-in / 两态文案底色像素断言+A/B 盲测 / 结论落 verification）；
4. **#2 板验**：制造一次 startRecording 被拒（-38），确认 APM `releaseInput() releasing unknown input` 无增量、第 2 次重试能进录音态。

**后手**：修复 diff 独立 reviewer 复核（maker≠checker）→ ce-compound 沉淀 → worktree 清理（wt-t2/t3/testing）→ 评审全过再议推 origin/合 main。

```evidence
verify_cmd: cd /e/desk-pet-android && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL in 1m 25s；XML 独立复算 tests=800 failures=0 errors=0 skipped=0（107 套件）=792+8 接线 seam 测；p2p 十一关键词持平；probe4/MIC_SOURCE_MIC app/ grep=0；变异锚 3 条（重试上限比较/删成功复位/归零转发恒 false）实红且还原 md5 b494095b 对拍一致
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/icons/AppIcons.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/AudioCaptureWiringSeamTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/AudioCaptureSourceDriftTest.kt
]
```

## 2026-10-20（四）port 4cap 战役收口：三车道线性合入 792 绿终验，②C 口径精确命中

**合入**（主代理执行，L10 线性序）：T2（`16f6f85`，8 文件 +386/−157，零冲突——T2 的 DEV_LOG 证据块追加在文件尾、战役条目在头，区域不重叠自动合并）→ T3（`7af84ed`，7 文件 +480/−1，零冲突）。

**合并终验**（主代理亲跑，不采信车道自述）：`--rerun-tasks` BUILD SUCCESSFUL（1m22s，25 tasks）；XML 复算 **tests=792 failures=0 errors=0 skipped=0**（106 套件）——754 基线 + T1 五层 32 测 + T2 六测 = 792，与拍板②C 预测（796−4）精确一致；p2p 十一关键词与开工前基线逐一持平；probe4 三符号与 MIC_SOURCE_MIC 合并树 grep=0。

**战役总账**（`aa94dd9..7af84ed`）：代码 10 改 + 2 新（VoiceTapRouter.kt 新建）+ 7 测试文件；docs 一组（fork 三验证档零漂移搬运 + 姊妹仓索引 + ADR 0001 stub + 文档债两顺手项）。三车道 progress 档与 T2 三票 DEV_LOG 条目（本条目下方，已自文件尾归位顶部）齐备。

**待办**：①`ce-code-review` 独立评审（maker≠checker，非平凡改动义务）；②triage 分级；③`ce-compound` 沉淀。评审通过前本分支不推 origin、不合 main。

```evidence
verify_cmd: cd /e/desk-pet-android && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL in 1m 22s (25 tasks executed)；XML 独立复算 tests=792 failures=0 errors=0 skipped=0（106 套件）；p2p 十一关键词持平基线；probe4/MIC_SOURCE_MIC 合并树 grep=0
files_touched: [docs/DEV_LOG.md, PROGRESS.md]
```

## T2 port-4cap Tap-to-Talk 票1：VSM stopListening 吞语音守卫（2026-10-20，fork 锚 e0e2eb2）

病 B（maxLevel 过线但 manual release 先到→吞已判定语音，2026-10-17 板端 4 轮实证）移植修复：stopListening 在 vad.stop 前增 S51 continuation hold 检查（ticksLeft=0 强制续听窗即尽→dispatchContinuation 照常派发，有货送货）；未判定仍走既有 isSpeaking/backToIdle 分支。与 cancelCurrentTurn（放弃本轮=丢弃）语义分界不变，S51ContinuationTest 锚不受影响。

```evidence
verify_cmd: （2026-10-21 勘正：本块实跑缺 PATH export，证据形态不合规——正确口径见 PROGRESS.md「单测基线口径」行；结论数字已由 2026-10-21 收口条目全套件带完整前缀复跑覆盖）cd /e/desk-pet-wt-t2 && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks；变异锚 M2=删守卫块单跑 VoiceSessionManagerStopReleaseGuardTest --rerun-tasks
verify_claim: 全套件终态 BUILD SUCCESSFUL + XML 聚合 760 tests/0 failures/0 errors/0 skipped（基线 754+新增 6）；M2 红=stop during continuation window dispatches -> expected:<PROCESSING> but was:<IDLE>（对照组 stop with plain silence PASS 证特异性）；cp 还原 md5 27da208c548518c19943b6e11c44ed1a 一致、pendingContinuation 回归
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerStopReleaseGuardTest.kt
]
```

## T2 port-4cap Tap-to-Talk 票2：VoiceTapRouter 点按分流（2026-10-20，fork 锚 e0e2eb2）

免按住模式真值表纯逻辑（零 Android import）：点一下=语音入口唯一手势，结束时机归状态机（VAD 判停/S51 续听/30s 超时），不绑人手松手。真值表两格：LISTENING→END（stopListening=有货送货）；IDLE/PROCESSING/SPEAKING→START（startListening；SPEAKING/PROCESSING 走 VSM 既有 cancel 接续 barge-in，UI 层不调 cancel）。两处 UI（ChatScreen 输入条/SettingsScreen 语音自检）共用同一分流。

```evidence
verify_cmd: （2026-10-21 勘正：原记录无 cd/export 前缀却标注「JDK17 前缀」，记录不实；正确口径见 PROGRESS.md「单测基线口径」行，结论数字已由 2026-10-21 收口条目带完整前缀复跑覆盖）变异锚 M1=删 LISTENING 分支全返 START 后单跑 VoiceTapRouterTest --rerun-tasks；绿证并入全套件终态跑
verify_claim: M1 红=listening routes to END -> expected:<END> but was:<START>（3 个 START 组不受扰）；cp 还原 md5 7e732b5db161d820b6da8042d76016cb 一致、MUT-M1 零残留；全套件 XML 760/0/0/0
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceTapRouter.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceTapRouterTest.kt
]
```

## T2 port-4cap Tap-to-Talk 票3：UI 三处点按化（2026-10-20，fork 锚 e0e2eb2）

R2 红线逐 hunk 合并（禁整文件覆盖）：InputBar 点按语义以上游手势族落地（awaitEachGesture+waitForUpOrCancellation 上-下沿配对），保留上游 neverEqualPolicy levels/WaveformBars(levelsState) 签名，移植 fork rememberUpdatedState 过时闭包守卫与两态文案（点击说话/正在聆听，点击结束），三态 when 底色退役为两态（outline 聆听高亮/surfaceVariant 独立底）；ChatScreen voiceState 派生自 VSM stateFlow（禁本地布尔镜像）+voiceTap 单路入口（权限/失败收敛沿用原语义）；SettingsScreen 仅 VoiceSelfCheck 区+imports（KwsSection 采麦源行 T1 属地零触碰，diff 6 hunk 全落 imports+810-870），Button onClick 直通分流，手势族 4 imports 随按住语义退役。

```evidence
verify_cmd: （2026-10-21 勘正：本块实跑缺 PATH export，证据形态不合规——正确口径见 PROGRESS.md「单测基线口径」行；结论数字已由 2026-10-21 收口条目全套件带完整前缀复跑覆盖）cd /e/desk-pet-wt-t2 && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL (1m 24s) + XML 聚合 760 tests/0 failures/0 errors/0 skipped；编译即证三文件接线（VoiceTapRouter 经 ChatScreen/SettingsScreen 编译消费、InputBar 手势族编译通过）；R2 领先符号对拍见 T2-progress.md
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt
]
```
## 2026-10-20（三）T1 车道收口：采麦层 5 层链移植完成（786 绿双证）+ 主代理独立复核全过

**T1 完成**（子代理串行执行，主代理 maker≠checker 独立复核）：fork rk3288@7bef28c 采麦层 5 层依赖链按序移植上游（分支 `port/baseline-4cap-upstream-47b240a`），每层一 commit（含 fork 锚 hash+红绿双证+变异锚结论）：

| 层 | fork 锚 | commit | 内容 | 绿数 |
|---|---|---|---|---|
| 1 | 63a79ca | `9dd622c` | ZeroBlockDetector + isAllZeroPcm16 + startRecordWithRetry + pumpBlocks stopWhen | 754→762(+8) |
| 2 | a0944a4 | `4e8789b` | expectedSourceFor/isSourceDrift 纯函数 + buildRecord S57 解析接线 | 762→766(+4) |
| 3 | e0b5c36 | `8c480c5` | TerminalStartFailureDetector + onTerminalStartFailure seam + VCF.selfHealRestart + Prefs voice_proc_last_restart | 766→772(+6) |
| 4 | 13ec3f9 | `a3620af` | **②C 裁决跳过实现**，仅合并 PrefContractTest selfheal 断言 | 772→773(+1) |
| 5 | 04e8015 | `38a65ec` | SoftwareAgc + 14 AGC_* 常量 + emit 单点集成 | 773→786(+13) |

**②C 裁决落地**：SourceDriftTest fork 侧 8 条中 4 条引用 MIC_SOURCE_MIC=3，全数剔除并逐条记录（progress 剔除清单）；无 mode 3 符号、无第 4 档 UI、coerce 保持 (0,2)、PrefContract 注明「0-2 三档（上游 S57 只读锁定）」。绿数口径：工单预测 T1 终态 790±1，实际 786，差 4 即上述剔除——**战役终态 786 + T2(+6) = 792 与拍板②C 预测（796−4）精确一致**。

**主代理独立复核**（不亲信子代理自述）：①XML 复算 tests=786 failures=0 errors=0 skipped=0 与自述一致；②probe4 三符号（onPreAgcBlock/dumpPcm/PreAgcDump）全树 grep=0；③MIC_SOURCE_MIC grep=0；④p2p 十一关键词（ShelfMcpBridge=11…currentAgentId=2）与开工前基线逐一持平零回退；⑤触碰文件 9 个（4 主代码 + 4 新测试 + PrefContractTest 合并）全部在工单范围内零越界。

**偏差补记**：T1 五笔代码 commit 未随附 DEV_LOG evidence 块（commit_guard 影子模式不阻断）——由主代理以本条目统一补记。

```evidence
verify_cmd: cd /e/desk-pet-android && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL；XML 独立复算 tests=786 failures=0 errors=0 skipped=0（子代理与主代理双证一致）；probe4 三符号 grep=0；MIC_SOURCE_MIC grep=0（②C 遵守）；p2p 十一关键词与基线持平
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt,
  app/src/main/java/com/xiaoduo/deskpet/util/Prefs.kt,
  app/src/main/java/com/xiaoduo/deskpet/util/PrefContract.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt,
  app/src/test/java/com/xiaoduo/deskpet/util/PrefContractTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/AudioCaptureZeroBlockDetectorTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/AudioCaptureSourceDriftTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/AudioCaptureTerminalStartFailureDetectorTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/SoftwareAgcTest.kt
]
```

## 2026-10-20（二）阶段 1 启动：三车道工单发布 + T1/T2/T3 子代理并行拉起

**阶段 1 执行**（接上条拍板）：三车道工单已发布 `.scratch/port-4cap/`（T1-ticket.md 采麦层三合一/T2-ticket.md Tap-to-Talk/T3-ticket.md docs 移植），各含环境红线、f2p 验收口径、防作弊变异锚与 progress 落盘义务。T1 独占主工作区 `E:/desk-pet-android`（分支 `port/baseline-4cap-upstream-47b240a`，兼作③B 基线车道）；T2 独立 worktree `E:/desk-pet-wt-t2`（分支 `port/t2-tap-47b240a`，压缩前已备）；T3 独立 worktree `E:/desk-pet-wt-t3`（分支 `port/t3-docs`）。三车道按 RESEARCH §5 准入并行（T1∩T2 仅 SettingsScreen.kt 区域隔离），线性合入顺序 T1→T2→T3 由主代理收口执行。

## 2026-10-20（一）port 4cap 战役拍板（①A ②C ③B ④A）+ 战役分支建立——4 项能力自 fork rk3288 移植上游

**战役背景**：Phase 0 调研完成（`.scratch/port-4cap/RESEARCH-phase0.md`，四路只读子代理汇总，10 项主代理抽验全过），四项阻塞决策经用户拍板确认，随即进入阶段 1（发布三车道工单 + T1/T2 并行）。

**四项拍板**：
1. **①A 分支策略**：从上游 `main@47b240a`（754 绿）开战役分支 `port/baseline-4cap-upstream-47b240a`（已建并 checkout，指向 47b240a）。D 途径实证 fork hash 在上游对象库全部无效，cherry-pick 不可行，逻辑移植 re-apply 是唯一路线。
2. **②C 采麦源口径**：采纳上游 S57 只读锁定语义（SettingsScreen.kt:970，依据 2026-09-21 生产事故：漂离 mode 0 后底噪抬 30~50 倍判停全废），**裁掉 fork 第 4 档可点击循环**（MIC_SOURCE_MIC=3 档位 UI + coerce 扩展不带入），代价 = −4 fork 自有基线断言（预期 796 → 792 绿）。
3. **③B 基线形态**：T1 车道本身兼作基线（单代理串行跑 5 层，实测三数=对照分母）；省去独立串行人工基准（原形态 A ≈ +1~2h 墙钟/+60~120k token）；牺牲「并行 vs 串行」对照分母的完整性（瓶颈在 T1 时对照本就测不出收益），分母代表性折扣在收口报告中声明。
4. **④A 工单位置**：三车道工单发布 `.scratch/port-4cap/`（工具性文件不进 git），决策同步本条目。

**三车道与并行准入**（RESEARCH §5）：T1 采麦层三合一（9 文件，内部 5 层依赖链 63a79ca→a0944a4→e0b5c36→13ec3f9→04e8015 必须串行，主工作区主分支）；T2 Tap-to-Talk（7 文件，独立 worktree + 分支 `port/t2-tap-to-talk`）；T3 docs 移植（4 文件零代码，独立 worktree + 分支 `port/t3-docs`）。T1∩T2 触碰交集仅 SettingsScreen.kt（区域物理隔离：T1 动 KwsSection ~936；T2 动 VoiceSelfCheck ~785-803+import 区），满足 L10 并行三前提（按文件归属切+程序化冲突验证+独立 worktree），线性合入顺序 T1→T2→T3。

**移植红线**：fork 工作区一律不动（probe4 未提交态是战役资产），唯一读取通道 = `git show HEAD:<path>`；禁带入 probe4 三文件（onPreAgcBlock/dumpPcm/PreAgcDump，上游 VFS:175 S34 CPU 202% 血泪注释）/ChatStore API25 兼容件/fork 版 VoiceStateRouter.kt/keywords.txt；禁 cp/rsync 整文件覆盖（R7），上游领先符号 grep 计数不下降（p2p 清单见 RESEARCH §4）。

**验收口径**：移植前 f2p 红（新增测试在移植前编译/断言失败）→ 移植后绿；全套件 `--rerun-tasks` + ElementTree 双证；变异锚三步法（cp 备份→删守卫→红→还原→绿→md5 对拍+grep MUT 零残留）；预期终态 ≈792 绿（=754 上游基线 + 41 可移植测试 + 1 PrefContract selfheal − 4 第 4 档断言，精确数以移植实跑为准）。

## 2026-10-19（一）main 定型基线打分支+tag（纯 git 治理）+ dsh-pet 动画可移植性调研

**版本管理**：应用户令对 main 定型态（`00c5c97`）建分支 `baseline/main-finalized` + tag
`main-finalized-20261019`，均已推 origin（GitHub wzejia/desk-pet-android）。无代码改动。

**main vs refactor/p0 差异勘明**（用户问「除经验文档外还有无区别」，`git diff main..refactor/p0` 实证）：
p0 = e288037 + Phase 0 守护成果——PetOverlayState 活性台账接线 4 文件（PetOverlayService/
MainActivity/SettingsScreen/VoicePlayer，~+70/-6 行）+ `PetOverlayStateTest.kt` 87 行（7 条测试，
761 绿 vs main 754 绿）；main 刻意回退到重构前 e288037 **字节一致**。p0 分支 HEAD 现为 `d866ec1`
（板上 APK 5a0103eb 对应更早的 `19ea825`）。

**dsh-pet 调研**（跨仓，文档落 `D:/mijijia/docs/research/2026-10-17-dsh-pet-animation-porting-assessment.md`）：
dsh-pet = DeepSeek Harness 桌宠 Web 插件，动画=VP9-alpha webm × 双 `<video>` 交叉淡入（180ms）× 纯函数
动画链/拖拽甩抛物理。结论：三硬约束（Android 原生播不了 VP9-alpha；**素材禁止商用**——只能借 MIT 代码
逻辑与素材管线思路；WebView 路线 +100MB 不可行）；可搬=素材生成管线（AI 绿幕视频→抠像→脚线归一化，
末端改出 Android 格式）+ 动画链权重/交叉淡入/弹簧甩抛物理直译 Kotlin。两个前置拍板待用户：①**S13 静态化
决策（2026-08-24 拍板）是否推翻**；②素材格式选型（建议首步=按动画分 spritesheet + 按需解码 LRU，方案 B
animated WebP 备选）。克隆已删，再研=重 clone（commit `6c5f0432` 锁定）。

**Compose 渲染性能优化**（`jetpack-compose-performance` skill，6 文件 +51/−21，九处修复：graphicsLayer
相位延迟 ×2 / contentType ×2 / derivedStateOf / neverEqualPolicy / floatArray 常量 / SimpleDateFormat /
prefs 循环外提）：双路评审 Ready to merge，行为等价纯性能。

```evidence
verify_cmd: cd /e/desk-pet-android && export JAVA_HOME='C:/Program Files/Microsoft/jdk-17.0.20.8-hotspot' && export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL, 754 tests, skipped=0, failures=0, errors=0
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/ui/components/MarkdownContent.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SessionDrawer.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt
]
```

## 2026-10-13（一）带密钥 APK 构建（main 重构前态）——产物留存未部署，用户令暂不构建

**背景**：用户要求按既往做法构建带 API 密钥的 APK（secrets/cloud.properties → BuildConfig，
机制自 2026-08-13 起未变）。

**构建**：`assembleDebug` 绿 → `app-debug.apk` 96,002,614B，md5 前缀 `6edaebcc`；代码=当前
main（重构前 e288037 态，754 绿口径）；debug 签名（与板上 5a0103eb 同源，可 install -r）。

**密钥核验**（只验长度不验值）：`CLOUD_KEY` ✅116 / `ALIYUN_SPEECH_KEY` ✅116 /
`VOLC_SPEECH_KEY` ⚠️**空**——secrets 文件该键自 08-19 起即空，**既往所有带密钥构建（含板上
5a0103eb）同此状态**：火山链路走 NetConfig 回退（SP → BuildConfig → 空），板上 prefs 配过
即不受影响。如需内置：补 `secrets/cloud.properties` 后重建即可。

**用户令**：暂不进一步构建/部署，文档先行。产物未安装任何设备。

## 2026-10-11（七）main 定型：代码回退重构前 e288037 态 + 经验文档全保留，重构版封存 refactor/complete

**用户令**：保留重构后的经验文档，但 main 对应代码=重构前版本，且方便回退。

**施工**：重构完整版封存**分支 `refactor/complete` + tag `refactor-complete-20261011`**（=
aa79fbd，859 绿，已推送）；main 上 `git checkout e288037 -- app/` 整目录还原。

**坑×1（已修）**：`git checkout <commit> -- <dir>` **只还原源树已有文件、不删源树外新增文件**
——首轮漏删 73 个重构期新增文件（api 包/voice 子包化/SEC-3/S22/守卫件及其测试），与还原后
旧文件并存 → 重声明冲突构建红（PetOverlayState 双声明/Settings 拆分件与单体并存）。
修正=comm 差集（ls-tree e288037 vs ls-files）补删 73 文件。

**验证**：`git diff e288037 HEAD -- app/` = **0 行（字节一致）**；全量
`testDebugUnitTest --rerun-tasks` = **754 绿 skipped=0**（e288037 基线吻合）。

**现状**：main `c6e7243` = 重构前代码（754 绿）+ docs/ 21 文件经验文档全保留
（spec v0.4/Phase0-4 审计/§5.4 复测/内存归因/Phase4 门槛评审）；
**回退到重构版 = `git checkout refactor/complete`**（板上部署走回灌法，DEV_LOG（二）流程）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: tests=754 failures=0 errors=0 skipped=0；git diff e288037 HEAD -- app/ 空（字节一致）
files_touched: [app/（73 删+还原）, PROGRESS.md, docs/DEV_LOG.md]
```


## 2026-10-11（六）用户拍板：优化不做了，生产板回滚 refactor/p0（main 版下板）

**决策**：用户审阅归因分析后拍板——优化不施工、**生产板改回换包前版本**（refactor/p0 @
19ea825，APK `5a0103eb`）。main 分支代码与 859 绿基线原样保留，随时可再部署。

**回滚流程**（本日（二）换包的逆操作，全流程零新坑）：快照当前 main 态数据（153 文件含
2 个 secure 库，tar 679KB → `.scratch/prod-deskpet-data-backup-20261011-rollback-snapshot/`，
今日 3 个变动会话文件在其中可追）→ 卸载 main → 装回 p0 APK（装前 md5 复核 `5a0103eb`）→
push 晨间 backup.tar（md5 对上）→ run-as tar xf 回灌 → **复验 151/151 全等** → 起活。

**起活健康全绿**：无 FATAL；149 sessions 恢复；双窗+first-frame 锚；always-on KWS
（shadow=true, gate=0.015）+ 采麦 source=7；shared_prefs 恰 3 个 legacy（secure 库随卸载清除，
零残留）。顺带观察：p0 本轮冷启动首帧 334ms（added→drawn），与 main 复测中位 296 同量级——
佐证复测的 +20ms 主要是板况噪声而非 main 回退（单样本，仅记录不定案）。

**状态影响**：部署批剩余真机项（三链路取证/Keystore 跨重启/OverlayBubbleHub 真机回归）随
main 下板**作废挂起**；优化菜单（影子引擎 −15MB / R8 −20~30MB）文档留存
`docs/audit/2026-10-11-perf-retest/optimization-analysis.md` 待未来重启。板上版本口径回到
spec §2 基线态（空闲 219.2 / KWS 221.2）。

```evidence
verify_cmd: 回灌复验 diff 晨间 manifest（151/151 全等）+ logcat 健康锚（无 FATAL/session restored/dual windows/capture start）
verify_claim: 板上 com.xiaoduo.deskpet=refactor/p0（5a0103eb），prefs 3 legacy 零残留，进程健康
files_touched: [docs/DEV_LOG.md, PROGRESS.md]
```


## 2026-10-11（五）部署批 §5.4 自动项复测：TOTAL 改善 / NH 台阶 +1.7⚠ / 首帧中位 +20ms⚠（均待定性）

**口径**：生产板 main 版（e646b5f3），协议同 Phase 0；**NH 同列纠正**（App Summary RSS 位，
初解析误取表格行 PSS 列差 1MB——已纠，基线同列复核 86.3 吻合）。现场项未测待现场批。

- **TOTAL PSS 不回退 ✓**：空闲 219.2→**217.1**（−2.1）/ KWS 221.2→**215.3**（−5.9）；
  抖动稳态带 215.8~216.7→215.3~216.2（左移带内），序列无爬坡无泄漏形态。
- **NH +1.7~2.0MB 稳态台阶 ⚠**：88.0~88.2 紧带（10 样本零漂移，非泄漏）；假设=Phase 3 缓存
  结构性成本（Markdown 列宽缓存/ESP 池化/PetLogger 缓冲），未归因 → heap dump 差分待
  10-24 复评或现场批。
- **首帧中位 276→296ms（+20ms）⚠**：n=10 带 269~382 与基线 n=7 带 259~289 重叠过半（两版各
  有离群 679/484）；ESP 初始化在 added 之前不在首帧段，非首嫌疑 → 现场批追加 n≥15 定性。
- 产物 `docs/audit/2026-10-11-perf-retest/`（含 protocol.sh 可复跑，末尾自动还原 mijijia 前台）。

```evidence
verify_cmd: bash .scratch/perf-retest-20261011/protocol.sh（+追加 5 轮冷启动）后 python 解析
verify_claim: 空闲 217.1/88.0、KWS 215.3/88.0、抖动带 215.3~216.2、首帧 n=10 中位 296ms；原始 dumpsys/锚行入库 audit 目录
files_touched: [docs/audit/2026-10-11-perf-retest/README.md, docs/audit/2026-10-11-perf-retest/protocol.sh]
```


## 2026-10-11（四）Phase 4 module 化门槛评审：本周期否决切换（门槛 1 时钟 1/14 天），数据做全待 10-24 复评

**结论**：结构面全绿（复扫环 0/例外边 8 不增/buildgen 1），但 spec §4.4 门槛 1 的 **≥2 周无环
回流**时钟客观未满（Phase 1 落地 10-10 → 窗口至 **2026-10-24**，评审日第 1 天）——按「全过才
切、否决即停」**本周期不切**。报告 `docs/audit/2026-10-11-phase4-gate-review/`。

**门槛 2 闭包代理**（新脚本 `.scratch/phase4-gate2-closure.py`，候选序 DAG 判定，main+test 分开
量）：PASS=api/device/pet/agui（net 修 BuildConfig 后 PASS）；**test 放置违规 3 文件**首次量出
（util×2→net 对账测试、cloud×1→voice、chat×1→ui，主源全净）；hub 硬阻断 8 边=8 文件
（voice2/chat1/ui5）。

**门槛 3**：工厂模式已一致（随被装配包走、调用全向下、无 kapt/ksp）——无需新决策。
**门槛 4**：双基线建成——结构传播面（api 102/util 117/voice 89/chat 53/ui 37 文件，上界）+
实测增量墙钟（热 daemon ABI 真变更探针：api 1.9s / voice 1.5s，即改即还原零残留）；IC 脏集
文件数无公开读数，以两把尺同测对比为切时口径。

**Hub 接口化评估**（voice/chat/ui 硬前置）：面清点完毕=instance 句柄 13+companion 4 法+VFS 2
钩+PetOverlayState 5 成员 ≈20 成员接口，diff 中等机械；建议复评通过后随第一张切分票同刀，
不单独预研。

**前置工程清单**（复评前可做、互不阻塞）：①net BuildConfig 注入（解锁 net）；②3 测试归位
（解锁 util/cloud/chat 闭包）；③（可选）hub 接口化（解锁 voice/chat/ui）。

```evidence
verify_cmd: MSYS2_ARG_CONV_EXCL="*" python "E:/desk-pet-android/.scratch/dep-analysis-20261010.py" && python .scratch/phase4-gate2-closure.py
verify_claim: 环 0/例外边 8/buildgen 1 复现；闭包 PASS×4；探针即改即还原（git status 干净，859 绿基线未动）
files_touched: [docs/audit/2026-10-11-phase4-gate-review/README.md, .scratch/phase4-gate2-closure.py]
```


## 2026-10-11（三）ocr[394] wakeack 小票收口：单飞保证提为可测微类+并发变异锚（859 绿）

**裁定（T2 上报两案择一，原文两可）**：PrefetchBackoff 内原子化 gate（failures.compute
预留协议）**不做**——ocr 实证前置条件不成立：生产路径唯一入口 `WakeAckPrefetcher.
maybePrefetch` 已有全局单飞 CAS（两触发点 SettingsEndpointEditor:222 /
VoiceClientFactory:222 同门），并发同 key 预取结构上不可达；原子化是为不可达竞态叠第二套
机制，且预留协议自带「占位后放弃」新失效面（需 TTL 兜底）。**实际缺口=单飞保证零测试**
（藏于 maybePrefetch，绑 Context+真线程 JVM 不可测；ocr[392]/[393]/[395] 均有变异锚，
唯独 [394] 靠 KDoc 裸奔）。

**施工**：单飞门提取为 `voice/wakeack/PrefetchSingleFlight`（internal，9 行，tryEnter/leave），
WakeAckPrefetcher 换用（行为零变化——同 CAS 语义，daemon finally 清不变）；新增
PrefetchSingleFlightTest 并发契约×2（8 线程×8 轮仅一飞 / leave 后重开）。**变异锚双红实证**：
CAS→check-then-act → winners≠1 红；leave 空操作 → 双用例红（门永不重开+下轮全撞死门）。
PrefetchBackoff KDoc [394] 条目同步收口（指向门类+裁定理由）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && ./gradlew.bat assembleDebug
verify_claim: tests=859 failures=0 errors=0 skipped=0（857+2 PrefetchSingleFlightTest）；变异×2 实证红→还原绿；assembleDebug 绿（APK e13649dd）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/wakeack/PrefetchSingleFlight.kt, app/src/test/java/com/xiaoduo/deskpet/voice/PrefetchSingleFlightTest.kt, app/src/main/java/com/xiaoduo/deskpet/voice/wakeack/WakeAckPrefetcher.kt, app/src/main/java/com/xiaoduo/deskpet/voice/policy/PrefetchBackoff.kt]
```


## 2026-10-11（二）部署批步骤 A：生产板换包 main @ dce2399（回灌法，用户逐次授权拿到）

**换包全流程**（照 Phase 0 2026-10-10（四）打法）：force-stop → 数据备份（shared_prefs×3 +
chat_sessions×148 = 151 文件，exec-out tar 流拉回 + 双端 md5 **151/151 全对**，存
`.scratch/prod-deskpet-data-backup-20261011/`；尺寸 674304B 与 Phase 0 备份一致，数据未变）→
旧 APK 拉回（md5 `5a0103eb`，96MB，与 Phase 0 装板包一致——板上确为 refactor/p0 版，回滚双保险）→
卸载 → 装 main 版（app-debug.apk md5 `e646b5f3`，92.5MB @ main dce2399；00:31 P2 修复后构建，
gradle up-to-date 判定与 HEAD 同源——dce2399 起仅 PROGRESS 文档差异）→ push tmp（md5 对上，
命名带日期防陈旧文件坑）→ run-as tar xf 回灌 → **复验 151/151 全等** → 清 tmp → 起活。

**起活健康全绿**：无 FATAL；`session restored: 149 sessions, 8 messages`；双窗 added +
`overlay first-frame drawn` 锚；双 KWS 引擎 newFromAsset 无异常；`capture start, source=7`；
`always-on KWS started (shadow=true), gate=0.015`。**SEC-3 首启实证**：
`xiaoduo_net_secure.xml` + `xiaoduo_settings_secure.xml` 双加密库创建，无 "encrypted prefs
unavailable" fallback 警告，logcat 无 401（迁移写路径已走通；**跨重启读回路径待验**=挂账 Keystore 跨重启项）。

**坑重演×1（已按既有解法规避）**：adb push 本地路径 POSIX 形式报 cannot stat——按 09-23 沉淀
改 Windows 形式 `E:/...` + `MSYS_NO_PATHCONV=1` 一次过。

**挂账（部署批剩余，现场项另约）**：语义三链路取证（spec §2）+ §5.4 指标复测（播报中三态/
唤醒→应答音喊话即采；空闲/KWS dumpsys 可 adb 自动）→ OverlayBubbleHub P2 票（拆分+真机回归）→
SEC-3 真机 Keystore 跨重启读回 → 之后本地两件：ocr[394] 原子化 gate（wakeack 小票）、Phase 4
module 化门槛评审（spec §4.4 四条，net BuildConfig 边先改注入）。

## 2026-10-11（一）Phase 3 收口：opus 评审可合入 + P2 修复（remove 复活陷阱）+ 合 main

**opus 独立评审**（隔离 worktree @ a045459，只收客观材料）：五项验收锚全 PASS（855/0/0 真重跑、
构建绿、环 0+hub 8+buildgen 1、资产逐字不变、红线六条零触碰）；行为零变化抽查 6/6 通过；变异锚
抽查 3/3 实测真红（含「No tests found 不计红」的口径自纠）。**总评：可合入**。

**P2 修复（评审 finding #1，Phase 内闭环不留尾）**：SEC-3 迁移包装 `edit()` 返回包装
Editor——remove 同步删 legacy 同键（消费方显式删除意图优先于迁移保护；否则升级板
TokenStore.clear（AguiClient 401 重登 6 处链）后旧 token 经 LEGACY 回落复活再吃一轮 401）。
remove 只动被显式删除的键，NetConfig 直读的端点/账号键不受影响；clear() 无消费方，透传新库
+KDoc 警告混居限制。TDD：红（复活复现）→绿→变异（删 legacy 分支）红→还原。**857 绿**
（855+2）+ assembleDebug 绿。
**P3×2 留痕不修**（评审判可缓）：①PetLogger.write 失去 @Synchronized（init 前并发行只进
logcat，窗口极窄有兜底）；②logFilePath() 跨天瞬时显示未存在文件（无数据影响）。

**T5 合入**（855 绿持平，纯收敛）：重复簇 5 处对齐（RMS_FLOOR/TtsVoice 兜底/AG-UI 双块/
ShelfNav page id/ThinkCleaner 正则）+ 死代码 2 处实锤删除（AudioCapture.record 死字段、全屏页
冗余预热）+ 5 条上报不动手（接口签名/root 红线区/存疑不变量）。

**挂账（部署批，待领导逐次授权）**：①生产板换包（refactor/p3 版，回灌法）→ 语义三链路取证
（spec §2）→ §5.4 指标复测不回退判定（空闲/KWS adb 自动 + 播报中/唤醒→应答音现场项）→
OverlayBubbleHub P2 票（真机气泡/双击回归）→ 真机 Keystore 跨重启验证（SEC-3）；②ocr[394]
原子化 gate（wakeack 地界小票）；③Phase 4 门槛评审（spec §6）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && ./gradlew.bat assembleDebug
verify_claim: tests=857 failures+errors=0 skipped=0（855+2：SecurePrefsTest remove 语义×2）；变异（删 legacy 分支）红→还原绿；assembleDebug 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/util/SecurePrefs.kt, app/src/test/java/com/xiaoduo/deskpet/util/SecurePrefsTest.kt]
```


## 2026-10-10（九）Phase 3 进行中：T4 合入（合并树 793 绿）+ 主对话 P3-2 收口（796 绿）+ 查证 2 条闭环

**T4 合入**（merge 58d8a6c ← refactor/p3-t4 三 commit：53ba724 SEC-3 / a1ba709 PrefContract /
47cf230 PetLogger）：独立核对（3 commit 地界零越界、消费方接线 diff 抽查吻合）→ 合并树复跑
**793 绿 skipped=0**（770+23）。agent 上报处置：①ocr[372] AppLauncher 改引 + ②Prefs 内联默认值
收敛单源 → 归收尾「常量收敛」小票（对账测试已钉住漂移）；③security-crypto 1.1.0=Google 弃用库
终版（附录 B 效果优先合规），长期替代（DataStore+Keystore）挂部署批评估；④PetLogger 强杀丢
<8KB 缓冲尾部权衡在案（历史逐行 open/close 无此窗口）。

**P3-2 静默降级+降级期禁写**（附录 B 4 拍板落地，refactor/p3，chat/ 地界）：
- `ChatStore.createSession()` 改可空（degraded 时 logger 显式留痕返 null，不再 `error()` 抛）；
- 新增 `ChatDegradedPolicy`（纯 JVM：Op 三类写/读分面 + shouldMarkDegraded + allowWrite）；
- `ChatViewModel` 六点接线：init 建会话失败→降级运行不崩溃+只读态；send/upload 入口
  allowWrite 守卫+createSession null 双保险；newSession 降级拒（保持当前会话）；
  rotateSessionIfIdle 降级不轮转旧会话继续（原吞异常继续发送等价）；deleteSession 回落
  建会话失败→无当前会话只读（next 可空化消息加载）。degradedWrites 进程级 @Volatile，
  重启重试（ChatStore degraded 同生命周期）。
- TDD：红₁（用例 15 期望改「降级返 null 不抛」——拍板推翻原「向上抛」口径，实测红）→
  绿（chat 包全绿）→ 全量 **796 绿 skipped=0**（793+3：ChatDegradedPolicyTest）→
  变异锚（allowWrite 恒放行）`degradedRefusesAllWrites` 红 ✓。UI 反馈按拍板最小面=显式
  日志（不碰 Phase，避免新 UI 行为——拍板比较基线是「崩溃 vs 降级」）。

**查证 2 条闭环（均维持 P3 不升级）**：①ocr[161] `NetConfig.saveAll` 唯一调用方
（SettingsEndpointEditor:208）漏传 `llmCloudProvider`（每次保存覆盖回 "openai"），但
`llmCloudProvider()` 全仓零消费方 + UI 无编辑入口 → 覆盖无观察面；footgun 记收尾清单
（saveAll 收参或删死键）。②ocr[375] `org.json:json` 仅 testImplementation、生产
debugRuntimeClasspath 零捆绑（依赖树实测）→ AsrClient JVM CCE 属 JVM-only 路径，
Android 用平台完整实现不受影响。

**ocr[382] 双开麦竞态根修**（T1 上报承接，refactor/p3）：VSM `captureMutex` 互斥门 +
`captureExclusivity` 挂起入口；AmbientCalibrator.collectAmbient 复用决策原子化（锁内 check，
采集锁外——订阅同流无双开，流停欠采样窗由 ocr[384] MIN_SAMPLES 兜底）、临时流整窗持锁；
VSM ensureCollectLoop 的 start 同锁（runBlocking）。**线程/锁序审计**（注释留痕）：teardown
不进锁（校准持锁窗内 VSM 流必未跑=no-op cancel，且消除主线程 stopIdleCapture 阻塞面）；
runBlocking 仅在「采麦未跑+校准窗」组合等待（KWS 常开走幂等早退；校准只在设置页，现实
互斥）；叶子锁无死锁环。跨实例双开根因：临时流是 AudioCapture 新实例，OnceGate 实例内
门闩防不了（T1 侦察实锤）。互斥靠库原语无新守卫逻辑，不造伪测试——15 个既有 VSM 行为
守卫测试 + 全量 855 绿为验证主体。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && ./gradlew.bat assembleDebug
verify_claim: tests=855 failures+errors=0 skipped=0；assembleDebug 绿；互斥接线编译过（Mutex/withLock import 修正后）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/pipeline/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/audio/AmbientCalibrator.kt]
```

**WAV 族 cloud 侧收口**（T1 上报回灌，refactor/p3）：`AliyunAsrClient`/`VolcengineAsrClient`
`stripWavPcm`（镜像双胞胎）三路径修复——巨量 data size `start+size` 回绕→无溢出钳位；负
chunkSize 推进回绕致 IOOBE→契约化 IAE；剩余装不下本块→无溢出比较循环退出走 44B 回退（原
行为等价化）。**契约裁定**：ROB-14 既有测试锁定「负 data size 抛 IAE（调用方映射
Result.Error）」——首版「宽容回退」语义与族契约（WavCodec require 一律 IAE）冲突，改判契约
化。新测 `WavPcmStripBoundsTest` 2（双胞胎同测五路径）+ 变异锚（回退旧溢出代码）红 ✓。
843 绿 skipped=0（841+2）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: tests=843 failures+errors=0 skipped=0（841+2）；ROB-14 既有契约保持绿；变异（旧溢出代码回灌）WavPcmStripBoundsTest 红
files_touched: [app/src/main/java/com/xiaoduo/deskpet/cloud/AliyunAsrClient.kt, app/src/main/java/com/xiaoduo/deskpet/cloud/VolcengineAsrClient.kt, app/src/test/java/com/xiaoduo/deskpet/cloud/WavPcmStripBoundsTest.kt]
```



```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: tests=796 failures+errors=0 skipped=0（793 合并树+3 policy 测）；T4 合并树独立复验 793；变异锚 allowWrite 恒放行→degradedRefusesAllWrites 红；用例 15 红转绿（拍板口径反转）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/chat/ChatStore.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatDegradedPolicy.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/test/java/com/xiaoduo/deskpet/chat/ChatStoreTest.kt, app/src/test/java/com/xiaoduo/deskpet/chat/ChatDegradedPolicyTest.kt]
```


## 2026-10-10（八）Phase 3 开工：切票 4 张并行（波 1 已下发）+ 主对话 root 两件收口

**切票**（地界=子包前缀，两两无文件交集，冲突矩阵逐票对过）：T1=voice/kws+audio 健壮性 17 条
（KwsCore decode 族 7 / VoicePlayer 族 6 / AmbientCalibrator 4 / WAV 族 audio 侧）→
`.worktrees/p3-t1`；T4=util/net（SEC-3 透明迁移+PrefContract 族 5+PetLogger 锁）→
`.worktrees/p3-t4`——波 1 两 agent 已下发。待发波 2：T2=voice/client+pipeline+seam+policy
（VSM 续听/盲区拆分+AsrClient 可取消+Factory 纪律+policy TOCTOU）、T3=ui（Markdown 列宽/
PetView 静止 invalidate/Settings 失步）。主对话串行项：root 两件（本轮）+ 查证 2 条 + 重复簇/
死代码剩余/P3-2 接线（排并行票合入后）。任务书 `.scratch/phase3/ticket-t{1,2,3,4}-*.md`
（spec §7.2 口径）。编号漂移裁定：设计文档所引 ocr[26]/[27] 按审计原文在 AudioCapture.kt
（=T1 地界，归 T1）；root 件 2 实体=Thread→scope+runBlocking 校准→launch+stopSelf(startId)。

**root 两件（refactor/p3，TDD 全闭环）**：
- **件 1 ocr[202] onWake TOCTOU**：新增 `WakeEntryGate`（纯 JVM，ReentrantReadWriteLock——
  runExclusive 持读锁执行、close 取写锁关门=天然等待 in-flight 完成，幂等）。service 接线：
  onWake 的 startListening 包 runExclusive（destroyed 预检保留为快路径）；onDestroy 先
  `wakeGate.close()` 等唤醒处理完成再清 sink（孤儿 capture 不可达）。死锁安全实证：startListening
  链路全同步无主线程依赖（cancelCurrentTurn 全同步/playAckThenListen 仅 launch）。红₁编译→
  红₂骨架（close 不等待）`closeWaitsForInFlightBlock` 红→实现绿→变异（去锁）红。
- **件 2 ocr[205] 裸 Thread→service-scoped scope**：新增 `KwsBootstrap`（纯 JVM 骨架：在途拒绝
  重入〔原 initGate/backlog #8a 语义〕+ 销毁后拒绝 + factory 结果交付前查 isActive + 失败
  onFailure 回调〔CE 透传；失败 job 正常结束=可重试，等价原 initGate.release 放行〕）。
  service：主构造 `bootstrapScope` 默认参数（无参构造保留，系统反射实例化兼容）；init/shadow
  改 scope.launch；校准 runBlocking 桥移除改独立 launch（销毁即取消，AudioRecord 由
  AmbientCalibrator 挂起点+finally 释放）；失败收尾 stopSelf() → **stopSelf(startId)**（防旧
  请求失败收尾误停新请求）；onDestroy 单点 `bootstrapScope.cancel()`（先 cancel 后清 sink）。
  initGate（OnceGate）从 service 退役（类本体保留——AudioCapture 仍用）。红→绿→变异 ×3
  （删 isActive 丢弃检查 / 删在途拒绝 / 删 onFailure）全红。
- **验收**：全量 **770 绿 skipped=0**（761+9：WakeEntryGateTest 4 + KwsBootstrapTest 5）；
  assembleDebug 绿；770 之前 WakeEntryGate/KwsBootstrap 单类迭代均带 `--rerun-tasks`。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && ./gradlew.bat assembleDebug
verify_claim: tests=770 failures+errors=0 skipped=0（761 基线+9 守卫测）；assembleDebug 绿；变异锚 WakeEntryGate 去锁/KwsBootstrap ×3 全红实录（见正文）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/WakeEntryGate.kt, app/src/main/java/com/xiaoduo/deskpet/KwsBootstrap.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/test/java/com/xiaoduo/deskpet/WakeEntryGateTest.kt, app/src/test/java/com/xiaoduo/deskpet/KwsBootstrapTest.kt, .gitignore, .scratch/phase3/]
```


## 2026-10-10（七）Phase 2 收口：voice 七子包化 + SettingsScreen 拆分 + 94 条死/幻影 import 清零

**交付**（refactor/p2，12 commits 合 main @ 1f3a3bd）：①voice 32 文件七子包化（pipeline7/audio6/wakeack6/kws5/client2/seam3/policy3；对 spec 六组建议的偏离已记录：echo 并入 audio、client 新增）；②PetView 归位 ui/overlay；③**94 条死/幻影 import 清零**（三批：24 KDoc 误加 + 23 未使用 + 47 phantom 全量清，全部编译器裁决，台账 `docs/audit/2026-10-10-phase0/dead-code-removal-ledger.md`）；④**SettingsScreen 1099→339**（5 兄弟文件：AgentPicker/EndpointEditor/SelfCheck/Kws/Widgets，大文件评估 P1 票）；⑤大文件拆分评估报告入库（P2 气泡 Hub/P3×3 挂批次；ChatViewModel 负结论不立项）。

**验收（全部独立复现）**：761 绿 skipped=0 双重复现；**环 0 / hub 例外边 8 不增 / 分层零违规**；33 搬移正文与 main 逐字等价（评审剥 diff 核对）；rename 相似度 93-99%。**独立评审（opus，固定 commit 隔离 worktree）**：可合入；P2-1（phantom 漏网 37→实测 49 删 47 真死+2 误杀恢复）+ P3×4 全部处置。

**施工方法论教训（三条，全实证）**：①**搬移脚本的引用判定必须剥注释**——KDoc/行注释/字符串里的类名提及（含「App 内」这种普通词）被当真实引用补 import，制造 24+47 条幻影边，其中部分在子包粒度成假环污染审计（顶层包粒度侥幸不显）；②**编译器是唯一裁判**——文本判定只做候选筛选，删/留以编译绿/红定（误杀 2 条 Date 因剥字符串器吞了 `${}` 插值内代码——剥注释器已知局限入台账）；③**评审与施工并发时验收锚必须在 quiet 树上复跑**（评审员中途扫到 hub=23 的中间态假象）。

**决策**：Phase 2 纯结构搬移+死代码清理（行为零变化），不部署生产板（同 Phase 1 论证）；死代码簇剩余项（死分支/死参数/死常量甄别）归 Phase 3 工程族批。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && MSYS2_ARG_CONV_EXCL="*" python "E:/desk-pet-android/.scratch/dep-analysis-20261010.py"
verify_claim: tests=761 failures=0 skipped=0；环 0；hub 8 不增；分层审计零违规；评审独立复现（隔离 worktree 固定 d0f9643）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/**(7子包32文件), app/src/main/java/com/xiaoduo/deskpet/ui/screens/Settings*(6文件), docs/audit/2026-10-10-phase0/(评估+台账)]
```


## 2026-10-10（六）Phase 1 收口：断环三刀 + PetOverlayState 搬出，环 4 对清零

**交付**（refactor/p1，4+1 commits 合 main @ 1ced9fa）：①PetOverlayState 搬出 MainActivity 成独立 root 文件（纯搬移）；②一刀 `api.AsrContract`（Result+LANGUAGE_ZH）/`api.TtsContract` 下沉——cloud↔voice 断；③二刀 `api.ChatMessage` 下沉——chat↔cloud 断；④三刀 `api.BubbleOwner` 下沉（BubbleOwnership 判定逻辑留 ui.overlay）——voice↔ui、ui↔chat 下半环断。全量改写引用（无 typealias，v0.3 定案）：主源 24 文件 + 测试 11 文件；api 包 4 文件 67 LOC 纯净（零 import 零可变态）。

**施工坑 ×2**：①批量替换子串误伤——`AsrClient.Result` 命中 `VolcengineAsrClient.Result` 内部致 6 厂商类型被改成不存在的 `VolcengineAsrContract`，编译器当场兜底，恢复后用负向后顾断言 `(?<![A-Za-z])` 重做；②内部自用常量断引——AsrClient 删 companion LANGUAGE_ZH 后同文件 `addFormDataPart("language", LANGUAGE_ZH)` 断引，编译器兜底改指契约。教训：**批量改写的替换模式必须带词边界，且「删常量后先 grep 本文件内引用」**。

**验收（全部过）**：761 绿 skipped=0（rerun-tasks）；**脚本 v3：包级环 0（原 4 对）+ hub 例外边 8 条不增 + buildgen 1 + 目录异常 0**；资产哈希与基线一致；assembleDebug 绿。**独立评审（opus）**：可合入，P1×0/P2×0/P3×2（KDoc 病句+import 排版，已修）；评审独立复现全部验收声称 + 37 文件 diff 逐文件核实「无一行逻辑变化」+ 批量替换误伤模式 0 命中。

**决策**：Phase 1 纯结构搬移（行为零变化），**不单独部署生产板**——语义真机验收累积到有行为性改动的 Phase（Phase 3 起性能/安全修复）部署时统一取证；761 全绿+资产不变+评审核实为本 Phase 行为守卫主体。

**Phase 2 侦察**（已完成，施工票定稿待做）：voice 32 文件初分 8 组（spec 六组建议 pipeline/kws/wakeack/echo/seam/policy + audio/client 两个自然分组——AudioCapture/VoicePlayer/WavCodec/VadDetector/AmbientCalibrator 归 audio，AsrClient/TtsClient 归 client）；大文件 5 个（>750 LOC）拆分评估待施工票。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && MSYS2_ARG_CONV_EXCL="*" python "E:/desk-pet-android/.scratch/dep-analysis-20261010.py"
verify_claim: tests=761 failures=0 skipped=0；包级环 0（原 4 对）；hub 8 不增；buildgen 1；资产哈希不变；assembleDebug 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/api/, app/src/main/java/com/xiaoduo/deskpet/PetOverlayState.kt, app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, (引用改写 37 文件全账见 merge 1ced9fa)]
```


## 2026-10-10（五）Phase 0 收尾：独立评审可合入 + 4 条 P3 处置（3 修 1 记录）

**独立评审（opus 异构，只喂 diff+spec+测试输出）**：总评**可合入**。评审员本机独立复现：760 绿 / 新增 6 删 0 / 变异锚 M1、M2 双红真守卫 / 红线零触碰 / 行为零变化逐路径核实（含 addView 失败路径、handler 串行性、OnDrawListener 合规）。

**findings 分诊与处置（4×P3，无 P1/P2）**：①markStarted 写序非原子→**修**（先 lastMark 后 startedFlag 的 volatile 序闭窗 + 主线程收编契约 KDoc）；②isAlive 负增量语义与时钟源契约未声明→**修**（KDoc：须同源 elapsedRealtime，负增量视为存活）；③测试常量调参盲区（STALE_AFTER_MS 调 <30s 仍绿）→**修**（新增不变式测试 staleWindow≥2×heartbeat，**761 绿**）；④主线程卡死 >90s 假阴性→**记录不修**（有界权衡：近 ANR 区、旧码同样受限、一次跳过自愈——notes 存证）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: tests=761 failures+errors=0 skipped=0（760+不变式测试；评审 3 条 P3 修复）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, app/src/test/java/com/xiaoduo/deskpet/PetOverlayStateTest.kt, docs/implementation-notes.html, docs/DEV_LOG.md]
```


## 2026-10-10（四）Phase 0 生产板部署 + §5.4 指标基线实测（用户授权）

**部署（回灌法，逐次授权已拿到）**：生产板 10.0.14.18 换装 refactor/p0 @ 19ea825（含守卫+日志锚）。流程全记录：force-stop → 数据备份（shared_prefs×3 + chat_sessions×148 = 151 文件，tar 流拉回 + 双端 md5 **151/151 验通过**）→ 旧 APK 拉回备份（ba8931b2，96MB，回滚双保险）→ 卸载 → 装新包（5a0103eb）→ push tar 回灌（run-as tar xf）→ **回灌复验 151/151 全等** → 起活健康（149 sessions 恢复、双窗 added、KWS 常开 gate=0.015、采麦 source=7）。坑×1：备份完整性首验全红=对照脚本 bug（Git Bash md5sum 输出 ` *path` 星号分隔 vs 板端空格分隔，键不匹配假报错），修正键归一后 151/151 通过——**数据本身零问题**。新日志锚板端实证：`overlay first-frame drawn` 每轮冷启动都出。mijijia 侧未动（旧版兼容：基础设置直拉退化为开 APP，不影响指标）。

**§5.4 指标基线（`docs/audit/2026-10-10-perf-baseline/`）**：
- **内存三态**（dumpsys PSS）：空闲 5min=**219.2MB**（NH 86.3）；KWS 10min=**221.2MB**（NH 86.3，较空闲 +2.0MB 稳态带内）；播报中态待现场喊话。
- **抖动带宽**（10×30s）：稳态 TOTAL PSS **<1MB 带宽**（215.8~216.7，分辨力 ±1MB）、NH ±0.3MB——后续「不回退」判定的最小可判差异。t=30s=192.9MB 是冷启动爬坡不计入。
- **悬浮窗挂窗→首帧**（新增渲染锚）：7 轮样本 259/268/271/276/279/289/679，**中位 276ms**（679 为布局调度离群）。added（窗口挂上）→OnDrawListener 首帧，设备钟。
- **待现场**：播报中三态（喊话触发，起播锚 `playback-start` 已备）/ 唤醒命中→应答音 / trampoline 端到端（mijijia 基础设置按键）/ Chat 首 token。
- 板钟慢 PC ~3.5 分钟实证再现（jitter 文件 PC 钟进度戳 vs 板端数据钟差值吻合）——计时口径全程板钟未破。

**Phase 0 状态**：脚本入库 ✓ / 守卫+测试 ✓（760 绿）/ 线程纪律设计 ✓ / 指标基线=自动化部分全落盘，现场配合项待喊话窗口。

```evidence
verify_cmd: adb shell run-as com.xiaoduo.deskpet sh -c 'find ./shared_prefs ./files/chat_sessions -type f | sort | xargs md5sum'（回灌后全量对照）
verify_claim: 151/151 md5 全等；App 起活 149 sessions+双窗+KWS 健康；基线表 docs/audit/2026-10-10-perf-baseline/ 落盘
files_touched: [docs/audit/2026-10-10-perf-baseline/, docs/DEV_LOG.md]
```


## 2026-10-10（三）Phase 0 代码交付：PetOverlayState 活性记账守卫 + §5.4 日志锚补点 + 线程纪律守卫设计

**PetOverlayState stale-true 守卫（spec §4.3 定案落地，TDD 全闭环）**：活性记账落 PetOverlayState（还在 MainActivity.kt，Phase 1 才搬出）——`markStarted(now)/markHeartbeat(now)/markCleared()/isAlive(now)`（时钟注入纯 JVM 可测）+ `running` 收窄只读（写入方契约显式化）；心跳 30s / stale 窗 90s（3 周期容忍调度抖动）。接线：PetOverlayService 窗口构建成功 markStarted+心跳循环（主线程 postDelayed 30s）、onDestroy removeCallbacks+markCleared；SettingsScreen scale 滑条重启判定读点改 `isAlive(SystemClock.elapsedRealtime())`。正常路径行为零变化（service 活着时 isAlive=true 与原 running 同值）。

**红-绿证明**：①红₁编译（API 不存在）→②红₂行为（骨架 isAlive 无活性判定，恰边界+心跳 2 测失败）→③绿（活性判定补上 6 测全过）→④变异 1 删心跳记账=`heartbeatExtendsAlive` 红 ✓→⑤**变异 2a 删 markHeartbeat 的 startedFlag 守卫=存活（未红）→守卫冗余实锤，删除**（isAlive 的 startedFlag 短路完全覆盖其语义；「变异存活=不是真守卫=删」纪律首次实战）→⑥变异 2b 重设计（markHeartbeat 置 running=复活 bug 形态）=双红 ✓（复活防线由 initialState+clearedThenHeartbeat 双锚）。全量 **760 绿（754+6）skipped=0**（rerun-tasks）。

**§5.4 日志锚补点（3 处，行为零变化纯日志行）**：①VoicePlayer.playBlocking `playback-start blocking bytes=N`；②流式首块 `playback-start stream bytes=N`（播报开始锚——此前无起点日志，响应基线的「播报开始后 3s」采样无法定位）；③PetOverlayService 交互窗首帧 `overlay first-frame drawn`（ViewTreeObserver.OnDrawListener 一次性标志，回调内禁止移除 listener 故标志短路；悬浮窗可见下界锚，上界仍录屏逐帧）。

**线程纪律 3 件守卫测试设计**（Phase 0 只设计，施工 Phase 2 评审定案/Phase 3 修）：`docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md`——ocr[202] onWake TOCTOU→WakeEntryGate 临界区门（close 等 in-flight）；ocr[205] 裸 Thread 生命周期→service-scoped scope 注入（随刀消解 ocr[26] stopSelf 竞态/ocr[27] 校准不可取消）；ocr[53] 进程级 scope→命名属性+resetForTest+契约 KDoc。三件全走「纯 JVM 抽取+薄接线」（无 Robolectric 下唯一可测路），变异锚逐件指定。

**挂账**：§5.4 指标基线**实测**（三态 dumpsys+响应 logcat）需生产板部署带锚包——回灌法换包属破坏性操作，待用户逐次授权；mijijia 侧不升级时基础设置直拉退化为开 APP（旧行为），不影响内存/响应指标测量。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: tests=760 failures+errors=0 skipped=0（754 基线 + PetOverlayStateTest 6 测；变异 1/2b 红、2a 存活删守卫实录上文）；assembleDebug 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoicePlayer.kt, app/src/test/java/com/xiaoduo/deskpet/PetOverlayStateTest.kt, docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md, docs/audit/2026-10-10-dep-analysis/]
```


## 2026-10-10（二）Spec v0.4 执行开工：Phase 0 启动 + 基线漂移修正 755→754

**开工清单执行**（HANDOFF-deskpet-spec-exec 六步）：读 AGENTS/PROGRESS/spec v0.4/HANDOFF → git status 核实未提交 docs（spec v0.4 + DEV_LOG 收尾条目，无混入）→ 入库 `b27dec3` 已推送 → 验基线 → 更新 PROGRESS 接手指引（指向 spec 执行）→ 开 Phase 0。

**基线漂移发现与修正（755→754）**：基线复验实测 **754 绿 skipped=0**（failures=0，`--rerun-tasks` 全量），与三处文档声明的 755 差 1。取证：`fafa28e`（trampoline 重写）删除 `MainActivityOverlayLaunchPolicyTest.kt`（3 测）换入 `OverlayLaunchPolicyTest.kt`（2 测）——被退役测试的守卫对象「overlayOnly=false 输入维度」随 v1 extra 分叉整体删除而消失（现行 `OverlayLaunchPolicy.resolve(granted)` 只看权限位，1×2 决策表已被 2 测全覆盖），**属架构演进的正常减员、非测试缺口**；事故在 fa fa28e 未同步改文档。数字链自洽：752（审计修复）+3（旧策略测）−3 +2 = 754。处置：AGENTS.md 基线行 / PROGRESS.md 执行状态段 / spec §2 锚表+硬约束+§5.3 共 4 处锚修正为 754，历史条目保留当时事实。

**Phase 0 进展**：①依赖脚本 v3 复跑对账 §3.2 **全对账通过**（环 4 对：chat↔cloud/cloud↔voice/voice↔ui/ui↔chat；hub 例外边 8=chat1/ui5/voice2；buildgen 1；矩阵逐格一致；大文件 Top5 一致；声明包≠目录异常 0）——脚本+JSON 入库 `docs/audit/2026-10-10-dep-analysis/`。②余项（§5.4 指标基线含两日志锚补点 / PetOverlayState stale-true 守卫+测试 / 线程纪律 3 件守卫测试设计）进行中。

**启示范例**（方法论）：交接声明的基线数必须实测复核后再开工——本轮直接沿 755 锚开工，后续所有「只增不减」判定会整体错位 1；实测一次即拦住。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL；XML 求和 tests=754 failures+errors=0 skipped=0（锚修正 755→754）
files_touched: [AGENTS.md, PROGRESS.md, docs/spec/refactor-structure.spec.md, docs/DEV_LOG.md, docs/audit/2026-10-10-dep-analysis/]
```



## 2026-10-10 结构重构 Spec v0.3（两轮评审 + 实证修订；纯方案零代码，待评审）

**背景**（用户令）：多维度优化 desk-pet + 原地重排包/module 结构 + 多 Agent 协同机制；本轮只出方案。产出 `docs/spec/refactor-structure.spec.md`（v0.3，经两轮三通道子智能体评审修订）。

**核心增量**（全部实证）：①包级依赖分析脚本 `.scratch/dep-analysis-20261010.py`（v3 可复跑，评审三轮演进）——**新发现包级环 4 对**（chat↔cloud / cloud↔voice / voice↔ui / ui↔chat）+ root 枢纽例外边 8 条（3 类白名单）+ BuildConfig 产物边 1 条（`root→chat→agui→net→BuildConfig` 真链）；10-03 全量审计 489 条对此零命中（盲区首落档）。②「断环三刀」（v0.2/v0.3 修订）：AsrClient/TtsClient 实为**具体类非接口**，下沉对象为其嵌套 `Result` 密封类型 + `LANGUAGE_ZH` → `api.AsrContract/TtsContract`，另 Message（cloud）、ChatMessage（chat）、BubbleOwner（ui.overlay）——环清零；**typealias 保旧引用语法被 kotlin 1.9.24 实测证伪（v0.3），唯一路径=全量改写引用**。③root 枢纽白名单（8 边 3 类）；PetOverlayState = MainActivity.kt:122 object 可变全局态（不下沉 api），stale-true 防线守卫定义为活性记账落 PetOverlayState（JVM 可测，v0.3）。④待拍板复核「4 开放 + 2 挂账」+ 第五节逐项销账。

**评审记录**：第一轮对抗评审 12 findings（P1×2 实证采纳）+ 事实核查 35 项（31 属实/3 勘误/1 存疑加注）；第二轮 delta 复审 16 条闭环核查（14 已闭环/2 部分闭环→v0.3 补齐）+ 新 findings N1-N4（P2×2 已修：typealias 证伪、守卫机制补定义；P3×2 已修：播放开始锚、口径措辞）+ 脚本 v3 代码审查与全量对账通过（环/hub/buildgen/矩阵逐格一致）。

**零代码改动**；审计 digest 与代码地图由两个 Explore 子智能体产出，未落盘（结论已并入 Spec）。

**v0.4（领导拍板收尾）**：①内存/响应指标**暂不设定目标值**——§5.4 改为「协议先行、实测后议」，唯一守门线=各 Phase 复测不回退，改善目标待实测数据后议；②状态改「已认可进入执行」，执行交接文档 `Temp/HANDOFF-deskpet-spec-exec.md`（多智能体编排打法），新会话从 Phase 0 起。spec/DEV_LOG 本会话未提交 git，交接文档已嘱新会话开工先核实入库。



## 2026-10-09 入口功能：密集架基础设置直拉悬浮窗 + 长按菜单删「打开 App」

**背景**（用户令）：mijijia 侧重划入口职责——系统设置加「打开小朵」（拉起 APP），基础设置按键名不变改直拉悬浮窗。desk-pet 侧配两条：①MainActivity 新增 `extra_launch_overlay_only` 直拉路径（TDD：策略纯函数 3 测锁「无权限回落 UI 不黑屏」；`finish()` 在 `setContent` 前不闪屏；singleTask 下 onNewIntent 同路收 UI）；②长按菜单删「打开 App」行（同 `onOpenApp` 参数与 Service 拉起代码一并清）——入口归密集架设置页，菜单跳转类只剩「打开密集架」。755 绿（752+3）。

```evidence
verify_cmd: gradlew.bat --no-daemon testDebugUnitTest（755 绿 skipped=0 failures=0；新增 MainActivityOverlayLaunchPolicyTest 3 测）
verify_claim: overlay-only extra 命中且权限在→起 PetOverlayService 并 finish（不闪 UI）；无权限→常规 UI；菜单无「打开 App」行且全量测试不退
files_touched: [app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt, app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/test/java/com/xiaoduo/deskpet/MainActivityOverlayLaunchPolicyTest.kt]
```

## 2026-10-03 全量代码审计 + P2 修复工程（双通道机扫 + 双路人工 → 三路并行修复 → 集成评审）

**审计**（双通道，管理者统筹子智能体）：ocr scan（100/105 文件，462 条）+ codex-security（全仓 215 文件，5 条）+ 人工兜底 ocr 三连败 5 文件 + 非代码面人工 → P1=0 / P2=43 / P3=410，跨通道独立命中（TLS 旁路、凭证明文）为最高置信。过程披露与覆盖账见 `docs/audit/2026-10-03-full-audit/README.md`。

**修复**（领导令：修除「分发形态」外全部 P2）：40 条按地界三分（voice 14 / ui 8 / misc 18，跨简报文件冲突程序化验证为 0），三路子智能体各自 worktree 并行 TDD 修复；集成 af-integration 后跨包接线（FUNC-5 flush 单点接入 hideBubbleLater），独立评审无 P1、其「3 条缺口」中 ROB-16/SEC-6 实为证伪有据（ROB-16 经管理者一次性实测仲裁：kotlinx 1.8.1 `CoroutineScope(ctx).cancel()` 不抛 ISE，工厂自动补 Job）。**集成终验 752 绿 / skipped 0 / failures 0**（基线 665，+87 新测试全部先红后绿）。

**沉淀**：①审计「机制类」发现（裸 scope、Double 撕裂等）必须先对依赖版本/ABI 实证再动手——ROB-16 被字节码+实测证伪、CONC-1 的 32 位论据被 arm64 abiFilters 削弱；②kotlinx 1.8.1 `close()` 不解脱满 Channel 挂起的 sender，解挂背压必须 `cancel()`——全仓任何「close 解挂」假设都适用；③并行修复工程的地界切分：按文件归属切 + 程序化冲突验证 + 独立 worktree 隔离 gradle，是三路可真并行的前提。

```evidence
verify_cmd: gradlew.bat --no-daemon testDebugUnitTest --rerun-tasks（基线 665 绿 → 集成 752 绿 skipped=0 failures=0）
verify_claim: 40 条 P2 处置完毕（37 修+2 证伪+1 部分）；独立评审无 P1；禁改项（HttpClients.kt/build.gradle.kts）零 diff
files_touched: [af-integration 分支 65+1 文件（voice/agui/ui/chat/cloud/device/根服务及其测试），主仓 docs/audit/2026-10-03-full-audit/ 全套产物]
```


## 2026-09-24 S68/S68b：弹窗区块参数摘要 + 并行 tool_calls 登记(SSE 取证)

**S68(Q1-B,用户从四方案原型中选定)**:同工具多区块标题全同无法区分。区块头追加 keywordOf(args) 参数摘要——「查询结果 ·「项目档案」」;args 缺失退化为现状标题。mijijia 端 makeBlockHead 单函数改动;8849 冒烟 OCR 验证两区块摘要清晰。

**S68b(Q2 调研,取证实锤)**:测试板复测第二区块仍无摘要。desk-pet 加 SSE 原始行调试日志(TOOL_CALL_START/RESULT 截断 1200 字符),一次复测即取证:**平台把并行工具调用装进同一条 START 的 rawEvent.tool_calls 数组([0]=项目 [1]=合同),顶层 toolCallId 只对应 [0]**——extractArgs 只取 [0],[1] 的 id 从未登记,args=null。另夹 on_tool_end 形态 START(rawEvent 无 tool_calls)会以 null 覆盖登记。修复:START 分支遍历 tool_calls 全元素登记 id→name/args;null 不覆盖已有登记。+2 测试(按取证真实形态构造),665 绿。

**注**:SSE 原始行日志(截断 1200 字符)保留在 runOnce 常驻——量小(每轮 TOOL_CALL 各 1-2 条),对平台形态类排障价值高。

```evidence
verify_cmd: gradlew testDebugUnitTest（665 绿，+2）；测试板真机（17:23 SSE 取证）+ 8849 冒烟 OCR（s68.png）
verify_claim: 并行 tool_calls 每个 id 都登记 args；on_tool_end 不覆盖；区块头摘要区分两区块
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiSseConsumer.kt, app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/test/java/com/xiaoduo/deskpet/agui/AguiSseConsumerTest.kt]
```

## 2026-09-24 S67b/S67c：轮次边界升级——同轮 START 不误杀、回合级 ID 同句追加

**S67b（同轮 START 误杀）**：S67 上线后真机仍报「两次调用只见一条」。板端日志实锤 16:14 轮时序：RESULT#1 弹卡（.863）→ **同轮**第二个工具 START 的 mcpUiSync（.929）触发 S53c dismissExisting 把卡关了 → RESULT#2 新建单区块卡。修复：`mcpUiSync` 体带 runId，密集架端 runId 变化才让位；`onToolCallEvent` 签名扩 (tool, runId) 贯穿四层；+2 测试。

**S67c（回合级 ID）**：用户复测仍只见一条——日志再实锤：平台把复合查询「查A和B」拆成**多个 run**（16:35:29 项目 + 16:35:39 合同，runId 各异且 attempt 递增 `_2`），run 级 ID 把同句第二 run 误判为新对话。修复：`AguiClient.chat/runOnce` 增 `roundId` 参数（调用方生成，`u_<millis>` 每句一次），`onToolCallEvent` 注入值与 `ToolCallOutcome.runId` 改用 roundId（内部 runId 仍留日志）；VoiceSessionManager.voiceChat / ChatViewModel 每回合生成。mijijia 端零改动（字段同名，粒度变粗）。

**验收**：真机「查询项目档案和合同档案」→ 一个弹窗两区块全显（用户确认）；监听日志 `u_1790240529462` 同回合 2×START + 2×RESULT 全链到达。

```evidence
verify_cmd: gradlew testDebugUnitTest（663 绿）；实时 logcat 监听（E:/mjj/s67c_live.log 机制）；双板 install -r
verify_claim: 同句跨 run 结果同窗追加；新句 START 让位旧卡（用户确认关闭逻辑保真）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/cloud/TextChatRouter.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt]
```

## 2026-09-24 S67：mcpUiResult 携带 runId（密集架弹窗同轮追加/去重、跨轮重置的轮次边界）

**背景**：S66 后每条工具结果即时转发，但密集架弹窗「后到替换先到」——同轮两次 MCP 查询只见最后一条。grilling 八问定案：弹窗侧改造（mijijia 仓）+ 本仓仅在 `mcpUiResult` 体加 `runId` 作轮次边界（同 runId 追加/去重，新 runId 整窗重置）。

**实现**：`ToolCallOutcome` 加 `runId`（默认空串，consumer 层不填）；`AguiClient.runOnce` 即时回调处 `copy(runId=)` 注入；`ShelfMcpBridge.resultBodyFor/resultAsync` 加参（空则不带该键，向后兼容旧弹窗）；语音/文字两调用点传 `oc.runId`。+2 测试（661 绿；1 例 ASR 时序 flaky 重跑绿，与改动无接触面）。

**验收**：mijijia APK `e6523ef` 8849 冒烟四连 + 截图 OCR（追加 2 区块/去重/重置全过，见 mijijia 仓 DEV_LOG）；双板已部署 desk-pet `44f5adee` + mijijia `e6523ef`。

```evidence
verify_cmd: gradlew testDebugUnitTest（661 绿，+2）；assembleDebug（APK 44f5adee）；双板 install -r Success
verify_claim: runId 非空进体/空不进体；两调用点（语音/文字）均携带轮次边界
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiSseConsumer.kt, app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/test/java/com/xiaoduo/deskpet/device/ShelfMcpBridgeTest.kt]
```

## 2026-09-24 S66：MCP 结果卡即时弹窗（转发时机从 run 收尾提前到结果到达，「弹窗延迟高」修复）

**用户报障**：生产板喊「查找所有项目档案」，回答调了 MCP 但结果卡弹窗延迟高（实测日志：转发在 `RUN_FINISHED` 后 29ms——不慢，而是 S43 设计上「run 正常收尾后才回吐最后一次工具结果」，弹窗要等首 token 5.3s + 1079 字长回复全部流出后才出现，延迟≈30s）。

**修复（S66，工单 01/02/03 见 `.scratch/s43-early-tool-result/issues/`）**：①消费器 `TOOL_CALL_RESULT` 事件到达即回调 `onToolCallResult`（name/args 经 toolCallId 查 START 期登记，缺失对齐收尾语义：name 空串/args null）；②客户端接线即时回吐，**移除 run 收尾二次回吐**（防双发；即时转发以覆盖式等价实现 S43「多结果轮取最后一次」）。语音/文字两轮同改；密集架桥多次转发呈覆盖式更新（弹窗自然被最后一次结果覆盖）。

**验收（测试板真机）**：「查找所有项目档案」结果卡提前至与 TTS 播报并行出现 ✓；全量 659 绿（+3 消费器即时回调用例）；生产板同包部署。**语义保持**：无结果轮零转发、断流已转发结果不撤回、纯聊天轮不变、工具轮「re-wake required」不变。

```evidence
verify_cmd: gradlew testDebugUnitTest（659 绿，+3）；assembleDebug（APK ad650aca）；双板 install -r Success；测试板真机验收（弹窗与播报并行）
verify_claim: 工具结果到达即弹窗（不再等 run 收尾）；多结果轮覆盖式更新；无工具轮/断流语义不变
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiSseConsumer.kt, app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/test/java/com/xiaoduo/deskpet/agui/AguiSseConsumerTest.kt, docs/DEV_LOG.md]
```


## 2026-09-23 双板部署 S65：生产板 S63→S65 + 两板 mijijia 统一到 xiaoduo 1ef8614

**用户拍板推新包**。测试机 desk-pet 已是 S65（11:07 部署），本次将两板统一到「desk-pet main S65 + mijijia xiaoduo 1ef8614」。

- **测试机 192.168.137.76**：mijijia `install -r` Success（签名同源，签名同源数据自保留）；desk-pet S65 不变。
- **生产板 10.0.14.18**：desk-pet S63→S65 `install -r` Success（同源签名，prefs/数据保留，双 KWS 引擎加载无 FATAL）；mijijia 同事签名包→我们的 debug 包（`fb528b1e`）——`install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同）→ 走「force-stop → 备份三件套+prefs（md5 存档 `E:/mjj/prod_db_backup_0923/`）→ 卸载 → 装 → push+root cp 回灌 → md5 全对 → 起活」流程，全部通过。
- **生产板 mijijia 验证**：8849 起（GetDeviceTime 返回真实时间）；getShelfStatus 有真实柜体数据；MCP 链实锤（`mcpUiSync/mcpUiResult tool=c_archive_query` 双「操作成功」+ handler 日志入参实锤）；ROOM/异常日志干净。

**两坑（写回类运维必读）**：①**adb shell stdin 重定向灌大文件会截断**（15.26MB db 灌成 4096B！小文件也只剩部分）——二进制回灌必须 `adb push`（binary-safe）+ root `cp` + `chown`，不能用 `shell ... < 文件`；②**重装 app 后 uid 会变**（u0_a152→u0_a153）——回灌后 chown 必须用**新** uid（从 databases 目录 owner 读），否则 app 打不开 db。

```evidence
verify_cmd: 双板 install（测试机 -r Success；生产板 desk-pet -r Success + mijijia 卸装流程）；回灌 md5 与备份逐字比对全对（db_induo f348d2f8…+shm+wal+LastingInfo+LocateLight）；生产板 8849 冒烟（GetDeviceTime/getShelfStatus/mcpUiSync/mcpUiResult=c_archive_query 均 200）；mijijia ROOM 错误 0；desk-pet 生产板 2×newFromAsset 无 FATAL
verify_claim: 两板 = desk-pet S65（b2df043c）+ mijijia xiaoduo 1ef8614（fb528b1e）；生产板 mijijia 数据字节级完整（含 WAL 未合并数据）；MCP 联动链在生产板实锤可用
files_touched: [E:/desk-pet-android（部署）, D:/mijijia（部署）, docs/DEV_LOG.md]
```

**遗留**：生产板 mijijia 现为我们的 debug 签名，与同事 keystore 包不兼容——以后若换同事包需再次走卸装回灌流程（或统一用我们构建）。


## 2026-09-23 S65：回声纪元守卫——盲窗回灌滤「我在」回声（「唤醒后听到我在就结束监听」根因修复）

**用户报障**：唤醒后板子应「我在」，随即监听结束（假对话轮抢走聆听窗）。**根因（测试板 10:49 三轮打断重唤醒 3/3 复现 + 代码/pcap 双实锤）**：打断播报场景重唤醒时，S40 盲窗缓冲内是「旧 TTS 残声 + wozai『我在』回声」（maxLevel 0.66-0.99）——wozai 播完后回灌 VAD，此时 NLMS 因参考过期（`now()-lastRefAt > REF_FRESH_MS=400ms` 直通零消除，遥测 `inRms≈outRms` 实锤）回声全额进 VAD → 判出伪 utterance（活块 `vad diag level=0.0007` 全静却 96ms 后报 1.2s 语音）→ ASR 转出「告诉我我在。」系假轮。IDLE 唤醒幸免（盲窗仅唤醒词尾+弱回声，不过判起线）。

**修复（用户拍板方向 1「静音确认后才收」）**：回灌截止线 `cutoff = vadSeamNs - (vadStartDelayMs + ECHO_TAIL_GUARD_MS[150])`——wozai 播放期捕获块（含旧 TTS 残声/回声）整段丢弃，delay 窗尾部迟到块保留（S40 话头找回收窄存续）。实现：`WakeBlindWindowBuffer` 条目携带 capturedAtNs + `drainFrom(cutoff)`；`backfillBlindWindow` 过滤并留痕 `S65 echo-era dropped N`。**推翻 S40 原语义一处**：ack 时代块不再回灌进 VAD（09-12 Case B 注释里「NLMS 兜回声」的假设被证伪，该路径 NLMS 恒过期直通）。

**新增测试 4 个（656 绿，+4）**：S65EchoEraGuardTest（回声纪元块丢弃不产伪轮 / delay 窗尾部迟到块仍回灌，变异验证：删过滤主用例红）；WakeBlindWindowBufferTest +drainFrom 边界 2 例；S40 用例 1/2 按新语义重写。

**顺带排障大发现（详见 solutions/workflow-issues/platform-sse-zero-chars-server-side-diagnosis.md）**：排障期间板上 run 请求大面积断流（`SSE 断流 produced 0 chars`，HTTP 200+控制事件后 ~2.2s 关流）**定案为平台 chatAgent 故障**——切换 Agent 即恢复；板端代码全程无罪（PC 同期同 token 直连成功、聊天 UI 同源同断、S65 改动不碰网络栈）。**取证明细与事故教训**（adb 写 prefs 必须读回校验；MSYS 路径转换会把设备路径转成 `C:/Program Files/Git/...`）见同日 solutions 两条。

```evidence
verify_cmd: gradlew testDebugUnitTest（656 绿，+4：S65 2 例 + buffer drainFrom 2 例）；assembleDebug（APK b2df043c）；测试机 192.168.137.76 install -r Success
verify_claim: 打断播报重唤醒后「我在」回声不再产出伪 utterance（S51 hold 不出现、状态保持 LISTENING）；delay 窗尾部迟到块回灌保留；IDLE 唤醒/按住路径零行为变更
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/WakeBlindWindowBuffer.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS65EchoEraGuardTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS40BlindWindowTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/WakeBlindWindowBufferTest.kt, implementation-notes.html]
```

**待真机验收**：测试板 prefs 恢复（password + 三模态 platform）后喊话复测「打断播报重唤醒→我在→说真指令」。


## 2026-09-23 S64：禁用打断词组（好了/停下，keywords.txt 删行，仅测试机）

**用户拍板**：禁用打断词。**处置（沿 S63 先例）**：keywords.txt 删 `h ǎo l e @0.5 @好了` 与 `t íng x ià @停下` 两行（剩 3 行纯唤醒组）；`INTERRUPT_WORDS` 保留为防御性墓碑（路由语义照旧，引擎不再识别）。**打断能力未消失**：唤醒组（小朵小朵/你好小朵/小度小度）在 SPEAKING/PROCESSING 命中即打断（S12 链路），双击打断亦在——用户要打断播报说唤醒词即可。复活流程：加回 keywords.txt 行 + 拍板。

**测试锚零改动**（路由行为未变，652 绿）。**部署**：遵守「编译后不上生产机」约束，仅测试机（APK `8e5ce3b0`）；生产板仍跑 S63 版（含好了@0.5/停下）。**挂账**：mijijia tips⑧ 文案「让我停：说「小朵小朵」或「停下」」中「停下」表述已过时（需产品确认新文案，随下次 mijijia 刷包同步）。

```evidence
verify_cmd: gradlew testDebugUnitTest（652 绿）；assembleDebug（8e5ce3b0，keywords.txt 3 行）；测试机 install -r Success + capture start + 0 FATAL
verify_claim: 好了/停下不再被 KWS 识别；唤醒词打断（S12）与双击打断不变；唤醒组三词不受影响
files_touched: [app/src/main/assets/kws-model/keywords.txt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S63：禁用「停」打断词（keywords.txt 删行，SPIKE_WORDS 留墓碑，双板已更）

**用户拍板**：禁用「停」。**处置**：keywords.txt 删 `t íng @0.5 @停` 行（引擎不再识别，剩 5 词）；`SPIKE_WORDS` 保留为防御性墓碑（若日后再加回引擎行，路由仍恒 DROP 待转正）；VoiceStateRouter/VoiceForegroundService 两处 KDoc 记录禁用原因（单音节误报体质，噪声/连续语流易越线——与 S61「好了」误触发同族，单字更甚）。复活/转正流程不变：真机双合格 + 用户拍板。

**未动**：停下/好了打断语义不变；测试锚零改动（SPIKE_WORDS 路由行为未变，652 绿）。次生观察：本番 assembleDebug 首跑静默失败（tail -1 吞掉 FAILED，sha 未变暴露）——概率性 BindException 家族，改用 grep BUILD 过滤可见化。

```evidence
verify_cmd: gradlew testDebugUnitTest（652 绿）；assembleDebug（c6ec4721，keywords.txt 5 行）；双板 install -r Success + capture start + 0 FATAL
verify_claim: 「停」不再被 KWS 识别（keywords.txt 无此行）；SPIKE_WORDS 墓碑保留（decide("停")=恒 DROP，防御）；打断组（好了@0.5/停下）与唤醒组不变
files_touched: [app/src/main/assets/kws-model/keywords.txt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S62：跳转页面增加「首页」（语音/文字导航回主界面，双板已更）

**需求**：跳转页面集合（借阅/归还/盘点）增加「首页」。**链路**：ShelfNavIntentMatcher += HOME_WORDS（首页/主页/主界面/主页面）→ page="home" → ShelfMcpBridge.NAVIGATE_PAGES += home → `{"Op":"mcpUiNavigate","page":"home"}` → 密集架 AppController 接受 home（盘点页前台先收起）→ `searchPageRequest` → MainActivity `popBackStack(mainFragment)` 回主界面（已在主界面幂等 no-op）。

**保守策略（home 专属）**：「首页」自身含页面名词「页」——若走页面名词兜底，「首页有什么」「主界面显示的内容」类话题会被导航劫持。**home 必须带导航动词**（打开/去/返回/回到/进入/切换）；NAV_VERBS += 返回/回到；裸「回」不入列（「回答」含「回」，hasNavVerb 会平凡为真）。执行对象词闸（档案/文件…）仍先于一切：「打开首页查档案」不劫持。

**文案**：回执共用函数 `navSpeech(page)`——home=「已回到首页」，其余=「已打开X页面」（既有语义不变，语音/文字两入口同源）。

**测试**：+3 锚（home 命中 5 形态 / 裸词与话题不劫持+对象词闸 / navSpeech 文案+既有语义不变）。**652 绿**。密集架端见 mijijia DEV_LOG 同票据条目。

```evidence
verify_cmd: gradlew testDebugUnitTest（652 绿 = 649 + 3 S62）；assembleDebug（3ad856a2）；双板 install -r Success + capture start + 0 FATAL
verify_claim: 「打开首页/返回首页/回到主界面」等 → page=home 经 mcpUiNavigate 回主界面；裸「首页」/话题形态/对象词语句不劫持；既有 borrow/return/inventory 语义不变
files_touched: [app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt, app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt, app/src/test/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcherTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S61：「好了」误打断杀死回答（阈值 0.3→0.5，双板已更）

**用户报障**：问「查询其他档案」没等到回答，反而听到「我在」。**21:19 logcat 取证**：ASR「查询其他档案。」→ PROCESSING 21 秒（LLM+工具）→ **21:19:50.888 shadow 好了（thr=0.15）→ 21:19:51.064 真命中「好了」→ barge-in cancel → 回答胎死腹中 → 播「我在」**。用户全程未说「好了」= 环境噪声误触发（21:17:41 还有一次 shadow 好了 未遂）。「好了」双音节音素常见，全局阈值 0.3（08-10 锚）在 0.19 噪声域 + 21s 长窗下被击穿——**S54 Q7 判废标准「负样本误动作>0 即调」首次实战触发**。

**处置（S61）**：keywords.txt `h ǎo l e @0.5 @好了`（0.3→0.5，与 spike 词「停」同级）。「停下」今晚零误报不动。真实喊「好了」清亮发音得分远高于 0.5（spike 词同档已验证可用），边际噪声伪触发集中在 0.3~0.45 域被滤除。**待复核**：若用户正常音量说「好了」出现漏打断，下调至 0.4 再找平衡。

**次生观察（挂账）**：误打断后「我在」播完仍出现一次幽灵「我在。」（21:19:52，maxLevel=1.0）——S59 的 400ms 衰减窗对本板 AudioTrack 排水深度可能不足（21:19:26 那次唤醒窗口正常）。证据不足不改参数，下次复现再量。

```evidence
verify_cmd: gradlew testDebugUnitTest（649 绿，keywords.txt 非断言对象）；assembleDebug（c9851565）；双板 install -r Success + capture start + 0 FATAL；21:19 现场 logcat 好了误触发链取证
verify_claim: 「好了」KWS 触发阈值 0.3→0.5（单行覆盖，其余五词不变）；路由/打断语义不变
files_touched: [app/src/main/assets/kws-model/keywords.txt, docs/DEV_LOG.md]
```



## 2026-09-21 S60：判停倒计时尾垫进 PCM（「最后几个字没检测到」根因修复，双板已更）

**用户报障**：聆听正常、无提前判停，但句尾几字丢失（「其他类型档~~案~~」「所有~~档案~~」）。**20:51 logcat 取证**：句长 3.1~3.6s 远未触 8s 顶——非强截；2/3 轮缺尾。

**根因（一行定案）**：VadDetector.feed 说话中分支 `buffer.add(block)` 仅在 isSpeech 时执行——**判停倒计时的 6 块（600ms）不进 PCM**（注释自称"同桌面端"）。安静域无害（end=0.03，句尾弱音节在线上）；S56/S58 起 end 线随噪声底上浮（0.19 环境 ≈0.27），句尾 0.1~0.25 弱音节被判"静音"且**音频物理丢弃** → ASR 缺尾。**S50 的病（弱音节掉字）从自适应门回归**——S50 当年修法是 end 线拆分 0.03，今晚被噪声顶高击穿。

**修复（S60）**：倒计时块也 `buffer.add`（尾垫 ≤600ms）。判停阈值/600ms 窗/噪声免疫一个不动；代价=每句多带 ≤600ms 尾部环境音（ASR 对尾静音天然容忍）。与桌面端分叉点，有意为之。**git 历史脉络**：S50 end 线拆分（7字→8字）→ S51 续听窗合并气口 → S52 取证「切尾=气口位置」+ 窗回调 6 → 今晚 S60 尾垫（end 线高低不再影响尾巴采集）。

**测试**：+1 语义锁（弱音节尾 100 进 PCM 末样本，旧行为末样本是 1000）；7 锚随拍板改（VadDetectorTest 3 处尺寸 3→9/8→14、pre-roll 用例改名、S51ContinuationTest 4 处 samples 8000→17600）。**649 绿**。

```evidence
verify_cmd: gradlew testDebugUnitTest（649 绿 = 648 + 1 S60）；assembleDebug（b6b50b7b）；双板 install -r Success + capture start + 0 FATAL；20:51 现场 logcat 缺尾三轮取证
verify_claim: 判停倒计时块（≤600ms，含句尾弱音节）进入派发 PCM；判停/噪声免疫/8s 顶语义不变（7 处锚同步 S60 尺寸）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S59：TTS 尾音衰减窗（「唤醒后说了没听到」根因修复，双板已更）

**用户报障**：唤醒后说「帮我查询2013年的档案」无响应无对话。**20:18 logcat 取证**：wozai「我在」播完 VAD 即刻启动，喇叭余音（满幅 1.0）被当成语音——**0.68s 派发幽灵句「我在。」** → 状态进 PROCESSING 不再听 → 用户真实指令落在空窗全丢 → 幽灵句判纯唤醒词回 IDLE；20:18:57 二次唤醒原样重演。S58b 自回声丢弃未拦住：「我在」是应答音非 AG-UI 回复，不在对照文本。

**修复（S59 时序根治）**：`vadStartDelayMs=400`（@Volatile internal，测试可置 0）——TTS 播完延迟 400ms 再 vad.start()，两处接线：①wozai 播完（守卫在延迟后重新执行，cancel 落窗内不启动，不放大 check-then-act）；②multi-turn TTS 播完（延迟后复查 currentTurn/state/sealed 三守卫，CancellationException 走原 catch 拆解）。S58b 文本判定保留作第二道兜底的网。

**测试**：+2 锁（衰减窗内保持 SPEAKING 不迁 LISTENING / 窗内 cancel 无迟到 LISTENING）；S33/S40 盲区测试置 `vadStartDelayMs=0`（测盲区机制与衰减窗无关，保原时序）。**648 绿**。

```evidence
verify_cmd: gradlew testDebugUnitTest（648 绿 = 646 + 2 S59）；assembleDebug（299a6930）；双板 install -r Success + capture start + 0 FATAL；20:18 现场 logcat 幽灵「我在。」链取证
verify_claim: TTS（wozai/回复）播完 400ms 后才启动 VAD——尾音不再被捕获成幽灵句，用户紧随其后说的真实指令落在有效聆听窗内；cancel/封轮守卫语义不变
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS33BlindSpotTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS40BlindWindowTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S58b：TTS 尾音自噬丢弃（「只监听一小会」根因修复，双板已更）

**现象与取证**（20:04~20:07 logcat）：S58 上线后出现新症状「只监听一小会」——multi-turn 回答完毕 VAD 重启聆听的瞬间，**喇叭余音未衰减**被麦克风捕获（diag 1.55/2.22），0.24s 即判起成句，ASR 转出 **「或档案管理帮助吗？」= 刚播回复的尾巴**（自回声）→ 幽灵轮送 LLM → 吃掉聆听窗口 + 空耗一轮 LLM，用户双击打断收场。

**修复（S58b，两层）**：①纯函数 `isSelfEcho(newText, lastReply)`——新转写去标点后 ≥4 有效字符且为上一轮回复（去标点）子串 → 判自回声；②VSM ASR 文本处接线：判定命中 → 不送 LLM，静默重回聆听（对话保持，S19-2 额度不受影响——复位行位于插入点之后未执行）。AG-UI done 处留档 `lastSpokenReplyText` 供对照。误杀边界：用户复述上一轮回答中 ≥4 字短语会被吞（罕见，日志可见，接受）。

**验证**：646 绿（+4 S58b 锁：尾巴命中/去标点/新指令不误杀/短文本与无历史）。双板 install Success（APK 前 16 `c4a66017`）。

**杂项**：①Gradle BindException 呈**概率性**（同命令忽过忽败，项目级 preferIPv4Stack 在位）——Clash TUN 干扰疑云升级为「概率性干扰」，重试即过，持续观察；②ee80b27/9e82c03 回退实验 APK 与 worktree（E:/desk-pet-ee80b27）保留备查；③生产板 20:01 已同步 S58+b 包。

```evidence
verify_cmd: gradlew testDebugUnitTest（646 绿 = 642 + 4 S58b）；assembleDebug（c4a66017）；双板 install -r Success + source=7 + 0 FATAL；20:07 现场 logcat 自回声链取证
verify_claim: 自回声（回复尾巴转写）不再送 LLM，静默重回聆听；S19-2 额度与真指令路径不变（新指令不误杀锁）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerSelfEchoTest.kt(新), docs/DEV_LOG.md]
```



## 2026-09-21 S58：上攻通道复活（回退实验定案 + 用户拍板），全功能版恢复可用判停

**回退二分实验**（用户实测 + logcat 对账）：①ee80b27（S52 收口态：判停 600ms + 续听窗 1000ms 原版 + 上攻停用）→ 今晚噪声下 **S51 死锁原样复现**（parts=2/265600 样本滞留零派发）→ **S54~S56 代码全部排除**，变量=环境（底噪 09-18 0.005 → 今 0.19）；②**9e82c03**（S50+S51 重构**之前**：无续听窗、判停线跟校准联动、**上攻通道开启**）→ 用户实测「判停正常」，logcat 四轮全自然成句（41600/11200/44800/48000 样本，无一条 8s 强截），长句「借阅2025年的合同档案」档案名完整、ASR 全清。

**定案**：S19-1 上攻通道（稳态噪声 plateau/half-peak 快速抬 floor）在当前环境**工作正常且必要**——S52 当年停用它是因持续元音误伤（长元音与稳态噪声同型），但实测今晚长句未触发误伤，判停可用性优先。**S58 动作**：`NOISE_ATTACK_ENABLED` false→true（KDoc 保留 S52 全部误伤知识 + 回退实验数据；若长句切尾再现，重审判据——建议方向：空 ASR 裁判门控 + 上攻限速）。锚随拍板改：4 个 S52 测试反转语义为「上攻滤除」锁（ pumped/decay/ghost/sustained-tone，S52 教训全文留档 git 历史）。642 绿。

**双板部署**：APK `9155a888`（= HEAD 1723d7b+S58），测试板 source=7 双引擎 0 异常，生产板同步。**版本矩阵留档**：ee80b27=`35b0375b`、9e82c03=`a7987505`（E:/desk-pet-ee80b27 worktree 可复现）。

```evidence
verify_cmd: gradlew testDebugUnitTest（642 绿，4 锚随拍板反转）；assembleDebug（9155a888）；双板 install -r Success + source=7 + 0 FATAL；9e82c03 现场四轮 ASR text 全清（今晚 logcat）
verify_claim: 上攻通道复活后全功能版（多唤醒词+全部加固）在 0.19 噪声环境判停正常；S52 误伤风险明示接受（长句实测未复现，再现时按 KDoc 方向重审）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S55/S56 追加实验（夜）：采麦模式复位无效 + 重启无效 → 收敛至物理声源；采麦源设置锁定（S57）

**三连排除实验**（137.45）：①mode 1→0 复位（source=7 实证）后 diag 仍 0.19；②板子整机重启（uptime 122h→0，IP 变 137.76）后 diag 仍 0.19（kws stats fed=511/gated=10 与重启前同）；③modes 0/1 两路径同电平。→ **采麦模式漂移与 HAL/codec 状态爬升均排除**，0.19 为麦克风真实拾取的持续声（能量≈09-18 底噪的 1500 倍 ≈ 31dB）。S52 曾记录「新包装板」——包装/麦克风孔/风扇物理状态为剩余嫌疑。

**待用户物理排查**（耳朵+手，10 分钟）：①耳朵贴板：有无风扇嗡嗡/电流声；②摸外壳：温度/振动；③看麦克风孔：有无遮挡/摩擦/贴膜起边；④把板子挪到另一房间再喊唤醒（我方立即抓 diag 对照）——挪开回落=原位置有噪声源，挪开仍响=板载硬件（风扇/麦克风）。

**S57（随本条同包）**：设置页「采麦源」选择器锁定为只读展示（TextButtonRow→Text），漂移通道关闭；变更须 adb run-as sed prefs+重启。同包含项目级 gradle.properties preferIPv4Stack（守护进程必需，用户级会被项目级 jvmargs 覆盖的教训入册：同键双文件时以项目级为准——本机两次 BindException 复盘修正）。热区实测 soc 56°C（正常）。

**现状可用性**：S56 自适应生效后，0.19 噪声房内**大音量**说完 ~1s 判停可回答（判起线自适应至 ≈0.38）；正常音量被底噪物理掩蔽——噪声源消除前无解，消除后 S56 自适应自动把线降回 0.03 基线恢复灵敏。

```evidence
verify_cmd: 复位+重启后 diag 仍 0.19（19:14 mode0 / 19:31 mode0 重启后 stats fed=511 gated=10）；tinycap root 实测失败（HAL 权限/占用，待复核）；设置页采麦源已改只读（TextButtonRow→Text）；双板 install Success（be9e094d）
verify_claim: 三连排除（模式/重启/双路径同电平）→ 物理声源收敛；S57 锁定上线；静默期 floor 自适应护甲保留
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt, gradle.properties, docs/DEV_LOG.md]
```



## 2026-09-21 S55/S56 真根因追加定案：采麦模式漂移（mic_source_mode 1/2，已双板复位 mode 0）

用户一句「9月18日没这个问题」触发对照：DEV_LOG 09-18 S52 表格 diag 噪底 **0.004~0.008** vs 今日 **0.19**（同板同指标差 30~50 倍）→ 代码音频链零改动（S54 词表/S55 续听/S56 floor 全不碰采集增益）→ 查 prefs 石锤：**测试板 mic_source_mode=1（VOICE_RECOGNITION）**、**生产板 =2（UNPROCESSED 裸麦）**，而 09-18 S52 收口实证现役=**mode 0（VOICE_COMMUNICATION，硬件语音路径）**。映射（AudioCapture.kt：0→通话/1→识别/2→原始）；设置界面 SettingsScreen 有该选择器（:941）——漂移路径=设置页人工拨动（何时/何人待用户回忆），生产板另有 09-17 16:41 远端同事重装（prefs 全新，可能当时即非 0）。

mode 1/2 与 mode 0 是**不同模拟增益拓扑**：raw/识别路径增益高 → 底噪 0.19 + 说话削顶 1.0 → 判停线 0.03 形同虚设 → 连锁出 hold 死锁（S55）与 floor 失学（S56 缺口）。**复位动作**：双板 force-stop → run-as sed → relaunch → `capture start, source=7` 双板实证。S55/S56 的加固（冻结短路、ASR 裁判式 floor）在 mode 0 安静域自动休眠不触发，作为护甲保留。

**教训入册**：①「环境噪声」结论必须先排除**配置漂移**——同一个 0.19，先查 pref 再怪房间；②diag 噪底是最佳回归哨兵（与 09-18 表格数据对照 5 分钟定位），此类「设置页可改的音频路径参数」应入对账表巡检项。

```evidence
verify_cmd: run-as grep mic_source_mode（复位前 1/2 → 复位后 0/0）；logcat capture start source=7 双板实证；09-18 DEV_LOG diag 0.004~0.008 vs 今日 0.19 对照
verify_claim: 采麦模式漂移为判停退化的第一因（mode 决定增益拓扑）；S56 自适应在 mode 0 安静域自动休眠；待用户喊话确认 diag 回落 0.005~0.01
files_touched: [docs/DEV_LOG.md]
```



## 2026-09-21 S56：判停线自适应落地——ASR 裁判式噪声底上行（双板已更，待喊话验收）

**设计缺口**：S19-1 v5 自适应底本有双通道，但上行通道（噪声上攻）被 S52 停用（误伤持续元音/衰减尾音）后，floor 只能向下/低位跟随——环境噪声阶跃到阈值之上（实测 0.19 vs 判停线 0.03）时 floor 永远学不到，判停恒失效拖满 8s（用户实测「说完 ~5s 才动」= 8s 窗减去说话时长）。

**S56 机制（语义裁判，误伤免疫）**：空 ASR 轮 = ASR 认证「收集到的不是话」——`VadDetector.raiseNoiseFloorFromLastUtterance()`（VSM S19-2 空 ASR 分支调用）以 0.6 速率把 floor 向该轮整段 RMS 收敛（封顶 0.5）；**有文本轮绝不推高 floor**（真语音免疫 S52 式误伤）。收敛动力学（单测锁死）：1 个幻觉轮反馈 → floor≈0.116 → 判起线 floor×2=0.232 > 噪声 0.19 → **幻觉轮绝迹** → 慢速跟踪（NOISE_TRACK_RATE）在聆听期接管（噪声块落回阈值下方可更新）→ floor→0.19、end=max(0.03,×1.4)≈0.25>噪底 → **判停复活（600ms 噪声尾即成句）**，判起=max(0.05,×2)≈0.38 过滤噪声。

**物理极限（明示）**：底噪 0.19 内正常音量（峰值 0.1~0.3）SNR 不足，判起线 0.38 意味着**需较大声说话**；根治=消除噪声源（板旁风扇/空调排查，用户侧）。

**验收预期**：首轮唤醒可能仍有一轮 8s+「没听清」（自适应轮），第二遍起：较大声说完 → ~1s 内判停 → ~2s 回答。若持续「没听清」且声嘶力竭 → 噪声源必须物理处置。

```evidence
verify_cmd: gradlew testDebugUnitTest（642 绿 = 639 + 3 S56 锁：floor 上行/幻觉轮过滤/适应后判停复活/no-op+语音中性）；assembleDebug（22b6522c）；双板 install -r Success + 2×newFromAsset 0 FATAL
verify_claim: floor 上行仅由空 ASR 轮触发（真语音中性）；适应后判停 600ms 恢复且幻觉轮被滤；0 直写陷阱/锚错位教训已规避（行号手术）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, gradle.properties, docs/DEV_LOG.md]
```



## 2026-09-21 S55 取证收口：链路端到端复活 + 噪声源定案（真实环境噪声，非 AGC）

**10 秒实验结果**（18:25~18:26 logcat）：用户唤醒后静默期 vad diag 恒定 **0.185~0.21**——app 刚重启（AGC 已重置）仍不衰减 → **排除 AGC 增益爬升，定案真实环境噪声**（稳态宽频特征，疑似板载风扇/空调/设备声，位置待用户排查）。

**修复实效端到端实证**：`wake → 判停(8s 截断) → dispatch(100ms 内) → ASR text「今天的天气怎么样？」「你能做哪些事情？」→ LLM（firstToken=1810ms）→ TTS 播报（143280 frames）→ 多轮续听 → 下一轮 dispatch→PROCESSING→SPEAKING` 全循环正常转——「说完不回答」问题**解决**。

**遗留（量化）**：判停线 0.03 在 0.19 噪底下失效 → 每轮最长 8s 才截断派发（延迟）+ 截断位置任意（ASR 出现「寻到是」类碎片文本）。**S56 候选（正解）**：判停线自适应——滚动噪底跟踪，end = max(0.03, 噪底×k)，恢复快自然判停；小票体量。临时缓解：排查板子附近噪声源（风扇/空调）；或接受 8s 节奏使用。

```evidence
verify_cmd: 137.45 logcat 18:25~18:26：vad diag 静默期 0.185~0.21 恒定；ASR text×2 正常；AG-UI done reply 30 chars firstToken=1810ms；VoicePlayer stream 143280 frames；多轮 LISTENING→dispatch→PROCESSING→SPEAKING 循环
verify_claim: 派发-ASR-LLM-TTS-续听全链复活；噪声源=真实环境（AGC 排除：重启后不衰减）；判停失效遗留已量化并立项候选
files_touched: [docs/DEV_LOG.md]
```



## 2026-09-21 S55 第二刀：hold 冻结死锁根因石锤 + 短路修复（双板已更）

**取证**（137.45 重测现场 logcat）：①每条 utterance 恒 132800 样本=8s MAX_UTTERANCE 强截断，maxLevel 1.0000 削顶——判停（600ms<0.03）**零触发**，环境电平 0.19~0.29=判停线的 7~10 倍；②`parts=1→8` 无限累积（66s 音频全滞留 hold，零派发）——即便第一刀把窗关到 1，`tickContinuation` 冻结条件（块 ≥0.03 或 isSpeaking）在噪声下永不解除，派发通道仍死锁。校准对照：kws_energy_gate=0.0046（校准时刻安静）+ 重启后 60s stats fed=512/gated=10（当前全程高于校准底）——环境电平暴涨 40 倍（真实噪声 or codec AGC 增益爬升，S18 老病史，**待分辨**：重启 app 后立即唤醒看 vad diag 是否回落）。

**修复**：`tickContinuation` 冻结条件加 `CONTINUATION_TICKS > 1` 门——窗=1（暂关续听）时无条件倒数，判停后下一块**必派发**，与噪声电平无关；窗>1 恢复时冻结语义整体回归。锚随拍板改：用例 3（轻声冻结合并）重写为「合并全关」锁 + 新增噪声回归锁（0.25 响块后必派发），639 绿。

**双板部署**：install -r Success（APK 前 16 `d433b9ac`），双引擎加载 0 异常，app 已重启加载新包。

**遗留分级**：①判停本身在此噪声下仍死（每句 8s 大块+削顶音频，ASR 质量与延迟受损）——正解=**判停线自适应**（滚动噪底跟踪，end=max(0.03, 噪底×k)）或 AGC 限幅（大票 A 复活候选），下一票；②环境电平 40 倍暴涨归因（AGC 爬升 vs 真噪声）待用户配合 10 秒实验（重启后立即唤醒+静默，看 vad diag 回落与否）；③生产板 10.0.14.18 今晚 logcat 无语音活动痕迹（用户重测或在 137.45）。

```evidence
verify_cmd: log-137.txt：VAD utterance 132800 samples×8 + hold parts=1→8 + vad diag level=0.19~0.29；修复后 gradlew testDebugUnitTest（639 绿）+ assembleDebug（d433b9ac）+ 双板 install -r Success + 2×newFromAsset 0 FATAL
verify_claim: hold 派发通道在任意电平下必通（噪声回归锁锁死）；判停 0 触发系环境电平超线 7~10 倍（非代码回归）；638→639 测试（+1 噪声锁，用例 3 重写）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S55 第一刀（用户拍板）：续听窗 10→1 双板暂关（排查「说完不回答、持续聆听」）

**现象**：用户实测说完话后 app 持续「聆听中…」不回答。**嫌疑定位**（代码走查，未取证即先行处置——用户拍板优先）：`tickContinuation` 冻结条件 = 单块 ≥end 线(0.03) 或 `vad.isSpeaking` 即冻结倒计时；噪声环境下永不解除，且 `holdForContinuation` 内 `vad.start()` **重置 30s 聆听超时** → 无限挂起（30s 兜底也失效，与「一直监听」吻合）。

**处置**：`CONTINUATION_TICKS` 10→1（判停 600ms + 100ms 即派发，≈S50 前行为）。**关键陷阱**：不可设 0——`holdForContinuation` 只攒片，派发全靠倒计时耗尽，0=永不派发=语音哑火。代价：短语气口合并（S51 主价值）暂失，「查询一下…（停>0.7s）…2025年的合同」会被拆两轮；轻声续说（0.03~0.05 频段）因冻结线语义仍可合并（用例 3 不变仍绿）。锚随拍板改：S51ContinuationTest 用例 2 重写为关窗语义锁（KDoc 注明恢复窗口时还原，原版见 git 历史），638 绿不变。

**双板部署**：137.45 + 10.0.14.18 install -r Success，双引擎加载 0 异常（APK sha256 前 16 `edeee8d6`）。

**待办**：①重测——若症状消失=冻结假设成立，正解候选=**有界续听**（冻结也有总上限 ~2.5s，保住合并语义又杜绝无限挂起）；②若仍不回答→抓 logcat 分流（empty ASR 续听 / 平台 LLM 挂起 / 判停线被噪声顶穿），与续听窗无关；③生产板环境噪声 vs 判停线 0.03 需 AmbientCalibrator 复核。

```evidence
verify_cmd: gradlew testDebugUnitTest（638 绿，用例 2 已随拍板重写）；assembleDebug（edeee8d6）；双板 install -r Success + logcat 2×newFromAsset 0 FATAL
verify_claim: 双板续听窗=1 块生效；判停即派发（+100ms）；hold 零派发陷阱已规避（0 未使用）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S54 多唤醒词+打断词（grilling 三轮拍板全落地）+ S55 立项（快语速漏唤醒取证）

**S54 内容**：KWS 词表 1→6 行——新增唤醒词「你好小朵」「小度小度」（Q1=①+②）、打断词「好了」「停下」、「停」为 spike 观测词（Q7）。命中后按词组路由：`VoiceStateRouter.decide(keyword, state)`——唤醒组全态动作；打断组仅 SPEAKING/PROCESSING 动作（Q5=b），**IDLE/LISTENING 命中丢弃**（Q6=a，防闲聊「好了」误唤醒）；「停」恒 DROP 只留日志，真机双合格（播报中真喊 ≥8/10 且播放期误打断 0；负样本误动作>0 判废）后挪 INTERRUPT_WORDS 转正。打断语义=打断并续听（Q3=b），与 S12 唤醒词打断完全同构，动作层零分叉。未登记词默认 ACT（词表/组表漂移不静默失效）。ASR 剥除表 WAKE_PREFIXES 补「你好小朵」与声学行为对称（原「非前缀不剥」用例中 你好小朵 断言随拍板删除）。六词拼音 token 逐一核验在表（含轻声 e）；keywords.txt「停」行带独立阈值 `@0.5`。

**机制备案**：sherpa-onnx KeywordSpotter 原生多词每行一个+回调带词名+每词阈值；打断组 IDLE 丢弃是唯一防线（sherpa 无按态切换词表能力，改词表=重建引擎）；「你好小朵」兼唤醒/打断是语义 no-op（唤醒词在 S12 通道下本来就打断续听），词表只写一次。

**坑与修复**：① enum `KwsHitAction` 定义在 companion object 内时跨文件 `VoiceStateRouter.KwsHitAction` 引用 Unresolved——挪类级嵌套解决；首版修复错插在 companion 花括号内（缩进误导，Kotlin 只看大括号不看缩进），clean+关缓存后仍复现才定位真因。② 本机 Gradle 全挂 `BindException: Address already in use`（FileLockCommunicator 随机 UDP）：最小复现=Java 经典 `DatagramSocket()` 通配绑定 0/30 全败，显式 loopback/NIO DatagramChannel/Python 均全通（疑 Clash TUN 驱动干扰双栈通配路径，**待复核**）——`-Djava.net.preferIPv4Stack=true` 救活（三个候选开关 preferIPv4Stack/preferIPv6Addresses=true/system 各 5/5 实测），写入用户级 `~/.gradle/gradle.properties` 不污染仓库。

**板端**：192.168.137.45:5555 install Success（app-debug.apk sha256 前 16 位 91efafccd7d63eab）；主引擎加载成功后影子引擎才启动加载（代码时序保证），两次 newFromAsset 无 FATAL；KwsEngine gap-reset 周期行=消费线程活跃。`@0.5` 语法初始化未炸；**细粒度行为（0.5 档命中率）随 spike 日志复核**。

**S55 立项（快语速漏唤醒，不并入 S54）**：现象=语速快时「小朵小朵」概率性唤不醒。三假设与判据：H1 阈值/韵律——影子通道(0.15)命中而主(0.3)不中=实锤，S54 soak 顺带采集差分数据（新唤醒词一并攒料）；H2 门削首音——影子也不中+探针构建见词首块被 RMS 门吞（S11-6 机制残余），坐实才临时 re-arm 探针（S52 先例用完收回）；H3 解码积压——kws stats fed/gated 断档。取证口径：用户 20 次快语速+20 次常速对照喊话+logcat 留痕。对症菜单（影子先行证据后动主通道）：每词阈值↓ / keywordsScore 1.0→1.3 / maxActivePaths 4→8；**拒绝项**：换/重训模型（成本不成比例）、关能量门（功耗+误触）。权衡拍板（Q11）：证据先行，误唤醒比漏唤醒更伤日常。

```evidence
verify_cmd: ./gradlew testDebugUnitTest（638 绿 = 633 基线 + 5 新增）；assembleDebug（apk sha256 前缀 91efafccd7d63eab）；adb install -r 192.168.137.45:5555 Success；logcat：双 newFromAsset 无 FATAL + KwsEngine gap-reset 活跃行
verify_claim: 六词词表+词组路由+剥除表落地，板端双引擎加载运行中；「停」spike 恒 DROP 不接动作；待用户喊话验收（唤醒组 IDLE ≥10/10；打断组播报中真喊 ≥8/10 且 IDLE 闲聊含好了/停 0 误唤醒；「停」spike 数据另行采集）
files_touched: [app/src/main/assets/kws-model/keywords.txt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceStateRouterTest.kt, docs/DEV_LOG.md, implementation-notes.html]
```


## 2026-09-18 两张小票：探针诊断构建回退 + mic_source_mode 对照复测（S52 后收尾）

**票 1：探针回退**（S52 遗留 hack 收回）：`AudioCapture.probeBlock` 门限 `shouldLog(rms, 0f)`（armed 全量 ~10 行/s）→ 回退 `shouldLog(rms, EnergyGate.DEFAULT_GATE)`，KDoc 注明 S52 历史（为何曾全量：AGC 压制取证需看 0.001~0.015 float 域低值段；再需时临时改 0f 随取证票收回）。`shouldLog` 语义已有 AudioProbeTest 5 锁，调用点回归无新行为不需新测，633 绿不退。

**票 2：mode 0/2 对照复测**（S52 停用上攻后首次同条件对照，一轮一变量）：新包装板 → 轮 A（mode 0/source=7）3 轮 → force-stop+sed prefs+relaunch 切 mode 2（source=9，capture start 实证）→ 轮 B 同脚本 3 轮 → 切回 mode 0（source=7 实证）。

| 维度 | 轮 A mode 0 | 轮 B mode 2 |
|---|---|---|
| 唤醒 | 3/3 | 3/3（含聆听期重唤醒 1 次） |
| maxLevel | 0.63/0.33/0.29 | 0.32/0.39/0.30 |
| ASR 完整性 | 3/3 完整 | 3/3（第 3 轮少「合同」系用户口误，非切字） |
| S51 链 | hold→dispatch 3/3（32000/35200/51200 样本） | 3/3（33600/38400/48000 样本） |
| 幽灵轮/切尾/拖尾 | 0/0/0 | 0/0/0（S40 backfill 每轮 7 块正常，**无硬件 AEC 下「我在」回声未构成回灌**） |
| 判停尾段 | diag 落底 0.004~0.007 | 同 0.005~0.008 |
| 噪声底（VAD 域 diag） | 0.0045~0.0076 | 同量级（电子噪底 0.0013 float×4.096≈0.005 吻合） |

**结论**：S52 后 mode 0/2 在判停质量/回声/ASR 完整性上**无实质差异**（n=3，同脚本同音量）；曾经 S18 时代的 AGC 钉死问题在两模式下均未复现（用户现中等音量 + end 线 0.03 + 上攻停用三重缓解）。**维持 mode 0 现役**（mode 2 无优势，mode 0 硬件 AEC 在真回声场景仍有保险价值且为出厂默认）。顺带：用户确认悬浮窗已自行开启（prefs 无 false 条目=默认 true，relaunch 后 `PetOverlayService pref=true` 双窗口实证）。轮 B 第 2 轮未走 MCP 工具、第 3 轮 empty run retry×1——均平台 LLM 侧行为，与采麦模式无关，不入票。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（633 绿）；adb install -r 192.168.137.45:5555 Success；轮 A tail -n +34619 / 轮 B tail -n +34817 会话锚对齐；capture start source=7→9→7 三段实证
verify_claim: 探针回退后 armed 仅打过门块（S52 hack 收回）；mode 0/2 对照 3+3 轮全部完整收账无幽灵轮无切尾，mode 0 恢复现役；悬浮窗 pref=true 双窗口已加
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt, docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 大票 A/B 归档（用户拍板：当前效果可以）

S52 交接后立项排程中的两张候选大票归档：**大票 A（软件 AGC 补偿/限幅预压）+ 大票 B（流式 ASR+语义端点），不立项**。依据：S52 规避方案（中等音量，峰值压 0.1 float 下）用户验收通过——连续两轮完整句收账后日常使用效果可接受，codec AGC 烸增益问题在现行用法下不再构成障斑。

**阶段 0 侦察留档**（立项前只读排查，已到手即归档，未来重启此票直接从这里继续）：
- 板上 `/system/bin/tinymix` + `tinycap` 存在（amixer 无）——tinyalsa 工具链可用性已验证 ✅。
- 声卡拓扑：0=rockchipbt / **1=rockchiprk809co（rk809 codec，采麦通路）** / 2=hdmi / 3=USB UGREEN CM564。`tinymix -D 0` 报 Failed to open mixer 系 card 0 是蓝牙（无 mixer），非工具不可用——**重启此票从 `tinymix -D 1 contents` 查起**，在 rk809 控件里找 AGC/ALC 开关。
- 若开关存在→大票 A 退化为「一行 mixer 设置 + init.rc/持久化小票」；若无→才进软件补偿长路（增益轨迹盲估）。

大票 B 归档口径：S51 续听窗（总静音容忍 ≈1.6s）+ 按住说话直通已覆盖现行场景，回忆型 2s+ 停顿的语义端点判定不再追（S51 边界拍板已明确「不迁就」）。

```evidence
verify_cmd: adb shell which tinymix tinycap（存在）；cat /proc/asound/cards（4 卡拓扑）；tinymix -D 0（Failed to open mixer，card 0=bt 无 mixer）
verify_claim: 用户拍板归档（会话内确认）；S52 规避方案验收依据见 DEV_LOG 2026-09-18 S52 条（两轮完整句收账）
files_touched: [docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 S52：S19-1 噪声上攻通道停用（逐块 RMS 探针定案早切真因）

接 S50/S51：用户报告「聆听时间足够长，说档案时还在聆听但没识别到，且基本无停顿（词间 <1s）」——证词与「回忆型停顿」结论冲突，重新取证。用 AudioProbe（诊断构建：armed 时去掉 0.015 门全量落行）拿逐块 RMS 包络，两个实锤：

1. **持续元音被 plateau 通道中途判停**：用户发「啊——」2s+（VAD 0.15~0.30 稳定，波动 0.10~0.15），VAD 在 02.012 中途判停——S19-1 的 plateau 通道把「稳定电平」判为「AGC 钉死噪声」触发上攻（floor 0.35/块 → end=floor×1.4 数块内越过说话者电平）。**稳定是长元音与钉死噪声的共同特征，判据天然误伤**。
2. **自然语句尾段悬崖**（17:36 轮逐块）：…0.30→0.26→0.18→0.095→**纯噪底 0.0013**（100ms 内 -17dB），之后 5.9s 无任何语音能量跨过派发/PROCESSING——half-peak 通道（level<peak/2 在自然语音动态里常真）触发上攻级联同样成立。

**根因分层定案**：①S19-1 上攻通道在正常语音动态上误伤（判停阈值被自己抬过说话者电平）；②真并发现在：codec AGC 对热输入砸模拟增益（峰值 float 越大切得越早：0.19→切 6 字 / 0.16→8 字 / 0.073 元音→存活；声学噪底 0.0082 也被压到 0.0013=只剩电子噪底，约 30 倍砸益指纹）——用户最初「是否与收音设备有关」问对了，且用户证词（无停顿）推翻了我此前「回忆停顿」的误判（证伪时误把 AGC 方向建反）。

**处置**：①`NOISE_ATTACK_ENABLED=false` 停用上攻通道（当前板端噪底 0.0013~0.002，S23 NLMS 后泵态不再观测，上攻收益消失、误伤恒在；开关保留，泵态复发再审）；三个 S19-1 旧测重写为「有界行为锁定」（泵态拖尾由 max_utterance 8s 兑底、幽灵轮由 S19-2 空 ASR 续听兜底）+ 新增持续音回归锁。②用户侧验证：中等音量（峰值压在 AGC 触发线下）连说两轮完整句「查询2022年的合同档案。」「查询2021年的合同档案。」全部完整收账。③遗留：codec AGC 热输入砸增益是硬件/固件层问题（UNPROCESSED/VOICE_RECOGNITION 均躲不过），软件侧候选=软 AGC 补偿/限幅预压（大票候选），当下降音量用法规避。

**追加（mode=0 核实）**：用户设回出厂 mode=0 并重启（source=7）后实喊「查询2023年的档案」完整收账（33600 samples）——注意 Settings 只写 pref，采集源构造时固化，**改后需重启才生效**；另澄清常数表：板上 pref0→source=7（VOICE_COMMUNICATION）、pref2→source=9（UNPROCESSED），全日 source=7 即 mode=0（此前「用户设过 mode1」系常数表记误，cliff 与模式无关的结论不变）。悬浮窗 pref 当前 false（用户关闭，待定是否恢复）。

**追加二（判停窗回调 600ms，用户拍板）**：S50 判停窗 6→8 的两个根因（弱音节掉线、上攻级联）已被 end 线拆分与 S52 停用上攻解决，800ms 的额外 200ms 延迟不再必要——`SILENCE_END_TICKS` 8→6 回调，总静音容忍 = 600ms + 续听窗 1000ms ≈ 1.6s。演进史终态：8→4(08-29)→6(09-11)→8(S50)→6(S52 晚)。对账表补注 4/清单、KwsDefaults 注释、VadDetectorTest 锚（注入 provider/基础判停/pre-roll/byte 路径/quiet-domain 五处）同步。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（633 全绿，含 S52 重写 3 测 + 持续音回归锁）；AudioProbe 诊断构建（armed 全量 cap rms）板端逐块包络取证；adb install -r 后真机两轮完整句验证 + force-stop 重启后 mode=0 复验一轮 + 判停窗 600ms 回调后全锚重跑
verify_claim: 持续稳定音 30 块（3s）无中途判停（旧代码 ~2s 即切）；用户中等音量连续两轮「查询202X年的合同档案。」ASR 全文完整（36800/38400 samples），S51 hold/dispatch 链正常；上攻停用后泵态场景由 max_utterance 有界兑底；mode=0 重启生效后完整句复验通过；判停窗 600ms（S52 晚回调）下全锚绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt(探针去门诊断), app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 S50+S51 语音端点双修：判停窗/阈值拆分 + 断句续听

用户报障「总是提前判停，是否与收音设备有关」。三阶段取证闭环：

**取证一（S50，判停参数）**：板端 15:59 四连切实锤——轻声说话句尾弱音节 RMS 跌破 0.05 被计静音，600ms 计满切尾（「盘点当前有哪」「盘点当前有」「进行档案」「进行档案盘」，maxLevel 0.31~0.47 vs 大声完整句 0.83）。用户拍板 A+B 组合：判停窗 6→8 块（回桌面端对账值）+ 判停/判起阈值拆分（end 线独立 0.03）。<b>关键发现</b>：环境音自动校准没缺席（09-14 自动跑过 + 09-16 手动重校两次），但公式 `suggestVadSpeechLevel = p95×1.6 coerceIn(0.05,0.15)` 在安静域被下限钳死（p95=0.0018×1.6=0.0029→0.05），校准数学上给不出低于 0.05 的判停线——拆分是唯一出路。改动：VadDetector 新增 `SPEECH_END_LEVEL=0.03` + `speechEndLevelProvider`（判起线继续跟校准联动；恒 end&lt;start），SILENCE_END_TICKS 6→8；KwsDefaults 注释/对账表补注 4 同步演进史。效果：切尾点从第 7 字推到第 8 字（弱音节不再掉线）。

**取证二（排除 AGC 假设）**：正常音量对照轮（maxLevel 0.39 更响）反而切得更早（第 7 字）——<b>切尾位置=气口位置，与音量无关</b>；换采集源关 AGC 的路 S18 已实测不通（rk809 codec 级，VOICE_RECOGNITION 也钉死）。真因：固定静音窗判停吃不掉长句字组气口，且切尾改变 LLM 工具路由（盘点被当查询调 c_archive_query）。

**修复（S51 断句续听，用户拍板）**：判停片段不即派发——`handleVadEvent` 截流→累积+重启 VAD+静音块倒计时（`CONTINUATION_TICKS=10`/1000ms）；语音级块或 `vad.isSpeaking`（含判停静音尾）冻结倒计时；窗耗尽合并成单条 utterance 走 `onUtterance` 原语义派发。直通例外：按住松开 stopListening（松开=明确说完）与单测直注不经截流（15 个既有 VSM 测零改动）。坑：判停静音尾与续听窗共用音频流重叠计数，窗剩余&lt;判停窗时片段成型中途提前派发（新用例 2 实锤）——`isSpeaking` 期冻结解。backToIdle/startListening 单点清挂起（同 kwsPaused 纪律）。<b>边界拍板</b>：回忆型 2s+ 停顿不迁就（每轮延迟不可接受），按住说话是正解；续听窗 1000ms 容忍总静音≈1.8s 覆盖短语间气口。

真机：S51 hold/dispatch 链 3 轮全对（713ms 派发无残留），一口气长句「查询当前有哪些2021年」完整收账。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（631 tests 全绿，含 S51 新增 4 测：hold 不即派发/续说冻结+双片段合并 20800 samples/cancel 清挂起/按住直通；VadDetectorTest S50 锚同步 6 处）；adb install -r 192.168.137.45:5555 + 板端日志取证（S51 continuation hold/dispatch 锚 + ASR text 对照）
verify_claim: S50：轻声弱音节 0.03~0.05 域计回说话（切尾点 7 字→8 字）；判起线校准联动零回归（S18/S19-1 用例全绿）。S51：带气口说话片段自动合并（hold→静音耗尽→单条派发）；cancel/超时/重唤醒清挂起无幽灵派发；按住松开零延迟直通；一口气长句 ASR 完整、延迟 = 判停 800ms + 续听窗 1000ms ≈1.8s（既定代价）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsDefaults.kt, docs/voice-param-reconciliation-2026-07-28.md, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS40BlindWindowTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt(新), docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-17 S48 Agent 全量拉取 + 会话闲置轮转 + 悬浮窗「创建新会话」

三项需求（grilling 一轮定案）：Q1=a 大 pageSize 一次拿全（失败退循环翻页）；Q2=惰性超时轮转——按消息 ts 记录，最后一条消息超 10 分钟无后续才在下次交互时新建（空会话无时间戳永不触发，杜绝空会话循环，用户拍板）；Q3=超时轮转完全静默；Q4=菜单「查看最近一轮」→「创建新会话」+ busy 置灰 + 成功气泡反馈。

#### 票 1：Agent 全量拉取
- 真机实测（curl + 板上 token）：`agent/list` 空 body 黔回前 10 条（信封 total:14/pageSize:10/pages:2，PageHelper 风格）；`{"pageSize":500}` 一把拿全 14 条
- `AguiClient.listAgents`：循环翻页聚合（pageNum 递增直到不足一页；20 页硬上限防服务端忽略参数死循环；401 透明重登后清空半卷从第 1 页重拉）；`listAgentsOnce` body 改 `{"pageSize":500,"pageNum":N}`
- `SettingsScreen` 选择器：限高 320dp 滚动列表（全量后不再撑爆页面）
- 坑：`chatNewSession?.invoke()` 在 runCatching 内产生 Boolean?，`getOrElse{false}` 后仍是可空——`?: false` 在 lambda 内先兜平

#### 票 2：会话闲置 10 分钟惰性轮转
- `SessionRules.shouldRotateForIdle(lastMessageTs, now, timeoutMs)`：纯函数；`ChatMessage.ts` 字段已有，零新增存储
- `ChatViewModel.rotateSessionIfIdle()`：静默版（不查 busy/无提示/存储异常降级不轮转），与 newSessionInternal 同构
- 触发点两处：文字 `send()`（upload gate 后、session 捕获前）+ 语音 `onUserUtterance`（置 voiceActive 守卫窗之前，rotate 后本轮消息入新会话/新 thread）
- resends 不轮转（旧会话内操作语义）

#### 票 3：悬浮窗菜单「查看最近一轮」→「创建新会话」
- `OverlayMenu` 行替换 + `canCreateNewSession` 置灰（busy 快照）
- Service→VM 双桥（chatInterrupt 同范式）：`chatBusyProvider={busy}`（菜单弹出时刻快照）+ `chatNewSession={newSessionFromOverlay()}`（busy 拒绝 false 双保险）；不进 APP，Service 侧 `showBubble(CHAT, "已开启新会话")` 反馈
- Activity 的 viewLastRound 哨兵机制保留未删（仅换菜单入口，surgical）

```evidence
verify_cmd: ./gradlew testDebugUnitTest（606 例全绿，+11：翻页聚合/401 重拉/分页 body 契约/超时判定 4 例）；真机：logcat `agent/list POST ... pageSize=500 pageNum=1` + 设置页可见超 10 个 agent（s48_agents.png）；chat send 空会话不触发轮转 ✓；悬浮窗菜单 dump 六项含「创建新会话」✓（s48 菜单 dump）。用户补验（2026-09-17 10:30）：② 菜单「创建新会话」板端日志实证 `menu: create new session, ok=true`（桥链路/busy gate/气泡链路通）；① 闲置轮转板端日志未捕获 rotate 记录（用户口头确认完成），规则层由 4 例单测 + 空会话不触发实测兜底
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/SessionRules.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientAgentListTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/chat/SessionRulesTest.kt
]
```

## 2026-09-17 S46 密集架页面导航（本地意图直连，不走平台）

需求：语音/文字「打开借阅/归还/盘点页面」→ 密集架 app 跳对应页面。平台侧注册 open_page MCP 工具不确定可行 → 改走本地意图分流（零平台改动），完全复用 S26-3 设备技能同款模式。

- 新增 `device/ShelfNavIntentMatcher`（纯 Kotlin，JVM 单测 20 例）：保守匹配——导航动词（打开/跳转/进入/切换/切到/去…）或页面名词 + 目标词（借阅/借书、归还/还书、盘点）三路取先现者；**含执行对象词（档案/文件/编号/盒…）绝不拦截**——「借阅档案A」必须走平台 MCP 工具链（防双控语义不破）
- `ShelfMcpBridge`：`NAVIGATE_PAGES = {borrow, return, inventory}` 白名单 + `navigate(page)` **同步** POST `{"Op":"mcpUiNavigate","page":...}`（区别 sync 的 fire-and-forget：导航需成败回执）；导航非 MCP 工具，不进 SYNCED_TOOLS
- `ChatViewModel`：文字轮 deviceCmd 后同级拦截 → `runShelfNavTurn`（零 LLM；成功「已打开XX页面」/失败「密集架联动不可用…」作为 assistant 终态上屏）
- `VoiceSessionManager`：S26-3 同款注入 `shelfNavMatcher/shelfNavExecutor`，命中走同一条分句/TTS 管线；密集架无自带播报 → **不置 turnSawToolCall**（正常 TTS，区别设备轮静音语义）
- `VoiceClientFactory`：按 `shelfBaseUrl` 非空装配（空=禁用零劫持）
- 密集架端（D:/mijijia 同步改动）：AppController 新增 mcpUiNavigate Op——borrow/return 走 searchPageRequest 跳检索页对应模式，inventory 走 openInventoryPage()（archives_inventory 同款）

边界：拦截后**不发云端**——「打开借阅页面」若发云端会被 LLM 误路由到 archive_borrow（执行语义）引发多余工具调用（S30 拦截语义同源）。

```evidence
verify_cmd: ./gradlew testDebugUnitTest --tests ShelfNavIntentMatcherTest（20 例全过）+ assembleDebug；密集架端 curl mcpUiNavigate borrow/return/inventory 三页真机跳转截图 + 非法 page 注入守卫（详见 D:/mijijia/docs/DEV_LOG.md S46 追加节）
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt,
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt,
  app/src/test/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcherTest.kt
]
```

## 2026-08-05 S8 UI 微调合集代码侧收口

### 会话背景
- 从 anzhuo4 死会话恢复（API 429 5 小时用量上限），加载上下文后继续
- 用户选择执行模式：串行关键路径（S8-3→S8-7）+ 派 opus critic 盲审
- 纪律：单测只增不减（基线 345），skipped=0，禁 mock/skip/删测；每守卫必须有变异锚测试

### 变更清单

#### S8-1 输入框视觉回滚两层+横线
- `ui/screens/InputBar.kt`：R3 单容器 → 两层独立 Surface + 层间 HorizontalDivider（outline 色 1dp）
- R3 功能修复全保留（行高 48、FunctionBar padding 4dp、isUp 快照、idle 粉底 gate、停止键、上传链）
- 纯视觉变更，无新单测

#### S8-2 图标白底
- `res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`：5 密度 PNG 替换（#FFFFFF 底 + 前景小朵形象）
- `res/values/ic_launcher_background.xml`：改 `#FFFFFF`
- 资源替换，无新单测

#### S8-3 TTS 中按住说话=打断并接续新一轮
- `voice/VoiceSessionManager.kt`：
  - `startListening`：speaking/processing 态按住（playWakeAck=false）→ cancelCurrentTurn → 进 LISTENING 采音
  - 3 处 sealed 回归守卫：consumeSentences 循环头 L490 + 合成后二查 L502 + 多轮迁移 L464
- `voice/VoiceSessionManagerBargeInTest.kt`：+2 测（PROCESSING 打断 4 断言 + SPEAKING 打断 stateFlow 闩）
- 测试 351→353

#### S8-4 气泡渲染 markdown 控制符修复
- `ui/overlay/BubbleTextStrip.kt`：裸 markdown 控制符剥离
- `ui/overlay/BubbleTextStripTest.kt`：+6 测（无空格标题/引用/加粗/斜体/行内代码/表格管道符）
- 测试 345→351（S8 开工前已完成）

#### S8-5 气泡文字改流式直出（撤 reveal 音频同步）
- `voice/VoiceSessionManager.kt`：
  - AG-UI delta 回调直接 bubble.show/append，与 TTS 播放解耦
  - 退役 playAndReveal/reveal 方法（RevealScheduler 类保留备查）
  - TTS 单句失败降级：气泡文字不受影响（delta 已上屏），仅跳过本句播放
- 无新单测（退役旧方法，delta 流式路径由现有 BargeInTest sealed 守卫覆盖）

#### S8-6 气泡「看不到开头」补救
- `ui/overlay/OverlayMenu.kt`：新增「查看最近一轮」菜单项（canViewLastRound 门控）
- `PetOverlayService.kt`：hasShownBubbleContent 标记 + playPetOnceThen companion 转发
- `MainActivity.kt`：handleViewLastRoundIntent + onNewIntent（EXTRA_VIEW_LAST_ROUND → ChatViewModel）
- `chat/ChatViewModel.kt`：pendingScrollMessageId 一次性事件（哨兵 `__last__`）+ consumePendingScroll
- `ui/screens/ChatScreen.kt`：LaunchedEffect(pendingScrollMessageId) 消费 → scrollToItem + listState 上提
- 无新单测（ChatViewModel 是 AndroidViewModel，项目无 Robolectric/mock 基建，纯 JVM 不可达；降级为真机取证）

#### S8-7 唤醒成功即时视觉反馈
- `voice/VoiceSessionManager.kt`：
  - `_wakeAckActive: MutableStateFlow<Boolean>` + 公开 `wakeAckActive` StateFlow
  - startListening(playWakeAck=true)：入口置 true + playPetOnceThen(JUMPING, WAITING)
  - transition()：SPEAKING + wakeAckActive → 跳过通用 TALKING 映射
  - wozaiJob finally：无条件 reset false（critic P2 修复）
  - cancelCurrentTurn：reset false
- `ui/screens/FullscreenVoiceScreen.kt`：LaunchedEffect 订阅 wakeAckActive，gate SPEAKING 映射
- `ui/PetView.kt`：playOnceThen 脚手架（上一会话已就绪）
- `PetOverlayService.kt`：playPetOnceThen companion 转发
- `voice/VoiceSessionManagerWakeAckTest.kt`：+3 测（时序/按住路径/cancel 复位）+ 1 增强
- 测试 353→356

#### S8-8 生成中等候态三点动效
- `ui/screens/ChatScreen.kt`：
  - TypingDots Composable（三圆点错相呼吸，900ms 周期，三角波 alpha 0.3↔1.0）
  - SENDING 状态替换原 CircularProgressIndicator + "小朵思考中…" 文字
- 无新单测（Compose UI 动画，重组不崩即为验收）

### 测试基线
- **356 绿 skipped=0 failures=0 errors=0**
- 增量：HEAD 345 → S8-4 +6 → S8-3 +2 → S8-7 +3 = 356
- 验证命令：`./gradlew.bat testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL

### 工作树
- `git diff HEAD --stat`：20 files, +595/-153
- 零 commit，所有变更在 working tree
- 真机 de61cd8 在线，apk 已装（app/build/outputs/apk/debug/app-debug.apk）

### Critic 轮次汇总
| 票 | critic | 结果 | P1 | P2 | P3 |
|---|---|---|---|---|---|
| S8-3 | 不逊 | 4 P2 → 修复 3，1 留档 | 0 | 4→1 | 0 |
| S8-5 | 不逊 | 1 P2 + 1 P3（首轮 critic 死亡，重派） | 0 | 1 | 1 |
| S8-6 | 不逊 | 1 P2 降级 P3 + 4 P3 | 0 | 0 | 5 |
| S8-7 | 不逊 | 1 P2 已修复 + 5 P3 | 0 | 0 | 5 |
| S8-8 | 不逊 | 2 P3 | 0 | 0 | 2 |

### 剩余事项（下会话真机验证）
1. **S8-5 P2**：delta callback check-then-act 竞态窗（L438 sealed → L444 bubble.show，cancel 可插入致幽灵气泡）——权限低，真机期顺修
2. **S8-5 P3**：BargeInTest 注释 "reveal 逐字查岗" → "delta sealed 拦截"
3. **真机逐票验收**（见 CONSTRUCTION.md 各票验收条）
4. **分票 git commit**（当前零 commit，建议每票一 commit + 真机证据截图附 commit message）
5. **S8-2 桌面截图**：浅色/深色壁纸各一，确认白底图标可读性（已抓 `C:\Users\Administrator\s8-2-desktop.png`）

```evidence
verify_cmd: ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL, 356 tests, skipped=0, failures=0, errors=0
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/PetView.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/res/mipmap-*/ic_launcher.png,
  app/src/main/res/values/ic_launcher_background.xml,
  app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStripTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerBargeInTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt
]
```
