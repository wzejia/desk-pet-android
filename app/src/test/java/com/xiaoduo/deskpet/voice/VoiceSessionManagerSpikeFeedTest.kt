package com.xiaoduo.deskpet.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S11-3 VoiceSessionManager.spikeFeedKws 委托属性测试（fake 注入，被测对象零 mock）。
 *
 * 覆盖：经 VSM.spikeFeedKws 写 → router.spikeFeedKws 读一致（双向）；默认 false。
 * 调试会话级开关语义：不被 backToIdle/状态迁移复位（与 kwsPaused 每轮语义不同，
 * 配对管理在 AecSpikeSection UI），故此处不做状态迁移复位断言——VSM 全程不启动
 * 管线，asr/tts 用不可达端点占位（零网络）。
 */
class VoiceSessionManagerSpikeFeedTest {

    private lateinit var scope: CoroutineScope

    /** 气泡桩：行为不断言，仅为满足构造。 */
    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(): VoiceSessionManager {
        // 不可达端点占位：本组用例不触发管线，零网络
        val asr = AsrClient(OkHttpClient(), "http://127.0.0.1:9/asr", logger = {})
        val tts = TtsClient(
            OkHttpClient(),
            streamUrl = "http://127.0.0.1:9/tts/stream",
            synthesizeUrl = "http://127.0.0.1:9/tts/synthesize",
            streamSupport = MemStreamSupport(),
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = asr,
            tts = tts,
            aguiFactory = { error("pipeline not used in spike-feed tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = {},
        )
    }

    @Test
    fun `spikeFeedKws 委托直通 router 双向一致`() {
        val mgr = newManager()
        // 默认 false（spike 默认关，对正常取证零干扰）
        assertFalse(mgr.spikeFeedKws)
        assertFalse(mgr.router.spikeFeedKws)
        // VSM 写 → router 读
        mgr.spikeFeedKws = true
        assertTrue(mgr.router.spikeFeedKws)
        // VSM getter 回读（变异锚：杀 get()=false / get()=router.kwsPaused 两类变异）
        assertTrue(mgr.spikeFeedKws)
        // router 写 → VSM 读（同一存储，无副本漂移）
        mgr.router.spikeFeedKws = false
        assertFalse(mgr.spikeFeedKws)
        // 幂等：重复写同值无副作用
        mgr.spikeFeedKws = true
        mgr.spikeFeedKws = true
        assertTrue(mgr.router.spikeFeedKws)
    }
}
