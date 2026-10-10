package com.xiaoduo.deskpet.voice.audio

import android.content.Context
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.kws.EnergyGate
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/**
 * S29 环境音自动校准（仅首次）：语音服务启动时若从未校准过，静默采 5 秒环境 RMS →
 * 建议阈值（EnergyGate 公式）→ 合理性判定 → 直接写入并标记 done。采样被污染
 * （有人说话/突发噪声，p95 远超 median）则放弃本次、不标记，下次启动重试。
 * 手动校准（设置页）成功写入时同样置 done——用户干预后自动校准永久让位。
 *
 * 单流纪律（知识包 §3.5）：调用时机在 KWS 采麦流已起之后，[collectAmbient] 走
 * captureRunning 复用分支，不双开。
 */
object AmbientCalibrator {

    private const val TAG = "AutoCalib"
    private const val COLLECT_SECONDS = 5

    /**
     * ocr[384] 充分性门槛：5s@100ms 块距应得 ~50 样本，<25 视为欠采样（卡顿/刚起流/
     * 零星几块）——不可代表样本推不出可信分布，且结果会被永久持久化（done 置位不再
     * 重试），宁缺毋滥。变异锚：门槛删除 → AmbientCalibratorRobustnessTest 红。
     */
    private const val MIN_SAMPLES = 25

    /**
     * 环境稳定性判定（纯函数，JVM 单测锁定）：纯噪声分布集中（p95 贴近 median），
     * 人声/突发噪声使 p95 抬升一个量级。容忍线 p95 ≤ median×4；median=0（采麦失败/
     * 全零样本）不可判噪声底，一律拒绝；样本数 < [MIN_SAMPLES] 拒绝（ocr[384]）。
     */
    fun stableEnough(stats: EnergyGate.CalibrationStats): Boolean =
        stats.count >= MIN_SAMPLES && stats.median > 0f && stats.p95 <= stats.median * 4f

    /**
     * 仅首次自动校准：未校准过 → 采集 → 判稳 → 写入 gate/vad + done 标记。
     * 返回是否实际写入（false = 已校准过 / 采样异常 / 采麦不可用）。
     */
    suspend fun autoCalibrateIfFirstTime(context: Context): Boolean {
        if (Prefs.autoCalibDone(context)) return false
        return autoCalibrate(
            done = { Prefs.autoCalibDone(context) },
            collect = { collectAmbient(context, COLLECT_SECONDS) },
            write = { gate, vad ->
                Prefs.setKwsGate(context, gate)
                Prefs.setVadSpeechLevel(context, vad)
                Prefs.setAutoCalibDone(context, true)
            },
        )
    }

    /**
     * 校准主流程（依赖注入拆出，JVM 可测）。ocr[383] 守卫：persist 前一刻复查 done——
     * 采集窗（5s）内手动校准完成置 done 时，在飞自动结果让位不覆写（类注释不变量：
     * 「用户干预后自动校准永久让位」）。旧实现只在进场查一次，5s 窗口内的手动落地会被
     * 自动结果整组覆写并重置 done。变异锚：删除复查 → AmbientCalibratorRobustnessTest 红。
     */
    internal suspend fun autoCalibrate(
        done: () -> Boolean,
        collect: suspend () -> List<Float>,
        write: (gate: Float, vad: Double) -> Unit,
        logInfo: (String) -> Unit = { PetLogger.i(TAG, it) },
        logWarn: (String) -> Unit = { PetLogger.w(TAG, it) },
    ): Boolean {
        val rmsValues = collect()
        val stats = EnergyGate.statsOf(rmsValues)
        if (!stableEnough(stats)) {
            logWarn(
                "ambient unstable (n=${stats.count} median=${stats.median} p95=${stats.p95}), keep defaults",
            )
            return false
        }
        if (done()) {
            logInfo("manual calibration landed during auto window, auto result yields")
            return false
        }
        val gate = EnergyGate.suggestThreshold(stats)
        val vad = EnergyGate.suggestVadSpeechLevel(stats)
        write(gate, vad)
        logInfo(
            "auto calibration written: n=${stats.count} median=${stats.median} " +
                "p95=${stats.p95} gate=$gate vad=$vad",
        )
        return true
    }

    /**
     * 采集 [seconds] 秒块级 RMS（KWS 常开中复用同一条流；未开则临时开一条采满即停）。
     * ocr[382] 根修：复用决策与临时流全生命周期经 [VoiceSessionManager.captureExclusivity]
     * 与 KWS 起流互斥（临时流是 AudioCapture 新实例，实例内门闩防不了跨实例双开）——
     * 复用分支仅决策原子化（采集锁外：订阅同一条流无双开，流中途停止的欠采样窗由
     * MIN_SAMPLES 门槛兜底）；临时分支整窗持锁（边缘代价：窗口内 KWS 起流延后 ≤5s）。
     */
    suspend fun collectAmbient(context: Context, seconds: Int): List<Float> {
        val session = VoiceClientFactory.session(context)
        val out = mutableListOf<Float>()
        val reuse = session.captureExclusivity { session.captureRunning }
        if (reuse) {
            collectRmsFor(seconds, session.captureBlocks, out)
        } else {
            session.captureExclusivity {
                val capture = AudioCapture(sourceMode = Prefs.micSourceMode(context))
                val ioScope = CoroutineScope(Dispatchers.IO)
                try {
                    capture.start(ioScope)
                    collectRmsFor(seconds, capture.blocks, out)
                } finally {
                    capture.stop()
                    ioScope.cancel()
                }
            }
        }
        return out
    }

    internal suspend fun collectRmsFor(
        seconds: Int,
        blocks: Flow<ByteArray>,
        out: MutableList<Float>,
    ) {
        try {
            withTimeout(seconds * 1000L) {
                blocks.collect { block -> out.add(EnergyGate.rmsOf(block)) }
            }
        } catch (te: TimeoutCancellationException) {
            timeoutFinishOrRethrow(te)
        }
    }

    /**
     * ocr[381] 守卫：超时收尾（TimeoutCancellationException 落 catch）仅在调用方仍存活
     * 时视为正常。计时器先到点、调用方取消随后落地的并发窗里，无条件吞会让取消态协程
     * 继续跑完 persist（写 gate/vad/done）——活性复查后取消态原样上抛。
     * 变异锚：守卫回退空体 → AmbientCalibratorRobustnessTest 红。
     * （竞态全貌不可确定性复刻：withTimeout 子协程先于父取消时改投 JobCancellationException，
     * 本守卫兜住的是「TimeoutCE 先到 catch、取消已在」的到达序。）
     */
    internal suspend fun timeoutFinishOrRethrow(te: TimeoutCancellationException) {
        kotlin.coroutines.coroutineContext.ensureActive()
    }
}
