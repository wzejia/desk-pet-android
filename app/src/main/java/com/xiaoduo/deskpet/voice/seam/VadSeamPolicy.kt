package com.xiaoduo.deskpet.voice.seam

/**
 * S40 唤醒接缝水位策略（纯逻辑，JVM 可测）：唤醒路径 vad.start 后、回灌消费前的过渡窗
 * 内，逐块判定去向。
 *
 * 背景：消费协程停顿恢复后，ack 时代（捕获早于接缝水位 = vad.start 瞬间）的积压块会
 * 成串突发到达 LISTENING 路由——09-12 Case B 实锤 533ms 窗口产出含 1.2s 音频的
 * utterance（数学上不可能，只能是陈块直灌）。UNPROCESSED 采麦源无硬件 AEC，这些块含
 * 「我在」外放回声（RMS 0.35-0.55），直灌会抬 S19-1 自适应阈值吞话头/产出伪轮。
 *
 * 三分支（判据 = 捕获时间戳 vs 接缝水位）：
 * - [Action.BUFFER]：ack 时代块 → 盲区缓冲等回灌（不直灌 VAD；FIFO 保证首个接缝后块
 *   到达时 ack 时代块已全部到齐，回灌时序完整）；
 * - [Action.BACKFILL_THEN_LIVE]：接缝后首个块 → 先回灌缓冲再按活块处理；
 * - [Action.LIVE]：未挂账（多轮续听/按住/降级路径）零开销直通。
 */
class VadSeamPolicy {

    enum class Action { BUFFER, BACKFILL_THEN_LIVE, LIVE }

    /** [capturedAtNs] 早于 [seamNs] 判 ack 时代；恰好等于 = vad.start 之后捕获，按接缝后。 */
    fun onBlock(pending: Boolean, capturedAtNs: Long, seamNs: Long): Action = when {
        !pending -> Action.LIVE
        capturedAtNs < seamNs -> Action.BUFFER
        else -> Action.BACKFILL_THEN_LIVE
    }
}
