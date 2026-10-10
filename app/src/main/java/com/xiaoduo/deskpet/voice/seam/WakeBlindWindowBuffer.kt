package com.xiaoduo.deskpet.voice.seam

import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import com.xiaoduo.deskpet.voice.audio.AudioCapture

/**
 * S40 唤醒盲区环形缓冲（纯逻辑，JVM 可测）：暂存唤醒接缝期（唤醒命中→vad.start，即
 * KWS 解码尾 +「我在」应答播放窗）的采麦块，vad.start 后首个接缝块触发时按 FIFO
 * 回灌 VAD，把用户紧跟唤醒词说的话头尽力找回。
 *
 * 09-12 取证（14.18 板，125 唤醒序列）：盲区 ~0.9s 内说话 100% 丢失——6 次 30s 超时
 * （短指令整句落在盲区）+ 句头截断（ASR「天天气怎么？」=「今」丢）。回灌语义见
 * [VoiceSessionManager.backfillBlindWindow]。
 *
 * S65 起条目携带捕获时间戳 [StampedBlock.capturedAtNs]：回灌方按截止线
 * [drainFrom] 丢弃「回声纪元」块（wozai 播放期采集 = 旧 TTS 残声 +「我在」回声，
 * NLMS 参考过期直通零消除，直灌必产伪 utterance——2026-09-23 测试板三轮打断重唤醒
 * 实锤，ASR 转出「告诉我我在。」假轮）。
 *
 * 容量 [CAPACITY_BLOCKS]（30 块 = 3s）：正常接缝 ~0.9s；消费停顿使接缝块迟到时环形
 * 淘汰最旧——与 AudioCapture DROP_OLDEST、VAD pre-roll deque 同策（保最新音频）。
 */
class WakeBlindWindowBuffer(val capacityBlocks: Int = CAPACITY_BLOCKS) {

    /** 回灌单元：块 + 捕获时刻（判回声纪元的依据，采集序 FIFO）。 */
    data class Entry(val pcm: ByteArray, val capturedAtNs: Long)

    private val deque = ArrayDeque<Entry>()

    /**
     * CONC-2：所有访问器共用一把锁。add/drain 走采麦收集协程（Dispatchers.Default），
     * clear 可达 KWS 唤醒回调/UI barge-in 线程（ack 播放中唤醒打断场景即双写）——
     * ArrayDeque 非线程安全，无锁并发丢块/损坏 head-tail；drainEntries 的 toList+clear
     * 也须原子（间隙落进来的 add 会被 clear 静默吞掉，违背「恰好回灌一次」）。
     * 同型先例：VoiceSessionManager.continuationLock。
     */
    private val lock = Any()

    val size: Int get() = synchronized(lock) { deque.size }

    /** 入队；满则淘汰最旧（保最新音频）。 */
    fun add(block: ByteArray, capturedAtNs: Long) = synchronized(lock) {
        deque.addLast(Entry(block, capturedAtNs))
        while (deque.size > capacityBlocks) deque.removeFirst()
    }

    /** 按 FIFO 排空并返回（回灌恰好一次：调后必空，防双灌撕时间轴）。 */
    fun drain(): List<ByteArray> = drainEntries().map { it.pcm }

    /**
     * S65：按截止线排空——丢弃捕获早于 [cutoffNs] 的回声纪元块，保留其余（FIFO 序）。
     * 调后必空（与 [drain] 同约）。
     */
    fun drainFrom(cutoffNs: Long): List<ByteArray> =
        drainEntries().filterNot { it.capturedAtNs < cutoffNs }.map { it.pcm }

    /** 清空（cancel/新唤醒入口调用，防旧轮音频泄入新轮）。 */
    fun clear() = synchronized(lock) { deque.clear() }

    private fun drainEntries(): List<Entry> = synchronized(lock) {
        val out = deque.toList()
        deque.clear()
        out
    }

    companion object {
        /** 默认容量 30 块 = 3s（KWS 尾延迟 ~0.2s + 应答播放 ~0.7s + 消费停顿裕量）。 */
        const val CAPACITY_BLOCKS = 30
    }
}
