package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceClientFactory 进程级 scope 契约单测（thread-discipline 件 3 / ocr[53] 定案）：
 * scope hoist 为命名属性 [VoiceClientFactory.processScope] + 测试专用 reset。
 *
 * 变异锚：resetForTest 漏 cancel 旧 scope → 用例 1 红（旧 scope isActive 仍 true）。
 * 注：JVM 测试无 Android 依赖跑不了 session(context) 全装配（assets/prefs/PetOverlayService），
 * 「session 重建非同一实例」以失效断言兑底（seed 后 reset 必非同实例且为 null，下次
 * session() 走 DCL 重建）——设计文档测试 2 的 JVM 可执行等价形态。
 */
class VoiceClientFactoryProcessScopeTest {

    /** 极简可用 VSM（体例同 S51 测试 newManager：纯 JVM 桩装配，不触 Android）。 */
    private fun stubSession(): VoiceSessionManager = VoiceSessionManager(
        capture = AudioCapture(),
        asr = AsrClient(okhttp3.OkHttpClient(), "http://127.0.0.1:9/asr", logger = {}),
        tts = TtsClient(
            okhttp3.OkHttpClient(),
            streamUrl = "http://127.0.0.1:9/tts/stream",
            synthesizeUrl = "http://127.0.0.1:9/tts/synthesize",
            streamSupport = object : TtsClient.StreamSupport {
                override fun isUnsupported(): Boolean = false
                override fun markUnsupported() {}
            },
        ),
        aguiFactory = { throw UnsupportedOperationException("stub") },
        bubble = object : VoiceSessionManager.BubbleSink {
            override fun show(text: String) {}
            override fun append(delta: String) {}
            override fun hide() {}
            override fun hideLater(delayMs: Long) {}
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun `resetForTest 后旧 scope 失效`() {
        val old = VoiceClientFactory.processScope
        assertTrue(old.isActive)
        VoiceClientFactory.resetForTest()
        assertFalse("旧 scope 必须被 cancel（变异锚：漏 cancel → 本行红）", old.isActive)
    }

    @Test
    fun `reset 后新 scope 活跃且 session 失效待重建`() {
        val oldScope = VoiceClientFactory.processScope
        VoiceClientFactory.session = stubSession()
        val seeded = VoiceClientFactory.session
        VoiceClientFactory.resetForTest()
        assertTrue("新 scope 活跃", VoiceClientFactory.processScope.isActive)
        assertNotSame("reset 后 scope 换新", oldScope, VoiceClientFactory.processScope)
        assertNull("seed 的 session 必失效（下次 session() DCL 重建，必非同一实例）", VoiceClientFactory.session)
        assertNotSame(seeded, VoiceClientFactory.session)
    }
}
