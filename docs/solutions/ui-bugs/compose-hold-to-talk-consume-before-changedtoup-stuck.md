---
title: Compose 手势循环先 consume 后判 changedToUp 致按住说话松手不收——先吞后判恒 false 循环出口失效
date: 2026-08-05
category: ui-bugs
module: app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt
problem_type: ui_bug
component: assistant
symptoms:
  - 松手后录音不停止不发送,VAD 空转 30s 超时自收(日志 VAD listen timeout,无 manual release)
  - "UI 永卡按住态:「松手发送 上移取消」提示与声波纹行 90 秒以上不消失"
  - 第二次按住说话零日志,事件被卡死的手势循环吞掉
root_cause: logic_error
resolution_type: code_fix
severity: high
tags: [jetpack-compose, pointer-input, changedtoup, consume, hold-to-talk, voice-input, gesture-loop, awaiteachgesture]
---

# Compose 手势循环先 consume 后判 changedToUp 致松手永不物化(P1)

## Problem

S7-f 输入框重构把「按住说话」从 `detectTapGestures` + `tryAwaitRelease` 重写为 `awaitEachGesture` 逐事件循环(为上滑取消能力),循环体内**先 `change.consume()` 后判 `change.changedToUp()`**——compose-ui 1.6.8 语义下 changedToUp 对已 consumed 的 change 恒返回 false,正常松手路径物理不可达,`onVoiceEnd` 永不被调用,录音只能靠 VAD 30 秒超时兜底自收。

## Symptoms

真机 de61cd8(adb 设备序列号,非 commit;OPPO PJD110,ColorOS)2026-08-04 实测:

1. **松手不物化**:按住 4 秒松手后无 `manual release` 日志,VAD 空转至 30 秒超时自收:
   ```
   VAD listen timeout, maxLevel=0.0341
   state -> IDLE (listen timeout)
   ```
   PetLogger 私有日志时间窗 grep 实锤:`startListening` 有、`stopListening` 无——按下路径正常,松手路径从未到达。
2. **UI 卡死**:松手 90 秒后界面仍停留「松手发送 上移取消」+ 声波纹行——`holding` 状态未复位,说明 `awaitEachGesture` 块内的 while 循环从未退出。
3. **第二次按住零日志**:卡死的 while 循环吞掉新手势的全部事件,`awaitEachGesture` 块永不返回,后续按下完全无响应。
4. **对照观察(非症状,诊断线索)**:第三次操作(上滑取消)却正常——`catch (CancellationException)` 分支是循环唯一活口——系统取消手势协程时异常路径仍能走完收尾。

排查期误导项:第一帧截图(f10)显示 UI 卡在按住态,一度引向「recomposition / 离组合状态丢失」方向,后被日志时间线纠正(见下节)。

## What Didn't Work

排查中排除的错误假设及排除依据:

- **`stopVoiceHold` 的 `runCatching` 静默吞异常**(初判主嫌疑):排除——日志证明 `onVoiceEnd` **从未被调用**,根本走不到 stopVoiceHold 内部,吞不吞异常无关紧要。
- **`VoiceClientFactory` 双实例**(怀疑状态写到另一个实例上):排除——工厂是单例,静态追踪确认全链同一实例。
- **recomposition 离组合导致状态错乱**(被 f10 截图误导的方向):排除——`holding` 是 `remember { mutableStateOf(false) }` 而非 `rememberSaveable`,若真离组合应复位回 false,与「截图持续卡按住态」矛盾。截图只是结果不是原因:holding 卡在 true 是因为 while 循环没退出,不是状态管理丢失。
- **JVM 单测覆盖**:该缺陷在 critic 代码评审期无法由 JVM 单测发现——`changedToUp`/`consume` 的真实语义在 compose-ui 原生层,JVM 单测里无可信替身。「语义验收型(可触性)」条目在评审时被标注「待设备窗口」递延,真机一抓即穿。
- **同表象旧票误导**(session history):仓内 2026-07-31 有一条真机上报「语音输入(按住说话)的内容在页面上不显示」——表象相似(按住说话不物化),但当时根因是**语音轮消息未写入会话历史**,与本次手势层事件丢失根因不同。排查时勿被旧票表象误导,两案可并存。

最终定位由 Explore agent 静态追踪 + **javap 字节码实证**完成:两个独立 agent 分别对 Gradle 缓存中的 `ui-android-1.6.8` AAR 执行 javap 反汇编 `PointerEventKt`/`PointerInputChange`,亲验 `changedToUp()` 方法体首条指令即 `invokevirtual isConsumed` + `ifne` 直返 false——consume 过的 change 上 changedToUp 恒 false 是字节码级事实,非 API 印象。

## Solution

修复 commit **2da482c**(本仓 main,单仓无 PR 流程),`app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt`(6 insertions/2 deletions:P1 净增 1 行代码 + 1 行注释 + 1 行替换;P3 小修另 2 行注释 + 1 行替换)。

修复前(S7-f 重构引入的缺陷版):

```kotlin
while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.first()
    totalDy += change.positionChange().y
    change.consume()                       // 先吞
    cancelled = totalDy < -cancelThresholdPx
    if (change.changedToUp()) break        // 后判——恒 false,break 永不可达
}
```

修复后(行号锚定 commit 2da482c 时点;后续 S8-1 视觉回滚不改手势语义,行号可能漂移):

