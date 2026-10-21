---
title: 注释守卫锚必须锚在被测接线上——判定器类直测不构成接线覆盖（6 轮变异测试证伪 792 全绿假锚，readLoop 三接线点收敛顶层 seam 修复）
date: 2026-10-21
type: solution
category: workflow-issues
module: app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt
problem_type: workflow_issue
component: audio-capture
root_cause: missing_workflow_step
resolution_type: code_fix
severity: medium
applies_when:
  - "在移植/重建中为接线点写「删此→XxxTest 组红」类守卫注释时"
  - "守卫测试直接实例化判定器类（TerminalStartFailureDetector/ZeroBlockDetector 型）而从不经过真实装配路径"
  - "裁决调用点在 Android 域 JVM 不可达，需抽顶层纯 seam（guard 作入参）方能零 mock 实测"
tags: [mutation-anchor, wiring-coverage, test-seam, audio-capture, structural-guard, guard-comment, port-4cap]
---

# 注释守卫锚必须锚在被测接线上——判定器类直测不构成接线覆盖

> 仓库：E:/desk-pet-android（分支 port/baseline-4cap-upstream-47b240a，2026-10-21 修复轮落地，评审通过前未合 main）。本文代码断言均按撰写时 HEAD=35f5a0d 逐条核实并标 file:line；会话评审过程性事实标注「（本会话评审记录）」。
> 本文是 [[unanchored-code-silent-loss-in-rebuild]]（变异锚=「分支删除态」必须红）在**接线层**的延伸：那篇讲「没锚的代码会静默丢」，这篇讲「**注释声称有锚、实测锚不到**」的变体——守卫注释点名了一个测试，但那个测试根本观测不到被守卫的行。

## Context

背景：port 4cap 移植战役把 fork 的采麦层判定器（TerminalStartFailureDetector / ZeroBlockDetector / SoftwareAgc，纯逻辑类）移植进 `app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt`。移植代码在 readLoop 的三个接线点写了「回归守卫注释」，口径都是「删此 → 某测试组红」，例如轮级终态失败计数处声称「删此 → AudioCaptureTerminalStartFailureDetectorTest 连败触发组红」（修复前口径，本会话评审记录）。

缺口：**三个被点名的测试文件全部直接实例化判定器类，从不构造/驱动 AudioCapture 的 readLoop**（Android 域 JVM 不可达，仓内禁 Robolectric/Mockito/MockK）——`AudioCaptureTerminalStartFailureDetectorTest.kt:29` 等 4 处 `val d = TerminalStartFailureDetector()`、`AudioCaptureZeroBlockDetectorTest.kt:61` 等 4 处 `val d = ZeroBlockDetector()`；全测试树对 `readLoop` 的引用只存在于注释里（如 `AudioCaptureSourceDriftTest.kt:21` 自陈「WARN 接线属 Android 路径 JVM 不可达」）。仓内常量 MAX_START_ATTEMPTS / CAPTURE_REBUILD_DELAY_MS 修复前只出现在 main、零测试引用（本会话评审记录；修复后由 AudioCaptureWiringSeamTest.kt:39-43 钉死）。

揭穿：独立评审 testing 评审员在隔离 worktree 做 6 轮变异、点名可复原的 5 个变异靶（本会话评审记录）——M-C 重试环 `attempt >= MAX_START_ATTEMPTS` 改 `>= 1`（退化为单次重试）、M-F 终态失败计数调用改 `if (false)`、M-E 删 `terminalFailureGuard.reset()`、M-G 全零判定改 `if (false)`、SoftwareAgc 弱链 `gRaw > AGC_MAX_GAIN` 子句删除——**全套件 792 绿纹丝不动**。即：自愈链（detect → count → reset → killProcess → START_STICKY 重拉）整条拔线，绿色照样认证。守卫注释许诺的「红」在测试拓扑上不可能发生。

## Guidance

**判定规则一句话：守卫注释只有在其点名的测试能实际观测到被守卫那行时才是真锚；直接实例化判定器类的测试只锚「判定本体」，对「readLoop 还调不调它」零覆盖。** 写「删此 → X 红」之前先自问：X 的测试对象是这一行吗？把测试文件打开，看它构造的是什么。

**实践做法：把接线裁决收敛为顶层 internal 纯 seam 函数，guard 作函数入参（不是读字段）。** 调用方持有状态（readLoop 局部变量/实例字段）传入，seam 本身零 Android 依赖，测试免构造 AudioCapture 即可 JVM 直驱。本仓落地（HEAD=35f5a0d，`AudioCapture.kt`）：

