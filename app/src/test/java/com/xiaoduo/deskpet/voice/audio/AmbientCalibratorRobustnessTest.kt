package com.xiaoduo.deskpet.voice.audio

import com.xiaoduo.deskpet.voice.kws.EnergyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AmbientCalibrator 健壮性批（spec §5.2 / ocr[381..384]，2026-10-10 P3-T1）：
 * - [381] TimeoutCancellationException 与调用方取消并发时被无条件吞 → 取消态协程继续
 *   跑完 persist（写 gate/vad/done）——catch 后活性复查，取消态原样上抛；
 * - [382] captureRunning check-then-act 双开麦竞态：原子化改在 session 侧（VSM，
 *   地界外）→ 上报清单；地界内可做部分=采集后充分性校验（[384] 覆盖，欠采样不持久化）；
 * - [383] 采集窗（5s）内手动校准完成置 done，在飞自动结果仍覆写 + 重置 done——
 *   persist 前一刻复查 done，手动校准永久赢；
 * - [384] count>0 门槛太松：1~2 个近乎同值 RMS 也过检，不可代表的阈值被永久持久化
 *   （done 置位不再重试）——要求 MIN_SAMPLES。
 */
class AmbientCalibratorRobustnessTest {

    private fun stats(median: Float, p95: Float, count: Int) =
        EnergyGate.CalibrationStats(count = count, min = 0f, median = median, p95 = p95, max = p95 * 1.2f)

    /** 稳定样本（p95 贴近 median）：5s@100ms 块距应得 ~50 个。 */
    private fun stableRms(count: Int = 50) = List(count) { 0.002f + (it % 3) * 0.0001f }

    // ---------------- ocr[384] 充分性门槛 ----------------

    @Test
    fun `near-identical tiny sample cannot pass sufficiency gate`() {
        // 变异锚：MIN_SAMPLES 门槛回退 count>0（旧行为）→ 两断言红。
        assertFalse(
            "2 个近乎同值 RMS 也满足 p95≤median×4——不可代表样本不得持久化阈值",
            AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.003f, count = 2)),
        )
        assertFalse(
            "24 个（<MIN_SAMPLES）仍拒",
            AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.003f, count = 24)),
        )
        assertTrue(
            "恰达 MIN_SAMPLES=25（5s@100ms 块距的一半）放行",
            AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.003f, count = 25)),
        )
    }

    // ---------------- ocr[383] 手动校准永久赢 ----------------

    @Test
    fun `manual calibration landing during collect window makes auto result yield`() = runBlocking {
        // 变异锚：persist 前 done 复查删除（旧行为只查进场一次）→ write 照发 → 红。
        var collected = false
        val written = mutableListOf<Pair<Float, Double>>()
        val result = AmbientCalibrator.autoCalibrate(
            done = { collected }, // 采集完成后翻 true = 手动校准在窗口内落地
            collect = { collected = true; stableRms() },
            write = { gate, vad -> written += gate to vad },
            logInfo = {}, logWarn = {},
        )
        assertFalse("手动校准已落地，自动结果必须让位", result)
        assertTrue("不得覆写 gate/vad/done（类注释不变量：用户干预后自动校准永久让位）", written.isEmpty())
    }

    @Test
    fun `clean first-time run persists suggested values once`() = runBlocking {
        // 行为保持锚：无手动干预的干净路径照常持久化（删错守卫会误伤本路径 → 红）。
        val written = mutableListOf<Pair<Float, Double>>()
        val result = AmbientCalibrator.autoCalibrate(
            done = { false },
            collect = { stableRms() },
            write = { gate, vad -> written += gate to vad },
            logInfo = {}, logWarn = {},
        )
        assertTrue(result)
        assertEquals(1, written.size)
    }

    @Test
    fun `unstable sample writes nothing and never consults done`() = runBlocking {
        // 行为保持锚：污染样本照旧拒绝、不写、不置 done。
        var doneConsulted = false
        val written = mutableListOf<Pair<Float, Double>>()
        val result = AmbientCalibrator.autoCalibrate(
            done = { doneConsulted = true; false },
            collect = { List(47) { 0.002f } + List(3) { 0.5f } }, // 尾部 3 块人声：p95 位（idx 47）落在人声块
            write = { gate, vad -> written += gate to vad },
            logInfo = {}, logWarn = {},
        )
        assertFalse(result)
        assertTrue(written.isEmpty())
        assertFalse("判稳未过根本不该问 done（旧时序：写前才查）", doneConsulted)
    }

    // ---------------- ocr[381] 超时收尾在取消态必须上抛 ----------------

    @Test
    fun `timeout finish rethrows when coroutine already cancelled`() = runBlocking {
        // 变异锚：守卫回退空体（旧行为无条件吞）→ caught==null → 红。
        // 真机竞态（计时器先到点、取消随后落地）不可确定性复刻（withTimeout 子协程取消
        // 时改投 JobCancellationException，见实现注释），故直接单测守卫语义本身：
        // 取消态下进入超时收尾 → 必须 ensureActive 上抛，不给取消态协程继续 persist 的口子。
        val realTimeout = runCatching { withTimeout(1) { delay(100) } }.exceptionOrNull()
        assertNotNull("须取得真 TimeoutCancellationException（构造器 internal）", realTimeout)
        val scope = CoroutineScope(kotlinx.coroutines.Job() + Dispatchers.Unconfined)
        var caught: Throwable? = null
        val job = scope.launch {
            scope.cancel() // 自我取消（Unconfined 急切执行：守卫进入前作用域已死）
            try {
                AmbientCalibrator.timeoutFinishOrRethrow(realTimeout as kotlinx.coroutines.TimeoutCancellationException)
            } catch (t: Throwable) {
                caught = t
            }
        }
        job.join()
        assertTrue(
            "取消态下超时收尾必须上抛（旧实现无条件吞 → 取消态协程继续写 Prefs）: caught=$caught",
            caught is CancellationException,
        )
    }

    @Test
    fun `timeout finish passes through when caller still active`() = runBlocking {
        // 行为保持锚：存活调用方的超时收尾照常吞掉（正常采满路径不受守卫影响）。
        val realTimeout = runCatching { withTimeout(1) { delay(100) } }.exceptionOrNull()
        assertNotNull(realTimeout)
        AmbientCalibrator.timeoutFinishOrRethrow(realTimeout as kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test
    fun `clean timeout still completes normally when caller is active`() = runBlocking {
        // 行为保持锚：正常采满收尾不受影响（blocks 先流完，withTimeout 到点收尾）。
        val out = mutableListOf<Float>()
        val flow = flow {
            emit(ByteArray(3200))
            emit(ByteArray(3200))
        }
        AmbientCalibrator.collectRmsFor(0, flow, out) // withTimeout(0) 立即到点 → 正常收尾路径
        // 0 秒窗下 collect 可能被取消（TimeoutCE 路径）或恰好完成——两种都必须无异常返回
        assertTrue(out.size <= 2)
    }
}
