package com.xiaoduo.deskpet.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROB-13：ui 层协程兜底逻辑件（SettingsScreen 三处 scope.launch / SessionDrawer 搜索
 * producer / ChatScreen 上传协程共用）。钉住三条语义：
 * 成功值原样通过 / 非取消异常（含 Error）收敛为 Failed 不逸出 / CancellationException rethrow。
 */
class UiCoroutineGuardTest {

    @Test
    fun `value passes through as Ok`() = runBlocking {
        val o = guardedUi { 42 }
        assertEquals(UiOutcome.Ok(42), o)
    }

    @Test
    fun `exception wrapped as Failed not rethrown`() = runBlocking {
        val boom = IllegalStateException("disk on fire")
        val o = guardedUi { throw boom }
        assertTrue(o is UiOutcome.Failed)
        assertEquals(boom, (o as UiOutcome.Failed).error)
    }

    @Test
    fun `error also wrapped as Failed`() = runBlocking {
        val o = guardedUi { throw OutOfMemoryError("big file") }
        assertTrue(o is UiOutcome.Failed)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation rethrown not swallowed`(): Unit = runBlocking {
        guardedUi<Unit> { throw CancellationException("navigation away") }
    }
}
