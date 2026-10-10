package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.policy.BubbleHidePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * BubbleHidePolicy 并发守卫单测（ocr[5]：裸 var 无 happens-before + read-then-clear 非原子）。
 *
 * 守卫：pendingHide 改 AtomicBoolean——「读取即复位」用 getAndSet 原子化，并发回 IDLE
 * 转移下**恰好一方**消费到 true（防重复调度收泡），且 show 写（pipeline IO 线程）对
 * transition 读线程可见性由原子类保证。
 *
 * 变异锚：回退裸 var（val hide = flag; flag = false）→ 用例 1 红：双线程可同读 true
 * 各消费一次（count==2）。可见性半面（无 jcstress 不做概率锁测）由 AtomicBoolean
 * API 契约承担，此处以「恰好一次」确定性交错为锚。
 */
class BubbleHidePolicyConcurrencyTest {

    @Test
    fun `并发回 IDLE 消费恰好一方收到 true`() {
        repeat(100) {
            val policy = BubbleHidePolicy()
            policy.onBubbleShown()
            val consumers = 8 // 高并行压测：加大裸 var「双读后写」窗口命中概率（压力锚）
            val start = CountDownLatch(1)
            val results = arrayOfNulls<Boolean>(consumers)
            val threads = (0 until consumers).map { i ->
                thread { start.await(); results[i] = policy.onReturnToIdle() }
            }
            start.countDown()
            threads.forEach { it.join(5_000) }
            assertEquals(
                "恰好一次消费（变异锚：裸 var read-then-clear 可多读 true）",
                1,
                results.filterNotNull().count { it },
            )
        }
    }

    @Test
    fun `消费语义零变化——show 后恰一次 true，复位后为 false`() {
        val policy = BubbleHidePolicy()
        assertFalse("未 show 不收", policy.onReturnToIdle())
        policy.onBubbleShown()
        assertTrue(policy.onReturnToIdle())
        assertFalse("消费后复位，重复 IDLE 不重复收", policy.onReturnToIdle())
    }
}
