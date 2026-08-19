---
title: KwsEngine.release() use-after-free SIGSEGV——interrupt≠线程退出，native 句柄释放必须 join 证实；native 边界 seam 化下沉 JVM 可测
date: 2026-08-06
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/voice/KwsCore.kt
problem_type: logic_error
component: assistant
symptoms:
  - "关闭唤醒词开关偶发闪退，无 Java 堆栈"
  - "tombstone 只有 SIGSEGV，崩溃点在 sherpa-onnx native 内"
  - "每天启停多次 KWS 前台服务即每天掷一次竞态骰子"
  - "审计推演发现，非现场抓获（无自动化测试可复现）"
root_cause: thread_violation
resolution_type: code_fix
severity: high
tags: [use-after-free, sigsegv, jni-boundary, thread-join, interrupt-not-exit, test-seam, fake-native, mutation-anchor, kws, sherpa-onnx]
---

# KwsEngine.release() use-after-free SIGSEGV + native 边界测试缝下沉

## Problem

旧 KwsEngine（修复前 `git show b1259b0^:app/src/main/java/com/xiaoduo/deskpet/voice/KwsEngine.kt`）的 `release()` 只做三件事：置 `running=false`、`decodeThread.interrupt()`、然后**立即** `stream.release()` + `spotter.release()`。decode 线程的消费循环里 `spotter.decode(stream)` 是 JNI 调用——interrupt 只能唤醒 `queue.take()` 的空转等待，**对已在 native 内的 in-flight decode 毫无作用**。时序窗口：release 把 native 句柄 free 掉的同时，decode 线程仍在 `spotter.decode(stream)` 里读写已释放内存 → use-after-free → SIGSEGV。KWS 前台服务每天随开关启停多次，每次都掷一次这颗骰子。

## Symptoms

- 「关唤醒词偶发闪退」：无 Java 堆栈，tombstone 仅 SIGSEGV，栈在 sherpa-onnx native 内。
- 概率性、不可稳定复现——崩溃与否取决于 interrupt 到达时 decode 线程恰好站在哪一行。
- 本项目系审计推演发现（commit b1259b0 提交信息：「审计 P1」），非现场抓获；此前无任何自动化测试覆盖这条路径。

## What Didn't Work

**结构盲区使竞态无法被测出。** 旧类构造器第一行就是 `KeywordSpotter(assetManager, ...)`——sherpa-onnx 只提供 arm64 native 库，JVM 上**连实例化 KwsEngine 都不可能**，遑论写并发时序测试。于是门控/队列/消费线程/release 时序全部逻辑零自动化测试，竞态藏在「无法测」的盲区里（KwsCoreTest.kt:14-19 的类注释即对此背景的交代）。

**Java 异常机制接不住 native 崩溃。** 旧 decodeLoop 虽有 `catch (e: Exception)` 丢块续跑，但 use-after-free 是 SIGSEGV，绕过整个 Java 异常体系直接杀进程，任何 catch/finally 都无机会执行。指望「崩溃时有日志」在本类缺陷上不成立。

**interrupt 被误当作「停止线程」。** `Thread.interrupt()` 的语义只是「置中断位 + 打断可中断的阻塞调用」；对 JNI 内执行的代码既不可观测也不可强制。把 interrupt 当作 free 的前置条件，是本缺陷的直接成因。

**该模块的生命周期缺陷史早有预警（session history）。** 07-31 KwsEngine 初建期，critic 评审连续抓出三个同域生命周期缺陷（onnx 加载窗口麦泄漏 P1 6abfec8、双 init 堵载 d48caee、stale release 令牌 6712299）——**全部漏过 builder 自测与当时的 243+ JVM 单测**，防御手段一直只有 critic 的交错推演，没有可自动重放的并发回归测试。当时未建 seam 把线程/门控/release 逻辑下沉纯 JVM——本次 KwsCore.Native seam 修复补的正是那个缺口。

## Solution

两层修复，分两个 commit 落地。

**① 时序层（b1259b0）：join 证实退出后才 free。** 新 `KwsCore.release()`（KwsCore.kt:141-155）：

```kotlin
fun release() {
    // 回归守卫：CAS 单次放行（删此 → 并发/重复 release 双 free → KwsCoreTest 红）
    if (!released.compareAndSet(false, true)) return
    running = false
    decodeThread.interrupt()
    // 回归守卫：join（删此 → free 抢在 in-flight decode 前 → KwsCoreTest 红）
    decodeThread.join(JOIN_TIMEOUT_MS)              // 2s，单次 decode 毫秒级，极宽
    if (decodeThread.isAlive) {
        logger("release: decode thread stuck after ${JOIN_TIMEOUT_MS}ms, skip native free (leak over crash)")
        return                                       // 超时：宁可泄漏句柄也不硬 free
    }
    runCatching { native.release() }
        .onFailure { logger("native free failed: ${it.message}") }
    logger(statsLine("released,"))
}
```

- **CAS 单次放行**：并发/重复 release 只 free 一次（旧实现 `if (!running) return` 在并发下可双 free）。
- **join(2s) 后才 free**：free 的前置条件从「发了 interrupt」改为「线程已证实死亡」。interrupt 的职责退化为只负责把空转在 `queue.take()` 的线程尽快唤醒。
- **超时分支 leak-over-crash**：native 真卡死时不 free、落日志、放行——崩溃规避 > 内存泄漏，进程退出时 OS 回收。且 skip 之后**永不补 free**（free 责任随 skip 放弃），由 stuck skip-free 测试钉住。