```kotlin
// AudioCapture.kt:514-517 —— 重试裁决（达上限含边界 GIVE_UP）
internal enum class StartRecordRetry { RETRY, GIVE_UP }
internal fun startRecordRetryOutcome(attempt: Int, maxAttempts: Int = MAX_START_ATTEMPTS): StartRecordRetry =
    if (attempt >= maxAttempts) StartRecordRetry.GIVE_UP else StartRecordRetry.RETRY

// AudioCapture.kt:527-533 —— 启动成败裁决（成→复位+false，败→转发计数）
internal fun onCaptureStartOutcome(guard: TerminalStartFailureDetector, startedOk: Boolean): Boolean {
    if (startedOk) { guard.reset(); return false }
    return guard.onTerminalFailure()
}

// AudioCapture.kt:541-542 —— 全零自愈裁决（纯转发，全零口径收敛在 isAllZeroPcm16）
internal fun onZeroBlockOutcome(guard: ZeroBlockDetector, block: ByteArray): Boolean =
    guard.onBlock(isAllZeroPcm16(block))
```

调用点保持一行薄接线（`AudioCapture.kt:196` 终态失败、`:212` 成功复位、`:237` 全零喂块、`startRecordWithRetry` 内 `:333` 重试裁决），状态归属不变：`terminalFailureGuard` 是实例字段（`AudioCapture.kt:105`，跨轮才攒得满证据），`zeroGuard` 是 readLoop 局部（`:183`）。

**测试样例（真实判定器、零 mock，`AudioCaptureWiringSeamTest.kt:48-53`）：**

```kotlin
@Test
fun `capture start outcome - three consecutive failures fire exactly once on the third`() {
    val guard = TerminalStartFailureDetector()
    assertFalse(onCaptureStartOutcome(guard, startedOk = false))
    assertFalse(onCaptureStartOutcome(guard, startedOk = false))
    assertTrue(onCaptureStartOutcome(guard, startedOk = false))  // 第三次恰一次 fire
}
```

配套 8 测钉死：连败三次恰一次 fire / 败败成复位后重计满（跨段不凑数）/ 孤成不 fire / 1→RETRY 2→RETRY 3→GIVE_UP（`:26-30`）/ 达上限含等值边界 ge 非 gt（`:33-36`）/ 三常数钉死（3 / 250L / 30，`:39-43`）/ 29 块不触发 30 块触发 / 非零打断重计。

**注释改诚实口径（三层声明，`AudioCapture.kt:190-195` 同款）**：「接线裁决序列由 AudioCaptureWiringSeamTest 钉死；判定本体在 XxxDetector，纯逻辑 JVM 可测可变异；readLoop 调用点属 Android 域 JVM 不可达，由审查+真机取证兜底。」——不声称测试覆盖不了的东西。

**验证协议**：seam 落地后变异锚必须实红再还原（本例 3 条：重试上限比较 `>=`→`>`、删成功复位分支、归零转发恒 false；还原后 md5 对拍零残留），全套件 800/0/0/0（本会话评审记录）。

## Why This Matters

**不遵守的后果 = 绿色认证与关键链路脱钩。** 本案例中 792 个绿测给出的认证信号与真实代码状态完全相反：自愈链整条拔线（僵尸 input 场景下进程不自尽、采麦永久死流，恰是 HOTFIX-2 要兜的病灶）而 CI 全绿。更糟的是守卫注释本身成了**反向文档**——评审者读到「删此 → X 红」会跳过核查（「反正有测试锚着」），假锚比没锚更降低发现概率。与 [[unanchored-code-silent-loss-in-rebuild]] 的差异在于：那篇的丢失发生在事故重建的非常态，这篇的脱钩发生在**正常开发态**——任何人一次顺手重构就能静默剪断接线，且所有既有信号都说是安全的。

**遵守的收益**：①「删接线」从静默变为必红（比较翻转、删分支、转发恒假三类变异都有对应锚组）；②裁决的边界语义（恰一次 fire、fire 即复位、达上限含边界、非零打断重计）第一次有了可变异的宿主——此前这些语义只存在于注释和评审者的脑子里；③守卫注释从许诺变成可审计事实：三层口径每层对应明确的验证手段（seam 测 / 判定器测 / 审查+真机），评审可逐层查证。

## When to Apply

