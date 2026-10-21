package com.xiaoduo.deskpet.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOTFIX-2 回归守卫（纯逻辑面，JVM 可测）：
 * - [isAllZeroPcm16]：精确全零口径——全 00 字节 ⇔ PCM16 全零样本；任一非 00 字节
 *   （+1 / -1 / -32768，含首尾位）即非全零；空数组恒真口径钉死。
 * - [ZeroBlockDetector]：29 块不触发 / 30 块触发（票据点名锚）/ 中间非零归零重新计数 /
 *   触发后须重新计满（自愈无硬上限语义）/ 阈值常数钉死 30（=3.0s）。
 * - pumpBlocks stopWhen（自愈的 JVM 可达半边）：置位即正常返回、read 不再被调；
 *   不传 stopWhen 的旧行为由既有 AudioCapturePumpTest 全组钉死零改动。
 *
 * 变异方向：删守卫（onBlock 恒 false）→ 触发组红；改阈值 → 计数组红；
 * 删非零归零 → 归零组红；删触发后归零 → 再触发组红；删 pumpBlocks stopWhen 检查 → 泵级锚红。
 */
class AudioCaptureZeroBlockDetectorTest {

    /** 产线口径块：3200B = 16kHz/Int16/mono × 100ms（VadDetector.BLOCK_BYTES）。 */
    private fun fullBlock() = ByteArray(VadDetector.BLOCK_BYTES)

    // ---------------- isAllZeroPcm16：精确全零口径 ----------------

    @Test
    fun `all zero 3200B block is exact zero`() {
        assertTrue(isAllZeroPcm16(fullBlock()))
    }

    @Test
    fun `any non-zero byte breaks exact zero including pcm16 extremes`() {
        val head = fullBlock().also { it[0] = 0x01 }                       // 样本 +1
        val tail = fullBlock().also { it[it.size - 1] = 0x01 }             // 尾位翻起
        val minus1 = fullBlock().also { it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte() } // 样本 -1
        val min = fullBlock().also { it[0] = 0x00; it[1] = 0x80.toByte() } // 样本 -32768
        assertFalse(isAllZeroPcm16(head))
        assertFalse(isAllZeroPcm16(tail))
        assertFalse(isAllZeroPcm16(minus1))
        assertFalse(isAllZeroPcm16(min))
    }

    /** 空数组口径钉死：全称量词恒真（产线块恒 3200B 定长，仅理论边界）。 */
    @Test
    fun `empty block pins vacuous-zero contract`() {
        assertTrue(isAllZeroPcm16(ByteArray(0)))
    }

    // ---------------- ZeroBlockDetector：阈值与连续性 ----------------

    /** 票据点名锚：29 块全零不触发，第 30 块触发（3.0s）。 */
    @Test
    fun `29 all-zero blocks hold and the 30th triggers self-heal`() {
        val d = ZeroBlockDetector()
        repeat(29) { i -> assertFalse("第 ${i + 1} 块不得触发", d.onBlock(true)) }
        assertTrue("第 30 块必须触发", d.onBlock(true))
    }

    /** 票据点名锚：中间插入非零块归零重新计数——15 零 + 1 非零 + 29 零不触发，重数第 30 块才触发。 */
    @Test
    fun `non-zero block resets the streak and recount starts from zero`() {
        val d = ZeroBlockDetector()
        repeat(15) { assertFalse("前 15 块未达阈不得触发", d.onBlock(true)) }
        assertFalse(d.onBlock(false)) // 非零块：归零
        repeat(29) { assertFalse("归零后重数期间不得触发", d.onBlock(true)) }
        assertTrue("重数后第 30 块才触发", d.onBlock(true))
    }

    /** 触发即归零：自愈重建后新流须重新计满阈值才再触发；循环多轮=自愈次数无硬上限语义。 */
    @Test
    fun `after each trigger the detector requires a fresh full streak repeatedly`() {
        val d = ZeroBlockDetector()
        repeat(5) { round ->
            repeat(29) { assertFalse("round=$round 重数期不得提前触发", d.onBlock(true)) }
            assertTrue("round=$round 第 30 块必须再次触发", d.onBlock(true))
        }
    }

    /** 阈值常数钉死：30 块 × 100ms = 3.0s（改阈值即红）。 */
    @Test
    fun `threshold constant pins 30 blocks equal to 3 seconds`() {
        assertEquals(30, ZERO_BLOCK_SELF_HEAL_THRESHOLD)
        assertEquals(3000, ZERO_BLOCK_SELF_HEAL_THRESHOLD * VadDetector.BLOCK_MS)
    }

    // ---------------- pumpBlocks stopWhen：自愈停泵出口（泵级锚） ----------------

    /**
     * 30 全零块喂入：第 30 块 emit 时判定器置 stop → 泵经 stopWhen 正常返回（非取消），
     * read 恰好每块一次、停后不得再被调（旧流已死，等 release 重建）。
     */
    @Test
    fun `pumpBlocks returns via stopWhen after the trigger block and never reads again`() = runBlocking {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        var reads = 0
        var emitted = 0
        var stop = false
        val d = ZeroBlockDetector()
        val job = launch(Dispatchers.Unconfined) {
            pumpBlocks(
                read = { buf, off, _ ->
                    reads++
                    val chunk = inbox.receive()
                    chunk.copyInto(buf, off)
                    chunk.size
                },
                emit = { block ->
                    if (d.onBlock(isAllZeroPcm16(block))) stop = true
                    emitted++
                },
                stopWhen = { stop },
            )
        }
        repeat(30) { inbox.send(fullBlock()) }
        awaitJobDone(job)

        assertEquals("30 块全喂入并 emit（含触发块）", 30, emitted)
        assertEquals("每个整块恰一次 read", 30, reads)
        assertFalse("须经 stopWhen 正常返回，而非被取消", job.isCancelled)

        repeat(5) { inbox.send(fullBlock()) } // 停泵后来块不得再被读
        repeat(20) { yield() }
        assertEquals("stop 后 read 不得再被调", 30, reads)
    }

    /** 有界等待（Unconfined 调度差异兜底，与既有 AudioCapturePumpTest 同策）。 */
    private suspend fun awaitJobDone(job: Job) {
        var spins = 0
        while (!job.isCompleted && spins < 1000) {
            yield()
            spins++
        }
    }
}
