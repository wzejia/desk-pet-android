package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.VoiceRoundRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * VoiceRoundRegistry 单测（ocr[49]：voiceRound claim-then-use 非原子的纯 JVM 守卫）。
 *
 * 不变式：current() 恒等于**最后完成的 claim** 的返回值——claim（含 show 副作用）与
 * read 在同一监视器上串行化。
 *
 * 变异锚：删 claim/current 的 synchronized（回退 @Volatile 裸读写）→ 用例 1 红：
 * reader 在 claim 在途期间带着旧值 0 返回（claim 的 show 被主线程 latch 卡住）。
 */
class VoiceRoundRegistryTest {

    @Test
    fun `claim 在途时 read 不得带旧值返回——串行化不变式`() {
        repeat(10) { // 冗余面：确定性交错为主，多轮只防偶发调度抖动
            val registry = VoiceRoundRegistry()
            val claimInside = CountDownLatch(1) // claimer 已进 claim（有锁形态=已持锁）
            val releaseShow = CountDownLatch(1)
            var observed = -1
            val claimer = thread {
                // 模拟 svc.showBubble 慢副作用：show 未返回前 claim 未完成
                registry.claim {
                    claimInside.countDown()
                    releaseShow.await()
                    42
                }
            }
            claimInside.await() // 保证 claimer 已进入 claim（锁内/副作用中），消除「reader 先拿锁」的合法交错
            val readerReturned = CountDownLatch(1)
            val reader = thread {
                observed = registry.current()
                readerReturned.countDown()
            }
            Thread.sleep(100) // 有锁：reader 停等在监视器上；无锁（变异体）：read 已带 0 返回
            assertEquals("claim 在途时 read 不得提前返回", 1, readerReturned.count)
            releaseShow.countDown()
            claimer.join(5_000)
            reader.join(5_000)
            assertEquals("read 恒见最后完成的 claim 值（ocr[49]）", 42, observed)
        }
    }

    @Test
    fun `claim 登记 show 返回值且 current 随最近 claim 走`() {
        val registry = VoiceRoundRegistry()
        assertEquals(7, registry.claim { 7 })
        assertEquals(7, registry.current())
        assertEquals(9, registry.claim { 9 })
        assertEquals(9, registry.current())
    }
}
