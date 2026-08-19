package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回复载体路由单测（S7-d 全屏语音模式）：CarrierBubbleSink 每次调用现读载体转发。
 *
 * 钉死验收点：
 * - FULLSCREEN 时 overlay 零调用 / fullscreen 全收；OVERLAY 反向；
 * - 在途轮中途切载体，后续调用立即改投新载体（无 round 粘滞）；
 * - hide/hideLater 同按载体路由。
 */
class ReplyCarrierTest {

    private class RecordingSink : VoiceSessionManager.BubbleSink {
        val calls = mutableListOf<String>()
        override fun show(text: String) {
            calls.add("show:$text")
        }
        override fun append(delta: String) {
            calls.add("append:$delta")
        }
        override fun hide() {
            calls.add("hide")
        }
        override fun hideLater(delayMs: Long) {
            calls.add("hideLater:$delayMs")
        }
    }

    @Test
    fun `fullscreen carrier routes everything to fullscreen sink`() {
        val overlay = RecordingSink()
        val fullscreen = RecordingSink()
        val sink = CarrierBubbleSink({ ReplyCarrier.FULLSCREEN }, overlay, fullscreen)

        sink.show("你")
        sink.append("好")
        sink.hide()
        sink.hideLater(12000)

        assertEquals(emptyList<String>(), overlay.calls)
        assertEquals(listOf("show:你", "append:好", "hide", "hideLater:12000"), fullscreen.calls)
    }

    @Test
    fun `overlay carrier routes everything to overlay sink`() {
        val overlay = RecordingSink()
        val fullscreen = RecordingSink()
        val sink = CarrierBubbleSink({ ReplyCarrier.OVERLAY }, overlay, fullscreen)

        sink.show("你")
        sink.append("好")
        sink.hide()
        sink.hideLater(12000)

        assertEquals(listOf("show:你", "append:好", "hide", "hideLater:12000"), overlay.calls)
        assertEquals(emptyList<String>(), fullscreen.calls)
    }

    @Test
    fun `switching carrier mid-turn redirects subsequent calls`() {
        val overlay = RecordingSink()
        val fullscreen = RecordingSink()
        var carrier = ReplyCarrier.OVERLAY
        val sink = CarrierBubbleSink({ carrier }, overlay, fullscreen)

        sink.show("你")
        sink.append("好")
        // 在途轮中途进全屏（守卫四问#2）：后续 append 立即改投全屏，overlay 定格
        carrier = ReplyCarrier.FULLSCREEN
        sink.append("，世界")
        sink.hide()
        // 退出全屏恢复：再投回 overlay
        carrier = ReplyCarrier.OVERLAY
        sink.hideLater(5000)

        assertEquals(listOf("show:你", "append:好", "hideLater:5000"), overlay.calls)
        assertEquals(listOf("append:，世界", "hide"), fullscreen.calls)
    }

    @Test
    fun `hide and hideLater follow current carrier`() {
        val overlay = RecordingSink()
        val fullscreen = RecordingSink()
        var carrier = ReplyCarrier.FULLSCREEN
        val sink = CarrierBubbleSink({ carrier }, overlay, fullscreen)

        sink.hide()
        carrier = ReplyCarrier.OVERLAY
        sink.hide()
        sink.hideLater(100)

        assertEquals(listOf("hide", "hideLater:100"), overlay.calls)
        assertEquals(listOf("hide"), fullscreen.calls)
    }
}
