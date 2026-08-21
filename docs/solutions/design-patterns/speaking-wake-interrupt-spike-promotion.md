---
title: 调试旁路 spike 经真机双合格后转正为生产行为——转正票同票退役 spike 设施（S12 唤醒词打断全链）
date: 2026-08-21
type: solution
category: design-patterns
module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
problem_type: design_pattern
component: assistant
severity: medium
related_components: [kws]
resolution_type: design
applies_when:
  - "调试旁路/实验开关（spike、feature flag、脚手架）经真实环境双合格（有效性证据 + 检出率达标）后，准备转正为生产行为时（须一并退役 spike 设施与其测试，避免调试/生产双轨并存）"
  - "半双工/互斥类「曾经正确的防御结构」需为新产品能力松绑时（逐道屏障审视前提，成立的留、失效的拆）"
  - "新打开的可达路径让旧异步收尾代码（finally/回调）产生跨轮竞态时（世代号守卫）"
  - "用 fed/gated 形态定性区分『没喂到』与『没检出』（如 AEC 偶发失效态观察票）时"
tags: [wake-word-interrupt, spike-promotion, kws-always-on, generation-guard, test-retirement, barge-in, half-duplex, debug-bypass-retirement]
---

# spike 双合格 → 转正 → 设施退役：调试旁路毕业为生产行为的完整闭环（S12 案）

## Context

小朵语音管线是半双工四态（IDLE/LISTENING/PROCESSING/SPEAKING）。这套结构当初是**防自激**的：
TTS 外放的自声若进 KWS（唤醒词检测）会自我唤醒，若进 VAD 会自我判停。因此路由层对
PROCESSING/SPEAKING 的麦块恒 DROP，再叠 `kwsPaused`（对话期置位）与 `startListening`
非 IDLE 早退，共三重屏障（核查结论见 `docs/verification/v17-kws-accuracy/README.md` §6.1）。
这三重屏障在 S0-S8 时代是正确的默认——直到用户提出「TTS 播放中喊唤醒词打断」（08-19 主诉），
需求与结构直接冲突：打断要求播放期人声可达 KWS，结构恰恰把它挡死。

任务源起（session history）：2026-08-19 用户原话把任务拆成两条轨道——「唤醒识别准确率不高」（诊断）
与「检查是否支持唤醒词打断」（核查），交接纪律明确**先定性再动手、不擅自开工实现**。预研纯代码
结论=三重屏障下打断结构不可达；这个「结构性前置排除」判断事后被证明准确——S12 拆的正是它
指认的那两堵墙。另：S12 双合格所依赖的 KWS 全栈设施（模型/AAR/wozai 资产）在 08-18 仓库事故
恢复期按逐字节口径保全核验过（session history，恢复会话），实验设施可信度有出处。

松绑不能拍脑袋：屏障的存在理由是「TTS 回声可能误触发唤醒词」，这个前提是否真成立取决于
硬件 AEC 的实际表现。于是走 spike 路径——但这里有个前车之鉴：08-04 的旧 spike 曾得出
「TTS 播放 5.8min 零误触发」的完美结论，2026-08-19 复查石锤为**空证据**（旧旁路只捅
`kwsPaused`，路由层对 SPEAKING 恒 DROP，KWS 全程没收到音频，详见
`docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md`）。S11-3
重做真放行 spike：`spikeFeedKws` 调试开关让 SPEAKING 态麦块真实进 KWS，并配 **fed 喂流
有效性闸**（观测期 fed 必须真实增长，否则结论作废）。

2026-08-20 真机双合格（v17 §6.2）：**误触发**长回复 TTS ~4min `keyword hit:` = 0，且
有效性有喂流佐证（播放起始 3 块过门进 KWS 为 AEC 收敛瞬态、max rms 0.15，其后回声全在
能量门 0.015 之下）——硬件 AEC 把回声压到门下，误触发结构性近乎不可能；**检出率**播放中
真声 8/10（达标线 ≥8/10），fed 播放期持续增长（6587→6748）——AEC 对近场人声约 2 成损耗
是打断能力的固有成本，接受。用户拍板立项（两默认值：打断时播「我在」；常开不设开关），
遂有 S12 转正票（commit `b862a07`，docs 收口 `9570c31`，注释补落 `13dd721`，均已在 main）。

## Guidance

核心模式是一条四步流水线，**每一步都不可省**：

> 有效 spike（真放行 + 喂流佐证）→ 真机双合格（误触发与检出率两个独立口径）→
> 转正（改默认值，复用既有分支）→ **设施退役（与转正同一张票删干净）**

### 1. 转正 = 改默认值，不是加开关常开

spike 证明了「SPEAKING/PROCESSING 进 KWS」安全，转正就把路由表改成生产行为本身，
而不是让 spike 开关长期挂着。`app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt:27-34`：

