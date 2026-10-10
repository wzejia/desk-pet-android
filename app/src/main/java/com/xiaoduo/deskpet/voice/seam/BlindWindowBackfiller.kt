package com.xiaoduo.deskpet.voice.seam

import com.xiaoduo.deskpet.voice.audio.VadDetector

/**
 * S40 盲区回灌编排（2026-10-10 P3-T2 自 VoiceSessionManager 聚合搬移：接缝水位/回灌挂账/
 * 回灌消费循环收口于此，参数化注入不改行为）。相邻件边界：[WakeBlindWindowBuffer] 只管
 * 环形缓冲本体（容量/FIFO/锁），本类管「何时记水位、何时挂账、回灌时滤什么」；接缝去向
 * 判定（VadSeamPolicy）与 VSM 测试缝仍留 VSM 面转委托。
 *
 * 盲区背景：唤醒命中→vad.start 之间（KWS 解码尾 +「我在」应答播放窗）采麦块不直灌
 * VAD——09-12 取证盲区 ~0.9s 内说话 100% 丢失；缓冲 + 接缝后回灌把话头尽力找回。
 */
internal class BlindWindowBackfiller(
    /** 盲区环形缓冲（唤醒接缝时代采麦块暂存，容量淘汰最旧）。 */
    private val buffer: WakeBlindWindowBuffer,
    /** S23 回声消除开关（每块现读，VSM echoCancelProvider 注入）。 */
    private val echoCancelEnabled: () -> Boolean,
    /** NLMS 消回声入口（ack 参考已由 VSM 钩子推入；块仍过 NLMS 保底）。 */
    private val processBlock: (ByteArray) -> ByteArray,
    /** VAD 喂块入口（回灌按采集序 FIFO，话头在前）。 */
    private val feedVad: (ByteArray) -> VadDetector.Event?,
    /** VAD 事件统一出口（utterance 进续听/超时回 IDLE 均由 VSM 收口）。 */
    private val onVadEvent: (VadDetector.Event) -> Unit,
    /** S59 尾音衰减窗（VSM internal var 注入，测试可置 0；回灌读现值）。 */
    private val vadStartDelayMs: () -> Long,
    private val logger: (String) -> Unit,
) {

    /** 回灌挂账：唤醒路径 vad.start 后置位，首个接缝后块触发回灌消费；打断清账。 */
    @Volatile
    var pending = false
        private set

    /** 接缝水位 = vad.start 瞬间（nanoTime，与采麦盖戳同域）：ack 时代判定基准。
     *  落笔必须先于 vad.start（此后捕获的块均判接缝后）。 */
    @Volatile
    var seamNs = 0L
        private set

    /** 接缝水位落笔（唤醒路径 vad.start 前调用）。 */
    fun markSeam(nowNs: Long) {
        seamNs = nowNs
    }

    /** 回灌挂账置位（vad.start 之后调用）。 */
    fun armPending() {
        pending = true
    }

    /** ack 时代块入缓冲（KWS 分支实时解码不变；FIFO 时序由缓冲保证）。 */
    fun add(block: ByteArray, capturedAtNs: Long) = buffer.add(block, capturedAtNs)

    /** 盲区缓冲占用（VSM 测试缝 blindBufferSizeForTest 转委托）。 */
    val bufferSize: Int get() = buffer.size

    /** 新唤醒清陈缓冲（playAckThenListen 原语义：只清缓冲，不动挂账）。 */
    fun clearBuffer() = buffer.clear()

    /** 打断/重唤醒单点清账：先撤挂账再清缓冲，防旧轮 ack 时代音频回灌进新轮。 */
    fun abortAndClear() {
        pending = false
        buffer.clear()
    }

    /** 接缝后首块触发：先撤挂账再回灌（恰好一次；调后必不重复消费）。 */
    fun disarmThenBackfill() {
        pending = false
        backfill()
    }

    /**
     * S40：回灌盲区缓冲到 VAD（接缝后首个块触发，采集序 FIFO——话头在前）。
     * S65 回声纪元守卫：截止线 cutoff = seam - (vadStartDelayMs + ECHO_TAIL_GUARD_MS)
     * ——wozai 播放期捕获的块（capturedAt < 写完时刻 ≈ seam - vadStartDelayMs，含旧 TTS
     * 残声与「我在」回声）整段丢弃：回灌发生时 NLMS 参考已过期（>REF_FRESH_MS 直通）
     * 零消除，直灌必产伪 utterance（2026-09-23 测试板三轮打断重唤醒实锤：活块 vad diag
     * 全静却 96ms 后报 1.2s 语音，ASR 转出「告诉我我在。」假轮抢占聆听窗）。保留
     * delay 窗尾部（写完+喇叭尾巴余量之后）捕获的迟到块——S40 话头找回收窄存续。
     * 块仍过 NLMS（保底，若参考尚新鲜）；回灌不喂 kwsSink：KWS 分支已实时解过这些块，
     * 双喂恐同声词尾二次命中触发重唤醒环。回灌途中 utterance 即刻进管线并中止回灌
     * （VAD 已停机，余块放弃——本块之后的活块继续走正常路由）。
     */
    fun backfill() {
        val total = buffer.size
        if (total == 0) return
        val cutoffNs = seamNs - (vadStartDelayMs() + ECHO_TAIL_GUARD_MS) * 1_000_000L
        val blocks = buffer.drainFrom(cutoffNs)
        logger("S40 blind-window backfill: ${blocks.size} blocks, S65 echo-era dropped ${total - blocks.size}")
        for (b in blocks) {
            val out = if (echoCancelEnabled()) processBlock(b) else b
            val ev = feedVad(out)
            if (ev != null) {
                onVadEvent(ev)
                if (ev is VadDetector.Event.Utterance || ev is VadDetector.Event.Timeout) return
            }
        }
    }

    companion object {
        /** S65 回声纪元尾巴余量：wozai 写完（playBlocking 返回）后喇叭/混响残余保护窗。 */
        internal const val ECHO_TAIL_GUARD_MS = 150L
    }
}