```kotlin
while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.first()
    // consume 后 changedToUp 恒 false(compose-ui 1.6 语义),必须先判后吞
    val isUp = change.changedToUp()        // L274:consume 前快照
    totalDy += change.positionChange().y
    change.consume()                       // L276
    cancelled = totalDy < -cancelThresholdPx
    if (isUp) break                        // L279:用快照判定
}
```

同 commit 还带一个关联小修(P3):`cancelled` 是 `remember`,松手后仍为 true,Surface 配色未 gate `holding` 会导致 idle 态永久挂 `errorContainer` 粉底;修复为 `holding && cancelled` 才粉(InputBar.kt:295-299)。

修复后真机复验全绿:

- 松手即 `state -> IDLE (manual release, no utterance)`;
- 音频驱动下 `VAD utterance, maxLevel=0.4146` → ASR → LLM → SPEAKING 全链;
- 上滑 80dp 取消 `cancel listening`,零消息发出;
- 345 单测绿 skipped=0;双 critic(含独立 javap 复核)判不逊。

## Why This Works

compose-ui 1.6.8(compose-bom 2024.06.00,app/build.gradle.kts:51)中 `PointerInputChange.changedToUp()` 的实现首条指令是 `if (isConsumed()) return false`(javap 双 agent 亲验:`invokevirtual isConsumed` 后接 `ifne` 直返 false)。而 `consume()` 会同时置位 `downChange` 与 `positionChange`,使 `isConsumed()` 返回 true。因此**任何在 consume 之后调用的 changedToUp/changedToDown 都必然 false**——这不是边界条件,是恒等式。修复前循环里 break 条件恒不成立,正常松手路径在字节码语义层面不可达。

修复后 `val isUp = change.changedToUp()` 在 `consume()` **之前**读取,此时 change 尚未被消费,isConsumed=false,changedToUp 返回真实语义,松手事件能正确触发 break。

为什么修复前 catch 分支是唯一活口:while 循环的退出条件只有两个——`if (changedToUp()) break`(已被 consume 毒死)和协程取消抛出的 `CancellationException`(catch 于 InputBar.kt:281-286,按取消收尾防误发)。系统取消手势(如来电、窗口切换、新手势抢占)时异常路径仍然通畅,这解释了症状④「上滑取消却正常」——它不是changedToUp 路径复活,而是走了异常出口。旧实现 `detectTapGestures` + `tryAwaitRelease` 无此病,因为 `tryAwaitRelease` 的内部等待不受外层 consume 影响。

**本仓 consume 语义坑第二例**(session history):2026-07-31 已有同族先例——Button 内建 `clickable` 先消费 down 事件,`detectTapGestures` 默认 `requireUnconsumed=true` 永远等不到 `onPress`,当时修法是改走 `awaitEachGesture` + `awaitFirstDown(requireUnconsumed=false)` 手写循环。本次 bug 正是写在这条手写循环路径上的 consume 语义第二坑:第一坑是「别人 consume 了我等不到」,第二坑是「我自己 consume 了我判不到」。 consume 家族语义(requireUnconsumed 门/changedTo* 对已消费事件的短路)是本仓手势代码的高危区。

## Prevention

1. **compose 手势循环铁律:先判后吞**。任何手写 `awaitPointerEvent` 循环中,`changedToUp()`/`changedToDown()` 等判定必须在 `consume()` 之前读取(快照入局部变量),或直接使用 `changedToUpIgnoreConsumed()`。代码注释留痕(本次修复已在 InputBar.kt:273 留注释说明 compose-ui 1.6 语义)。
2. **评审规则:「语义验收型」条目不得从 API 印象推断、不得无限递延真机**。可触性/可听性/可见性条目在 critic 评审时若标「待设备窗口」,必须设明确闭环时限并阻塞收口——本次 P1 正是「组件默认应该有」式推断 + 递延真机的复合产物,真机一抓即穿。
3. **手势 while 循环必须存在不依赖事件内容的退出路径**。循环内唯一 break 依赖事件语义(可被 consume 这类状态污染毒死)时,必须有第二条出口:系统取消兜底(catch CancellationException 正确收尾)是最低要求;本次缺陷中这条兜底反而成了诊断线索(唯一活口=唯一出口)。
4. **手势逻辑评审检查清单**(此类代码 critic 必查):
   - [ ] 循环内 `consume()` 调用点之后是否还有任何 `changedTo*`/`isConsumed` 敏感判定?(有即打回)
   - [ ] break/return 出口枚举:每个出口是否可达?是否存在「唯一出口依赖事件内容」的结构?
   - [ ] 异常路径(catch 块)是否完成全部收尾物化(状态复位 + 回调分发)?吞异常后是否有日志?
   - [ ] `remember` 状态在松手/取消/离组合三条路径上是否都复位?(本次 P3 即 cancelled 未 gate holding)
   - [ ] 是否有真机验收条目?递延的「待设备窗口」项是否有闭环时限?
5. **字节码存疑即验**:对 framework 行为的断言(如「changedToUp 应该不受 consume 影响」)不许凭 API 文档印象下结论,javap 反汇编 Gradle 缓存 AAR 是五分钟成本的实证手段,本次双 agent 独立复核即范例。

## Related Issues

- `docs/solutions/ui-bugs/material3-121-modal-drawer-scrim-back.md` — 同域(Compose 手势 ui_bug)独立问题;共享预防规则「Compose 手势/默认行为必须真机断言,不能从 API 印象推断」。
- 2026-07-31 consume 语义第一坑(clickable 先消费 down + requireUnconsumed 等不到 onPress),记录于仓根 `implementation-notes.html` 设计决策条目(session history)。