**② 结构层（b1259b0）：抽 Native seam，逻辑下沉 JVM。** native 边界抽象为六方法接口 `KwsCore.Native`（KwsCore.kt:34-41：acceptWaveform/isReady/decode/resultKeyword/reset/release），全部线程/门控/统计/release 时序逻辑移入纯 JVM 零 Android import 的 KwsCore；KwsEngine 瘦身为薄适配——`SherpaNative`（KwsEngine.kt:54-76）只做 sherpa 真身的直通转发，是唯一不可 JVM 测的部分，由真机取证兜底。签名不变，VoiceForegroundService 零改动。KwsCoreTest 11 测，关键两测 + 一条假身纪律：

- **release 竞态测**：双 latch 编排——`decodeGate` 把假身 decode 钉在「native 内」复刻竞态窗，release 在另一线程发起；断言 200ms 抢跑窗口内 `free` 绝不得出现在事件序列，开闸后断言 `free` 的下标必须大于 `decode-exit`。
- **stuck skip-free 测**：闸门永不开 → release 阻塞 ~2s 返回、`freeCount=0`、落 skip 日志；finally 开闸后再等 300ms，断言不得补 free。
- **awaitUninterruptibly 假身**：吞 InterruptedException 循环等闸门，且检查 `await` 返回值（超时不算开闸）。**第一版假身用可中断 latch，被 release 的 interrupt 提前唤醒，测出假阳性红**（当日 session 实录，非树内可考）——假身必须与 JNI 语义同构：native 解码不响应线程中断。

**review 加固（17f21c0）：release 期命中不派发。** ce-code-review 发现残留窗：命中在 release 进行中解码出来时，旧路径照派 `onKeyword` → 已毁服务幻影唤醒 startListening。修复在 decodeLoop 命中分支加 `running` 复查（KwsCore.kt:97-103）：release 进行中命中只记日志 `keyword hit dropped (release in progress)`、reset、不派发。配套 skip-free 补测、free 失败 onFailure 日志（纪律 5：不静默吞）等四修复，共新增 6 测（KwsCoreTest+3 / 姊妹票 IdleCaptureTest+3），变异锚实测（删两守卫各红）。

## Why This Works

native 资源释放的合法前置条件只有一个：**消费线程已证实退出 JNI 边界**。interrupt 是「请求」，join 才是「证实」——`join` 返回且 `!isAlive` 是 JVM 层唯一能给出的「该线程不会再触碰 native 内存」的证明。超时分支把这个证明的失败模式显式化：证实不了就放弃 free（泄漏可观测、可记账、进程退出可回收），绝不退而求其次赌一把（SIGSEGV 不可恢复、无堆栈、伤用户）。

seam 下沉则把「无法测」本身修掉：竞态之所以能从写代码活到审计，根因是整类绑死 arm64 native 导致并发逻辑零自动化测试（session history：初建期三个生命周期 bug 全靠 critic 推演补位，缺的就是这条 seam）。抽出 `Native` 接口后，时序逻辑全部在 JVM 上可用假身精确编排（双 latch 确定性复现竞态窗），且每条守卫都有变异锚——删 join、删 CAS、删 running 复查，各对应一条测试变红，守卫的注释里直接写明「删此 → 红」。

## Prevention

- **模式**：native 边界一律 seam 化——薄适配接口 + 逻辑下沉 JVM 可测层。凡「这个类没法在 JVM 上实例化」的模块，其并发/生命周期逻辑视同未测，审计时优先怀疑。
- **假身纪律**：Fake 必须与 JNI 语义同构。native 调用不响应 interrupt，假身的等待就必须 `awaitUninterruptibly` 并检查返回值；用可中断 latch 会被被测代码的 interrupt 提前唤醒，测出假阳性红（本项目实测踩过，第一版假身即栽于此）。
- **变异锚实测**：每条并发守卫写「删此 → 红」注释并实际删除验证一次（b1259b0/17f21c0 均实测后还原复绿）。没做过变异实测的「回归守卫」只是注释。
- **真机踩骰子**：native 竞态的最终取证在真机——设置页开关 KWS 循环 N 次 + 查 `/data/tombstones` 无新 SIGSEGV；辅以 release 汇总日志行（`released, fed=...`）确认时序路径走到了。
- **同类排查口令**：凡见 `interrupt()` 后紧跟资源释放/连接关闭/句柄 free，一律追问「join 了吗」。

## Related Issues

- [对话中销毁 KWS 服务 → 麦克风孤儿空采](kws-idle-capture-orphan-audiorecord.md)——同销毁链相邻环、同会话沉淀的姊妹 P1；同为「跨生命周期资源在调用点条件早退」族缺陷
- [delta 回调 check-then-act 竞态窗](delta-callback-check-then-act-ghost-bubble.md)——同族异步守卫缺陷（检查与副作用错位）；竞态确定性测试手法同族
- 库外背景：`docs/research-sherpa-onnx-android-2026-07-28.md`（sherpa-onnx Android 使用姿势预研，native API 语义来源）
