package com.xiaoduo.deskpet.ui.screens

import kotlinx.coroutines.CancellationException

/**
 * ROB-13：ui 层协程兜底逻辑件。异常收敛为值：非取消异常包进 [UiOutcome.Failed] 不逸出
 * 协程（防沿 rememberCoroutineScope 顶穿崩溃 + loading/running 标志卡 true 按钮永久禁用）；
 * [CancellationException] 原样 rethrow（协程取消语义，导航离开静默取消是正常路径，吞掉会
 * 破坏结构化取消）。纯 JVM 可测（UiCoroutineGuardTest，runBlocking 驱动）。
 */
internal sealed interface UiOutcome<out T> {
    data class Ok<out T>(val value: T) : UiOutcome<T>
    data class Failed(val error: Throwable) : UiOutcome<Nothing>
}

internal suspend fun <T> guardedUi(run: suspend () -> T): UiOutcome<T> =
    try {
        UiOutcome.Ok(run())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        UiOutcome.Failed(t)
    }
