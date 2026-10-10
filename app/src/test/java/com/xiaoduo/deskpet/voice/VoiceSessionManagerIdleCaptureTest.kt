package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * IDLE 采麦归属（审计 P1：对话中销毁 KWS 服务 → 麦克风孤儿空采）。
 *
 * 根因：旧 stopIdleCapture 在 state != IDLE 时直接 return——服务 onDestroy 先
 * kwsSink=null 再 stop，对话结束回 IDLE 后 collectJob 仍收块、IDLE 路由到 null sink
 * 全丢，AudioRecord 永不收（系统麦绿点常亮、耗电、无日志）。
 * 修复：start/stopIdleCapture 改为意愿登记（idleCaptureWanted），回 IDLE 的
 * transition 统一按意愿执行 teardown——采集流归属有了单一裁决点。
 */
class VoiceSessionManagerIdleCaptureTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(logs: MutableList<String>): VoiceSessionManager {
        // 不可达端点占位：本组用例不触发管线，零网络
        val asr = AsrClient(okhttp3.OkHttpClient(), "http://127.0.0.1:9/asr", logger = {})
        val tts = TtsClient(
            okhttp3.OkHttpClient(),
            streamUrl = "http://127.0.0.1:9/tts/stream",
            synthesizeUrl = "http://127.0.0.1:9/tts/synthesize",
            streamSupport = object : TtsClient.StreamSupport {
                override fun isUnsupported(): Boolean = false
                override fun markUnsupported() {}
            },
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = asr,
            tts = tts,
            aguiFactory = { error("pipeline not used in idle-capture tests") },
            bubble = object : VoiceSessionManager.BubbleSink {
                override fun show(text: String) {}
                override fun append(delta: String) {}
                override fun hide() {}
                override fun hideLater(delayMs: Long) {}
            },
            scope = scope,
            logger = { msg -> logs += msg },
        )
    }

    @Test
    fun stopIdleCaptureDuringConversationDefersTeardownToIdleTransition() {
        // 回归守卫：回 IDLE 时的意愿检查（删此 → 对话中 stop 后 collectJob 挂活 → 本测试红）
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)

        mgr.kwsSink = { }
        mgr.startIdleCapture()
        assertTrue("IDLE 态 start 应起采麦循环", mgr.collectLoopActive)

        mgr.startListening() // 进入对话（LISTENING）
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        mgr.kwsSink = null
        mgr.stopIdleCapture() // 对话中：只登记意愿，不动对话的采集
        assertTrue("对话进行中采集归对话管线，不得立即收", mgr.collectLoopActive)

        mgr.cancelCurrentTurn() // 对话结束 → backToIdle
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse("回 IDLE 且意愿已撤 → 必须收采集循环（孤儿空采修复点）", mgr.collectLoopActive)
    }

    @Test
    fun stopIdleCaptureInIdleTearsDownImmediately() {
        // 旧行为保持：IDLE 态 stop 立即收（critic 修复史：必须连 collectJob 一起收）
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)

        mgr.startIdleCapture()
        assertTrue(mgr.collectLoopActive)
        mgr.stopIdleCapture()
        assertFalse(mgr.collectLoopActive)
    }

    @Test
    fun conversationEndKeepsCaptureWhenKwsStillWanted() {
        // 非回归：KWS 常开中对话结束回 IDLE，采集循环必须活着（唤醒常开的根基）
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)

        mgr.kwsSink = { }
        mgr.startIdleCapture()
        mgr.startListening()
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertTrue("KWS 意愿仍在 → 回 IDLE 不得收采集", mgr.collectLoopActive)

        mgr.stopIdleCapture() // 清理： scope.cancel 前显式收
        assertFalse(mgr.collectLoopActive)
    }
}