- **JVM-only 单测仓，调用点属 Android 域**（Service/AudioRecord/View 生命周期内），判定逻辑已下沉为纯类——本案例形态。仓禁 Robolectric/Mockito/MockK 时尤其如此：没有「顺手整个 instrumented test」的退路，seam 是唯一把接线钉进套件的手段。
- **任何点名测试作锚的守卫注释**：下笔前审计该测试的构造对象能否观测到被守卫行；不能，就先 seam 再写注释。
- **移植/重建战役**：逻辑跨层搬动时最容易「类到了、接线没收编」，恰是 [[unanchored-code-silent-loss-in-rebuild]] 盲区家族在接线层的再现。
- **不适用**：调用点已有真实 instrumented/Robolectric 测试驱动（锚就在那里，不必 seam）；调用点与判定合一、无独立接线语义的琐碎一行（seam 比逻辑还宽，纯属仪式）；纯数据转发且调用点删除会立刻被上层行为测试暴露的链路（先证明真会红再决定要不要额外锚）。

## Examples

**Before（修复前，假锚，本会话评审记录）**——readLoop 计数点注释：

```kotlin
// 自愈重启钩回归守卫：轮级终态失败计数
// （删此 → AudioCaptureTerminalStartFailureDetectorTest 连败触发组红…）
if (terminalFailureGuard.onTerminalFailure()) { ... }
```

而该测试全文只有 `val d = TerminalStartFailureDetector()` 式直驱（`AudioCaptureTerminalStartFailureDetectorTest.kt:29`），从未触碰这行。评审变异把 `onTerminalFailure` 调用改 `if (false)`、删 `reset()`、重试上限 `>=`→`>= 1`，6 轮全绿（792 不动）——注释许诺的红在测试拓扑上不存在。

**After（HEAD=35f5a0d 实况）**——同一裁决点，seam + 真锚 + 诚实注释：

```kotlin
// AudioCapture.kt:190-196（节选）
// 自愈重启钩回归守卫：轮级终态失败计数，统一走 [onCaptureStartOutcome]
// seam（接线裁决序列由 AudioCaptureWiringSeamTest 钉死；判定本体在
// [TerminalStartFailureDetector]，纯逻辑 JVM 可测可变异；readLoop 调用点
// 属 Android 域 JVM 不可达，由审查+真机取证兜底）。…
if (onCaptureStartOutcome(terminalFailureGuard, startedOk = false)) { ... }
```

裁决逻辑进了 `onCaptureStartOutcome`（`AudioCapture.kt:527-533`），`AudioCaptureWiringSeamTest` 8 测直接驱动 seam——评审复核确认删除成功复位分支、翻转重试边界、归零转发恒假均实红，还原后 md5 对拍零残留（本会话评审记录）。

**诚实边界（残留，勿过度外推）**：① seam 钉的是**裁决序列语义**，不是调用点存在性——readLoop 里 `onCaptureStartOutcome`/`onZeroBlockOutcome` 调用行本身被删仍 JVM 不可达，由守卫注释（如实版）+人工审查+真机取证兜底；要彻底钉死需 record 工厂 seam（构造/释放计数使调用点删除可观测），留待后续票。② 同文件的 SoftwareAgc 调用点（`AudioCapture.kt:254`）本轮未 seam 化，其守卫注释（`:246-253`「删此 process 调用 → SoftwareAgcTest 远场收敛组红」）仍是判定器直测口径——按本文规则该锚同样够不到调用行，属同类残留，后续票应一并收敛。③ merge 状态再说一遍：分支 port/baseline-4cap-upstream-47b240a，2026-10-21 修复轮落地（修复 commit 0a7c268，撰写时 HEAD=35f5a0d），评审通过前未合 main。

## Related

- [[unanchored-code-silent-loss-in-rebuild]] — 母篇：无锚常量/分支在事故恢复中静默丢失（阈值 0.5 回退 + 云端分支双案）；其「变异锚=分支删除态必须红」与「如果这个文件明天被重写，哪个测试会红？」自检是本文的规则源头，本文补上「注释假锚」这一接线层变体。
- [[kwsengine-release-native-use-after-free]] — 解法先例：native 边界 seam 化下沉 JVM 可测 + fake-native + 变异锚实测同 playbook；其 Prevention「没做过变异实测的回归守卫只是注释」由本文推进一步——实测对象必须是接线本身，实测全绿本身就是假锚暴露信号。
- [[delta-callback-check-then-act-ghost-bubble]] — 「删此 → 测试名 → 红」守卫注释纪律的本仓出处（S8 起确立）；本文为该纪律补前置条件：注释命名的测试必须真实经过被守卫的那行接线。
- [[aec-spike-null-evidence-feed-verification]] — 「空证据」平行案：验证貌似完成但从未作用于被验证对象（spike 从未喂流却结论「零误触发」≈ 判定器直测从未过接线却结论「有守卫」）。
