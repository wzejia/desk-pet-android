package com.xiaoduo.deskpet.voice

/**
 * S40 唤醒盲区环形缓冲（纯逻辑，JVM 可测）：暂存唤醒接缝期（唤醒命中→vad.start，即
 * KWS 解码尾 +「我在」应答播放窗）的采麦块，vad.start 后首个接缝块触发时按 FIFO
 * 回灌 VAD，把用户紧跟唤醒词说的话头尽力找回。
 *
 * 09-12 取证（14.18 板，125 唤醒序列）：盲区 ~0.9s 内说话 100% 丢失——6 次 30s 超时
 * （短指令整句落在盲区）+ 句头截断（ASR「天天气怎么？」=「今」丢）。回灌语义见
 * [VoiceSessionManager.backfillBlindWindow]。
 *
 * 容量 [CAPACITY_BLOCKS]（30 块 = 3s）：正常接缝 ~0.9s；消费停顿使接缝块迟到时环形
 * 淘汰最旧——与 AudioCapture DROP_OLDEST、VAD pre-roll deque 同策（保最新音频）。
 */
class WakeBlindWindowBuffer(val capacityBlocks: Int = CAPACITY_BLOCKS) {

    private val deque = ArrayDeque<ByteArray>()

    val size: Int get() = deque.size

    /** 入队；满则淘汰最旧（保最新音频）。 */
    fun add(block: ByteArray) {
        deque.addLast(block)
        while (deque.size > capacityBlocks) deque.removeFirst()
    }

    /** 按 FIFO 排空并返回（回灌恰好一次：调后必空，防双灌撕时间轴）。 */
    fun drain(): List<ByteArray> {
        val out = deque.toList()
        deque.clear()
        return out
    }

    /** 清空（cancel/新唤醒入口调用，防旧轮音频泄入新轮）。 */
    fun clear() = deque.clear()

    companion object {
        /** 默认容量 30 块 = 3s（KWS 尾延迟 ~0.2s + 应答播放 ~0.7s + 消费停顿裕量）。 */
        const val CAPACITY_BLOCKS = 30
    }
}
