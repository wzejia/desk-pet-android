package com.xiaoduo.deskpet.voice.audio

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * VoicePlayer 族健壮性批（spec §5.2 / ocr[424..429]，2026-10-10 P3-T1）：
 * - [424] stopped 永不复位 → 实例复用永久 IOException，reset 重建恢复流失效；
 * - [425] refPacerRunning 异常退出 → 已由 CONC-4 修复（refPacerLoop try/finally），
 *   VoicePlayerRefPacerTest 既有 3 测锚定，本票不重复施工（处置表记录）；
 * - [426] join(200) 超时窗 → 陈旧 pacer 复活双跑（代际票根自毁）；
 * - [427] 0 字节写当成功（off 不进死旋）+ play/write 的 ISE 逃出 IOException 降级契约；
 * - [428] 空 PCM/负 minBuf 直进 setBufferSizeInBytes(0) → IAE 逃逸降级路径；
 * - [429] drainUntil 墙钟死线 + 播放头 2^31 回卷后裸比较恒「未追平」。
 *
 * VoicePlayer 本体 Android IO 层（JVM 不可载 AudioTrack），守卫逻辑抽纯 JVM 函数/类
 * 逐条变异锚（删守卫实测红）；接线处（releaseStream 清门 / startRefPacer 领票 /
 * drainUntil 换钟）以 isStoppedForTest 缝 + 行为测试覆盖到 JVM 可达边界。
 */
class VoicePlayerRobustnessTest {

    // ---------------- ocr[424] 打断位必须随会话拆除复位 ----------------

    @Test
    fun `interrupt gate clears on session teardown so instance reuse recovers`() {
        // 变异锚：InterruptGate.clear() 回退 no-op（旧行为 stopped 永不复位）→ 本测试红。
        val gate = InterruptGate()
        gate.stop()
        assertTrue(gate.isStopped())
        gate.clear()
        assertFalse("会话显式拆除（reset/finish）后打断位必须复位（reset 后下句重建的文档化恢复流）", gate.isStopped())
    }

    @Test
    fun `gate check throws exactly the documented stopped IOException`() {
        val gate = InterruptGate()
        gate.checkActiveOrThrow() // 未置位不抛
        gate.stop()
        try {
            gate.checkActiveOrThrow()
            fail("置位后写循环查门必须抛 IOException(stopped)")
        } catch (e: IOException) {
            assertEquals("stopped", e.message)
        }
    }

    @Test
    fun `voice player reset clears interrupt flag for next session`() {
        // 接线锚：releaseStream 不清门（旧实现）→ reset 后 isStoppedForTest 仍 true → 红。
        val vp = VoicePlayer()
        vp.stop()
        assertTrue(vp.isStoppedForTest())
        vp.reset()
        assertFalse("reset() 后下句重建必须可开播（ocr[424] 恢复流）", vp.isStoppedForTest())
    }

    @Test
    fun `play after stop and reset no longer fails with stopped IOException`() {
        // 行为级锚（JVM 可达边界）：play() 建轨前的 stopped 检查放行后，第一个 Android
        // 调用（getMinBufferSize）在 JVM 桩上抛非 IOException——只要不再是
        // IOException("stopped") 即证明打断位已随 reset 复位（ocr[424]）。
        val vp = VoicePlayer()
        vp.stop()
        vp.reset()
        try {
            vp.play(ByteArray(100))
            // 理论不可达（JVM 桩必炸）；真机上这里就是正常开播
        } catch (e: IOException) {
            fail("reset 后 play 不得再抛 stopped IOException（旧实现永久复抛 → 此红）: ${e.message}")
        } catch (e: Throwable) {
            // JVM 桩的 not-mocked RuntimeException / 真机正常路径：均证明已过 stopped 检查
        }
    }

    // ---------------- ocr[426] 陈旧 pacer 代际票根自毁 ----------------

    @Test
    fun `stale pacer generation is invalidated and never resurrects`() {
        // 变异锚：PacerGeneration.isValid 恒 true（旧行为：无代际概念，陈旧 pacer 随
        // refPacerRunning 复活）→ invalidate 断言红。
        val gen = PacerGeneration()
        val stale = gen.issue()
        assertTrue("新领的票有效", gen.isValid(stale))
        gen.invalidate() // stopRefPacer 作废在途票
        assertFalse("join 超时残活的陈旧 pacer 必须自毁（不与新会话双跑推参考）", gen.isValid(stale))
        val fresh = gen.issue() // 新会话 startRefPacer 领新票
        assertTrue("新票有效", gen.isValid(fresh))
        assertFalse("更陈的票保持作废", gen.isValid(stale))
    }

    // ---------------- ocr[427] 写失败裁决 + ISE→IOException 转换 ----------------

