package com.xiaoduo.deskpet.voice.pipeline


/**
 * 回复载体路由（S7-d 全屏语音模式，纯 Kotlin 零 Android import，JVM 可测）：
 * 语音轮回复按「当前活跃载体」分发——悬浮窗＝气泡文字（不变），全屏＝无文字
 * （TTS 经 VoicePlayer 不经 BubbleSink，全屏分支恒 no-op 即天然纯 TTS）。
 * 载体判定在每次调用时现读（carrier lambda），在途轮中途切载体后续调用立即
 * 改投新载体（无 round 粘滞）。
 */
enum class ReplyCarrier { OVERLAY, FULLSCREEN }

/** 按当前载体转发的 BubbleSink：show/append/hide/hideLater 全部现读现投。 */
class CarrierBubbleSink(
    private val carrier: () -> ReplyCarrier,
    private val overlay: VoiceSessionManager.BubbleSink,
    private val fullscreen: VoiceSessionManager.BubbleSink,
) : VoiceSessionManager.BubbleSink {

    private fun active(): VoiceSessionManager.BubbleSink =
        if (carrier() == ReplyCarrier.FULLSCREEN) fullscreen else overlay

    override fun show(text: String) = active().show(text)
    override fun append(delta: String) = active().append(delta)
    override fun hide() = active().hide()
    override fun hideLater(delayMs: Long) = active().hideLater(delayMs)
}

/** 全屏分支：恒 no-op（全屏默认无文字，声音与动画反馈不经 BubbleSink）。 */
object FullscreenBubbleSink : VoiceSessionManager.BubbleSink {
    override fun show(text: String) {}
    override fun append(delta: String) {}
    override fun hide() {}
    override fun hideLater(delayMs: Long) {}
}
