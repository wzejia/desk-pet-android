package com.xiaoduo.deskpet.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ROB-10：SessionDrawer 搜索 producer 兜底逻辑件。
 * 搜索 IO 异常降级为空结果（抽屉显示「无匹配会话」），不撕毁整个组合；
 * CancellationException 穿透 rethrow（结构化取消语义）。
 */
class SessionSearchGuardTest {

    @Test
    fun `normal search result passes through`() = runBlocking {
        assertEquals(listOf("a"), searchCatching("q", { listOf("a") }))
    }

    @Test
    fun `io exception degrades to empty list and reports via sink`() = runBlocking {
        var reported: Throwable? = null
        val r: List<String> = searchCatching("q", { throw java.io.IOException("disk unreadable") }) {
            reported = it
        }
        assertEquals(emptyList<String>(), r)
        assertEquals("disk unreadable", (reported as java.io.IOException).message)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation rethrown`(): Unit = runBlocking {
        searchCatching<String>("q", { throw CancellationException("producer cancelled") })
    }
}
