package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