```kotlin
// before（spike 期）：默认 DROP，spike 开关是唯一放行口
State.PROCESSING -> Sink.DROP
State.SPEAKING -> if (spikeFeedKws) Sink.KWS else Sink.DROP

// after（S12 转正，b862a07）：两态默认进 KWS，spikeFeedKws 属性整体删除
// S12 回归守卫：SPEAKING/PROCESSING 恒 KWS（唤醒词打断的声学前提，spike 双合格
// 转正——删此回退恒 DROP → 对话期唤醒词不可达 → VoiceStateRouterTest 红）
State.PROCESSING -> Sink.KWS
State.SPEAKING -> Sink.KWS
```

注意 `kwsPaused`（三重屏障之二）**保留**：它只门 IDLE 分支（对话期回到 IDLE 不即解 KWS，
防收尾瞬间自声误触发），与「对话两态麦块进 KWS」正交不冲突（`VoiceStateRouter.kt` kwsPaused
KDoc）。松绑不是拆墙，是逐条审视每道屏障的前提还成不成立——成立的留下，失效的拆掉。

### 2. 打断接续复用既有 cancel 分支，不另起炉灶

第三道屏障（`startListening` 非 IDLE 早退）本已有半扇门：S8-3 按住说话打断。S12 只是把
唤醒路径放进同一扇门。`VoiceSessionManager.kt` startListening：

```kotlin
// before：打断分支只对按住路径开放（!playWakeAck），唤醒路径在对话态物理不可达
if (!playWakeAck && (state == SPEAKING || state == PROCESSING)) {
    cancelCurrentTurn()
}
if (state != IDLE) return

// after：条件去掉 !playWakeAck——唤醒打断与按住打断共用同一 cancelCurrentTurn
if (state == VoiceStateRouter.State.SPEAKING || state == VoiceStateRouter.State.PROCESSING) {
    cancelCurrentTurn()
}
if (state != VoiceStateRouter.State.IDLE) return
```

`cancelCurrentTurn()`（`VoiceSessionManager.kt` cancelCurrentTurn 段）是全同步打断：封轮
（TurnFlags sealed + 部分回复补发 done 非 error）→ 停「我在」/停 TTS/停管线 → 收泡 →
回 IDLE。返回后 state==IDLE，唤醒路径接着播「我在」→LISTENING，按住路径直进 LISTENING。
复用的收益是显然的：打断语义（旧轮残跑协程隔离、终态记账、气泡收尾）只在一份代码里维护，
S8-3 全部回归测试白捡。LISTENING 态唤醒仍物理不可达（VAD 占用麦块），这是设计而非遗漏。

### 3. 新可达路径打开后，旧收尾代码要用世代号守卫

转正自己引入了新竞态：S12 之前「SPEAKING(wozai) 期再喊唤醒词」物理不可达，如今可达了
（唤醒打断唤醒）。旧 `wozaiJob` 的 finally 无条件写 `_wakeAckActive.value = false`——
旧 job 残跑收尾若落在新一轮置 true 之后，会把新一轮信号覆盖掉。这是 builder 开工前的
「守卫四问」（谁开窗谁关窗/异常路径终态/取消语义/重复触发）抓出的自引入竞态。修法是
**世代号**（`VoiceSessionManager.kt:165` 声明、`:266` 入口自增、`:290` 收尾比对）：

```kotlin
private val wozaiEpoch = AtomicLong(0)          // :165

private fun playAckThenListen() {
    val epoch = wozaiEpoch.incrementAndGet()    // :266 每轮自增，闭包捕获
    _wakeAckActive.value = true
    ...
    finally {
        // S12 回归守卫（世代比对）：唤醒打断唤醒时旧 wozaiJob 残跑收尾可落在
        // 新一轮置 true 之后——世代已变则本 job 是旧轮残跑，不得复位新一轮信号
        if (wozaiEpoch.get() == epoch) _wakeAckActive.value = false   // :290
    }
}
```

选世代号而非「job 体自读 wozaiJob 引用比对」，是因为闭包捕获自增值没有发布竞态
（KDoc 原话，`VoiceSessionManager.kt` wozaiEpoch 声明处）。通用教训：**凡打开一条历史
新可达路径，逐个审计「原本不可达所以写得天真」的老代码**——尤其是 finally/回调这类收尾逻辑。

### 4. 设施退役是转正票的一部分：全链删除，同票完成

spikeFeedKws 在 `b862a07` 里随转正**全链删除**，一处不留：router 属性、VSM 委托属性、
AecSpikeSection ①② 段（现存仅 ③ 延迟探针，`AecSpikeSection.kt` KDoc 记明退役原委）、
`VoiceSessionManagerSpikeFeedTest` 全文件、router spike 用例。理由见下节。

### 5. 测试随行为 1:1 置换且净增，变异锚重新锚定

测试记账（亲数 `b862a07` diff 的 @Test 增删）：**454 → 455 = −2 + 3**。删 2：
`VoiceSessionManagerSpikeFeedTest` 全文件 1 测随设施退役 + `VoiceSessionManagerWakeAckTest`
的「重复唤醒被 IDLE 守卫丢弃」废用例 1 测——它的前提「唤醒在 SPEAKING 物理不可达」
（原断言 `playCalls == 1`）随 S12 失效，新行为（打断重播）由新用例以 `playCalls == 2`
断言取代。增 3：`VoiceSessionManagerWakeInterruptTest`（SPEAKING 唤醒全链 / PROCESSING
唤醒全链 / 唤醒打断唤醒世代守卫，文件 KDoc 自述全部变异锚）。另有 1 处原地改写
不计数：router 的 S11-3 spike 用例改写为 S12 断言（「spike 放行」→「默认恒 KWS 且不受
kwsPaused 门控」）。三个变异锚全部亲验转红：route 回退恒 DROP → 2 红；startListening
唤醒路径早退恢复（不 cancel）→ 3 红；删 wozaiEpoch 世代比对 → 1 红。

