package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionRules.currentAfterDelete：删除会话后的当前会话回落规则。 */
class SessionRulesTest {

    private val s1 = ChatSession("s1", "一", 100L, 100L)
    private val s2 = ChatSession("s2", "二", 200L, 200L)
    private val s3 = ChatSession("s3", "三", 300L, 300L)
    private val all = listOf(s1, s2, s3)

    @Test
    fun deleteNonCurrentKeepsCurrent() {
        assertEquals("s1", SessionRules.currentAfterDelete(all, "s2", "s1"))
    }

    @Test
    fun deleteCurrentFallsBackToMostRecentlyUpdated() {
        assertEquals("s2", SessionRules.currentAfterDelete(all, "s3", "s3"))
    }

    @Test
    fun deleteCurrentWithNoneRemainingYieldsNull() {
        assertNull(SessionRules.currentAfterDelete(listOf(s3), "s3", "s3"))
    }

    @Test
    fun nullCurrentStaysNull() {
        assertNull(SessionRules.currentAfterDelete(all, "s2", null))
    }

    // ===== S48 惰性超时轮转判定 =====

    @Test
    fun idleBeyondTimeoutRotates() {
        val now = 10 * 60 * 1000L + 1
        assertTrue(SessionRules.shouldRotateForIdle(lastMessageTs = 0L, now = now))
    }

    @Test
    fun idleWithinTimeoutKeepsSession() {
        val now = 10 * 60 * 1000L
        assertFalse(SessionRules.shouldRotateForIdle(lastMessageTs = 0L, now = now)) // 恰好阈值不转
    }

    @Test
    fun emptySessionNeverRotates() {
        // 空会话（无消息）永不触发——杜绝空会话再次轮转出空会话的循环
        assertFalse(SessionRules.shouldRotateForIdle(lastMessageTs = null, now = Long.MAX_VALUE / 2))
    }

    @Test
    fun customTimeoutHonored() {
        assertTrue(SessionRules.shouldRotateForIdle(lastMessageTs = 0L, now = 5_000L, timeoutMs = 4_999L))
        assertFalse(SessionRules.shouldRotateForIdle(lastMessageTs = 0L, now = 5_000L, timeoutMs = 5_000L))
    }
}
