package com.xiaoduo.deskpet.voice

import android.content.Context
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
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
     * 环境稳定性判定（纯函数，JVM 单测锁定）：纯噪声分布集中（p95 贴近 median），
     * 人声/突发噪声使 p95 抬升一个量级。容忍线 p95 ≤ median×4；median=0（采麦失败/
     * 全零样本）不可判噪声底，一律拒绝。
     */
    fun stableEnough(stats: EnergyGate.CalibrationStats): Boolean =
        stats.count > 0 && stats.median > 0f && stats.p95 <= stats.median * 4f

    /**
     * 仅首次自动校准：未校准过 → 采集 → 判稳 → 写入 gate/vad + done 标记。
     * 返回是否实际写入（false = 已校准过 / 采样异常 / 采麦不可用）。
     */
    suspend fun autoCalibrateIfFirstTime(context: Context): Boolean {
        if (Prefs.autoCalibDone(context)) return false
        val rmsValues = collectAmbient(context, COLLECT_SECONDS)
        val stats = EnergyGate.statsOf(rmsValues)
        if (!stableEnough(stats)) {
            PetLogger.w(
                TAG,
                "ambient unstable (n=${stats.count} median=${stats.median} p95=${stats.p95}), keep defaults",
            )
            return false
        }
        val gate = EnergyGate.suggestThreshold(stats)
        val vad = EnergyGate.suggestVadSpeechLevel(stats)
        Prefs.setKwsGate(context, gate)
        Prefs.setVadSpeechLevel(context, vad)
        Prefs.setAutoCalibDone(context, true)
        PetLogger.i(
            TAG,
            "auto calibration written: n=${stats.count} median=${stats.median} " +
                "p95=${stats.p95} gate=$gate vad=$vad",
        )
        return true
    }

    /** 采集 [seconds] 秒块级 RMS（KWS 常开中复用同一条流；未开则临时开一条采满即停）。 */
    suspend fun collectAmbient(context: Context, seconds: Int): List<Float> {
        val session = VoiceClientFactory.session(context)
        val out = mutableListOf<Float>()
        if (session.captureRunning) {
            collectRmsFor(seconds, session.captureBlocks, out)
        } else {
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
        return out
    }

    private suspend fun collectRmsFor(
        seconds: Int,
        blocks: SharedFlow<ByteArray>,
        out: MutableList<Float>,
    ) {
        try {
            withTimeout(seconds * 1000L) {
                blocks.collect { block -> out.add(EnergyGate.rmsOf(block)) }
            }
        } catch (te: TimeoutCancellationException) {
            // 采满时长正常收尾
        }
    }
}