## Why This Matters

**两条等价路径并存必漂移。** 本案的 08-04 空证据事故，根因正是「spike 旁路」与「路由层」
两层各自演化：旁路是按当时的路由语义写的（捅 kwsPaused），路由后来改成 SPEAKING 恒 DROP，
旁路就静默变成了 no-op——没有任何测试会红，因为 spike 路径没有生产语义的守卫。spike 开关
活得越久，这类漂移的期望次数越大；转正后若把 spikeFeedKws 留着「以后也许还用」，等于在
生产 route（恒 KWS）旁边留一条语义重叠但生命周期规则不同的旧路（不被状态迁移复位、
由 UI arm/disarm 管理）——下一个读到这两条路径的人必然问「该用哪个」，而答案是「都行但
不一样」，这就是漂移的起点。

**退役时机 = 转正那一刻最便宜。** 此刻全部上下文在脑中（spike 结论、双合格口径、屏障
清单）、变异锚可以顺手亲验（删设施后哪些测试该红一目了然）、critic 正在场（本票 critic
顺手抓了 F1/F3 两处注释漂移）。拖到下一票，退役就要重新考古「这个开关还有没有人用」，
成本单调上升；拖到出事，就是又一篇空证据式的机构解法。

## When to Apply

- 任何调试旁路、实验开关、feature flag、临时脚手架**毕业为生产行为**时：转正票 = 改默认值
  + 复用既有分支 + 新可达路径竞态审计 + 旁路设施同票全链删除 + 测试 1:1 置换净增。
- 半双工/互斥/熔断类「曾经正确的防御结构」需要为新产品能力松绑时：逐道屏障审视前提
  （本案前提「回声必误触发」被真机 AEC 实测证伪），成立的保留并更新语义注释，失效的拆除。
- 新可达路径打开时：审计所有「原本不可达所以写得天真」的老代码（finally/回调收尾），
  跨轮竞态优先考虑世代号守卫（闭包捕获，无引用发布竞态）。
- spike 结论立项前：双合格 = 误触发与检出率两个独立口径，且零发生结论必须有正向喂流
  计数佐证（这是上一篇的教训，本篇是其后续）。

## Examples

S12 全链的关键数字（证据 `docs/verification/v17-kws-accuracy/README.md` §6；日志锚
`DEV_LOG.md` 2026-08-21 条目；票据 `CONSTRUCTION.md` S12 节；接手指引 `PROGRESS.md` S12 段）：

- **spike 双合格口径**（08-20 真机）：误触发 0 次 / TTS ~4min（喂流佐证：起始 3 块过门
  max rms 0.15 为 AEC 收敛瞬态，其后回声全在门 0.015 之下）；检出 8/10（fed 6587→6748
  持续增长，AEC 近场人声 ~2 成损耗为固有成本）。
- **转正实现**（`b862a07`）：7 文件 +537/−211；测试 454→455（−2+3，见上节分解）；
  三变异锚红数 2/3/1。
- **真机验收**（08-21，真机 de61cd8＝adb 设备序列号，非 commit）：正常条件 4/4 + 大音量 4/4 = **8/8**，播放中打断链
  日志完整（hit → barge-in cancel → 「我在」→ LISTENING）。
- **What Didn't Work**：08-21 上午首轮打断 0/N——该窗 `kws stats` 呈 **fed +600 /
  gated +0**（TTS 回声持续过门一分钟），与常态「回声在门下」相反，定性为 **AEC 偶发
  失效态**（疑与 09:03 重装后 09:05 首启有关，18:08 重启后消失未复现）。诊断方法论沉淀：
  **打断失灵先看该窗 fed/gated 形态**——回声持续过门 = AEC 失效窗；回声在门下而人声
  不中 = 另查。大音量轮 4/4 同时反证了「回声过门必灭」的简单模型，因此 S12-2 状态感知
  门控**不盲修缓行**（失败模型未坐实不叠加防御层——这与「退役比留存便宜」是同一纪律的
  两面：设施要么转正要么删除，不悬置）。

## Related

- `docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md` —— 前篇：
  spike 实验有效性必须用喂流计数佐证（本篇 spike 双合格的方法学前提；其 Fix 节描述的
  spikeFeedKws 设施已随本篇 S12 转正退役，SPEAKING 进 KWS 成为默认行为）。
- `docs/solutions/logic-errors/energy-gate-drop-breaks-streaming-kws.md` —— 相邻票（S11-7）：
  能量门+挂起窗在 S12 防自激中与硬件 AEC 构成双层屏障。
- `docs/verification/v17-kws-accuracy/README.md` §6 —— 本案全证据链（核查→spike→实现→真机）。
