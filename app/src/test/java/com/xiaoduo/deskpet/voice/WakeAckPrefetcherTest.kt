package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.api.TtsContract
import com.xiaoduo.deskpet.voice.audio.WavCodec
import com.xiaoduo.deskpet.voice.policy.PrefetchBackoff
import com.xiaoduo.deskpet.voice.wakeack.WakeAckAudio
import com.xiaoduo.deskpet.voice.wakeack.WakeAckCacheStore
import com.xiaoduo.deskpet.voice.wakeack.WakeAckPrefetcher
import com.xiaoduo.deskpet.voice.wakeack.WakeAckResolver
import com.xiaoduo.deskpet.voice.wakeack.WakeAckSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WakeAckPrefetcher.runWith 单测（ROB-15）：类 KDoc 契约「不让异常逃出线程」——
 * resolver.snapshot()/selector/validate/process/pcmToWav 等步骤异常不得逃出
 * （daemon 线程默认 uncaught handler 可杀进程）；失败记退避窗（key 已知时）。
 * resolver 用 fake 注入（同 WakeAckResolverTest 体例），缓存落临时目录。
 */
class WakeAckPrefetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pcm = WakeAckAudio.toBytes(ShortArray(6000) { (it % 2000).toShort() })

    /** cloud+aliyun 自定义音色 → 无缓存时 needsPrefetch=true → 走 synth。 */
    private val snap = WakeAckSelector.Snapshot(
        WakeAckSelector.MODE_CLOUD,
        TtsVoiceDefaultsForTest.PROVIDER,
        TtsVoiceDefaultsForTest.MODEL,
        "custom-voice",
    )

    /** 避免直接 import net 层默认值（与 WakeAckResolverTest 同值口径）。 */
    private object TtsVoiceDefaultsForTest {
        val PROVIDER = com.xiaoduo.deskpet.net.TtsVoiceDefaults.PROVIDER_ALIYUN
        val MODEL = com.xiaoduo.deskpet.net.TtsVoiceDefaults.ALIYUN_MODEL
    }

    private fun newResolver(snapshotProvider: () -> WakeAckSelector.Snapshot): WakeAckResolver =
        WakeAckResolver(
            snapshotProvider = snapshotProvider,
            assetLister = { emptySet() },
            assetReader = { null },
            cacheStore = WakeAckCacheStore(File(tmp.root, "wake_ack/v2")),
            log = { },
        )

    /** ROB-15 红测：snapshot() 抛（prefs/配置层异常形态）不得逃出 runWith。 */
    @Test
    fun `ROB-15 snapshot exception does not escape runWith`() {
        val backoff = PrefetchBackoff()
        val resolver = newResolver { throw IllegalStateException("prefs boom") }
        var synthCalled = false
        WakeAckPrefetcher.runWith(
            resolver,
            synth = { synthCalled = true; TtsContract.Result.Audio(pcm) },
            backoff = backoff,
            log = { },
        )
        assertFalse("snapshot 抛时不得走到 synth", synthCalled)
        assertFalse("key 未知不得记退避", backoff.shouldSkip("any", System.currentTimeMillis()))
    }

    /** synth 抛 → 记退避窗（同 key 10min 内跳过）+ 不逃出。 */
    @Test
    fun `ROB-15 synth exception records backoff and does not escape`() {
        val backoff = PrefetchBackoff()
        val resolver = newResolver { snap }
        val key = WakeAckSelector.cacheKey(snap)
        WakeAckPrefetcher.runWith(
            resolver,
            synth = { throw RuntimeException("net boom") },
            backoff = backoff,
            log = { },
        )
        assertTrue("失败须记退避窗", backoff.shouldSkip(key, System.currentTimeMillis()))
    }

    /** 成功路径锚：写缓存成功 → 退避不记（应可立即再跑）+ 缓存在场。 */
    @Test
    fun `ROB-15 success path writes cache and clears backoff`() {
        val backoff = PrefetchBackoff()
        val resolver = newResolver { snap }
        WakeAckPrefetcher.runWith(
            resolver,
            synth = { TtsContract.Result.Audio(pcm) },
            backoff = backoff,
            log = { },
        )
        val key = WakeAckSelector.cacheKey(snap)
        assertTrue("缓存须落盘（WAV 容器）", resolver.cacheExists(snap))
        assertFalse("成功须清退避账", backoff.shouldSkip(key, System.currentTimeMillis()))
    }

    /** 已有缓存 → needsPrefetch=false → 不打 synth（幂等锚）。 */
    @Test
    fun `ROB-15 existing cache short-circuits before synth`() {
        val backoff = PrefetchBackoff()
        val resolver = newResolver { snap }
        val name = WakeAckSelector.fileName(WakeAckSelector.cacheKey(snap))
        assertTrue(resolver.cacheStore.write(name, WavCodec.pcmToWav(pcm, 24000)))
        var synthCalled = false
        WakeAckPrefetcher.runWith(
            resolver,
            synth = { synthCalled = true; TtsContract.Result.Audio(pcm) },
            backoff = backoff,
            log = { },
        )
        assertFalse("缓存已在不得重打 synth", synthCalled)
    }
}