    @Test
    fun `zero byte write counts as failure`() {
        // 变异锚：isWriteFailure 回退 n<0（旧行为 0 当成功）→ n=0 断言红。
        assertTrue("track.write 返回 0：off 不进=死旋，必须当错误", isWriteFailure(0))
        assertTrue(isWriteFailure(-1))
        assertTrue(isWriteFailure(-2)) // ERROR_BAD_VALUE
        assertFalse("正常写入字节数不是失败", isWriteFailure(1))
        assertFalse(isWriteFailure(3200))
    }

    @Test
    fun `runtime exception from track calls is converted to IOException`() {
        // 变异锚：unpackAsIo 回退直通（旧行为 ISE 裸逃）→ ISE 断言红。
        try {
            unpackAsIo<Int> { throw IllegalStateException("track was released") }
            fail("ISE 必须转 IOException 走上层降级路径（ocr[427] 契约）")
        } catch (e: IOException) {
            assertTrue("原因链保留", e.cause is IllegalStateException)
        }
    }

    @Test
    fun `io exception passes through unpack unchanged`() {
        try {
            unpackAsIo<Int> { throw IOException("stopped") }
            fail("rethrow")
        } catch (e: IOException) {
            assertEquals("stopped", e.message)
            assertEquals("原生 IOException 不包壳（上层按 msg 判 stopped）", null, e.cause)
        }
    }

    @Test
    fun `fatal error propagates through unpack unwrapped`() {
        // Error（OOM 等）不吞不转：保持致命语义
        try {
            unpackAsIo<Int> { throw OutOfMemoryError("boom") }
            fail("rethrow")
        } catch (e: OutOfMemoryError) {
            // 原样穿透
        }
    }

    // ---------------- ocr[428] 建轨缓冲裁决 ----------------

    @Test
    fun `track buffer never reaches zero or negative`() {
        // 变异锚：trackBufBytes 回退 maxOf 直通（旧行为）→ 负 minBuf 断言红。
        assertEquals("负 minBuf（ERROR_BAD_VALUE）落默认底（空 PCM 不再 setBufferSize(0) 崩）",
            48_000, trackBufBytes(minBuf = -2, wanted = 0, floor = 48_000))
        assertEquals("空 PCM + 合法 minBuf：维持 minBuf（行为不变）",
            5_760, trackBufBytes(minBuf = 5_760, wanted = 0, floor = 48_000))
        assertEquals("流式 wanted 优先于合法 minBuf（行为不变）",
            48_000, trackBufBytes(minBuf = -2, wanted = 48_000, floor = 48_000))
        assertEquals("minBuf 大于 wanted（行为不变）",
            64_000, trackBufBytes(minBuf = 64_000, wanted = 48_000, floor = 48_000))
        assertEquals("minBuf=0 同负值处理",
            48_000, trackBufBytes(minBuf = 0, wanted = 0, floor = 48_000))
    }

    // ---------------- ocr[429] 播放头回卷安全 + 死线单调域 ----------------

    @Test
    fun `playback head wrap-around no longer reads as never-reached`() {
        // 变异锚：headReached 回退裸 Int 比较（旧行为）→ 回卷断言红。
        // 24kHz 下 2^31 帧 ≈ 24.8h 回卷成负数：裸比较恒「未追平」→ 死线 3s 空转。
        val justWrapped = Int.MIN_VALUE + 150 // 回卷后又播了 150 帧
        assertTrue("回卷后的头位置在无符号域早已追平 100 帧", headReached(justWrapped, 100L))
        assertTrue("恰好回卷点（2^31 帧）对 2^31 总帧算追平", headReached(Int.MIN_VALUE, 2_147_483_648L))
        assertFalse("未追平仍是未追平", headReached(50, 100L))
        assertFalse("回卷前的大头位置对更大目标未追平", headReached(Int.MAX_VALUE, 2_147_483_700L))
        assertTrue("0 帧/0 头立即追平（幂等语义不变）", headReached(0, 0L))
    }

    @Test
    fun `drain deadline lives in monotonic nanotime domain`() {
        // 变异锚：DRAIN_CLOCK_MS 回退 System::currentTimeMillis（旧行为墙钟）→ 域断言红。
        val v = DRAIN_CLOCK_MS()
        val nanoMs = System.nanoTime() / 1_000_000
        assertTrue(
            "drainUntil 死线基准钟须在 nanoTime 单调域（NTP 回拨不再拉长死线）: v=$v nano=$nanoMs",
            Math.abs(v - nanoMs) < 60_000,
        )
    }

    @Test
    fun `drain deadline math converts frames to playback millis plus 3s tail`() {
        // 新抽纯函数的换算锚：24000 帧 @24kHz = 1s + 3s 上限
        assertEquals(4_000, drainDeadlineMs(nowMs = 0, totalFrames = 24_000, sampleRate = 24_000))
        assertEquals(3_100, drainDeadlineMs(nowMs = 0, totalFrames = 2_400, sampleRate = 24_000))
        assertEquals(3_000, drainDeadlineMs(nowMs = 0, totalFrames = 0, sampleRate = 24_000))
    }
}
