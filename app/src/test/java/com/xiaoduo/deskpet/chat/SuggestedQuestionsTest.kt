package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** SuggestedQuestions：建议问句池规模/去重/抽批与「换一换」避让规则。 */
class SuggestedQuestionsTest {

    @Test
    fun poolHasAtLeastSixDistinctNonBlankEntries() {
        assertTrue(SuggestedQuestions.POOL.size >= 6)
        assertEquals(SuggestedQuestions.POOL.size, SuggestedQuestions.POOL.distinct().size)
        assertTrue(SuggestedQuestions.POOL.all { it.isNotBlank() })
    }

    @Test
    fun pickReturnsDistinctItemsFromPool() {
        val batch = SuggestedQuestions.pick(3, random = Random(42))
        assertEquals(3, batch.size)
        assertEquals(batch.size, batch.distinct().size)
        assertTrue(SuggestedQuestions.POOL.containsAll(batch))
    }

    @Test
    fun pickIsDeterministicWithSeed() {
        assertEquals(
            SuggestedQuestions.pick(3, random = Random(7)),
            SuggestedQuestions.pick(3, random = Random(7)),
        )
    }

    @Test
    fun pickAvoidsExcludedBatchWhenAlternativesSuffice() {
        val first = SuggestedQuestions.pick(3, random = Random(1))
        val second = SuggestedQuestions.pick(3, exclude = first, random = Random(2))
        assertEquals(3, second.size)
        assertTrue(second.none { it in first })
    }

    @Test
    fun pickFallsBackToFullPoolWhenExcludeLeavesTooFew() {
        val almostAll = SuggestedQuestions.POOL.take(SuggestedQuestions.POOL.size - 1)
        val batch = SuggestedQuestions.pick(3, exclude = almostAll, random = Random(3))
        assertEquals(3, batch.size)
        assertTrue(SuggestedQuestions.POOL.containsAll(batch))
    }

    @Test
    fun pickZeroReturnsEmpty() {
        assertTrue(SuggestedQuestions.pick(0).isEmpty())
    }
}
