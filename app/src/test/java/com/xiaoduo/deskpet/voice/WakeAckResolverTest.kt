package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.net.TtsVoiceDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WakeAckResolver 单测（S9-3，fake 注入零 Android：快照/资产名单/资产读/日志全构造注入，
 * 缓存用真 WakeAckCacheStore 落临时目录——读路径本身要过 store validate + 剥头）。
 *
 * 票据四测：①缓存在场读缓存字节 ②坏缓存回退资产（S9-3 回归守卫）③memo 命中不重读
 * ④Decision 变化重读。
 */
class WakeAckResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()

    /** 资产桩：名单 + 字节 + 读计数（memo 断言锚）。 */
    private class FakeAssets(val names: Set<String>, val data: Map<String, ByteArray>) {
        var reads = 0
        val reader: (String) -> ByteArray? = { name -> reads++; data[name] }
    }

    /** 三源可分辨 PCM：全过 validate 窗（长度偶、∈[9600,144000]、峰≥1000）。 */
    private val pcmA = WakeAckAudio.toBytes(ShortArray(6000) { (it % 2000).toShort() }) // wozai.wav
    private val pcmB = WakeAckAudio.toBytes(ShortArray(6500) { (it % 1700).toShort() }) // wozai-aliyun.wav
    private val pcmC = WakeAckAudio.toBytes(ShortArray(7000) { (it % 1500).toShort() }) // 缓存

    private val assetNames = setOf("wozai.wav", "wozai-aliyun.wav")

    private fun assets() = FakeAssets(
        assetNames,
        mapOf(
            "wozai.wav" to WavCodec.pcmToWav(pcmA, 24000),
            "wozai-aliyun.wav" to WavCodec.pcmToWav(pcmB, 24000),
        ),
    )

    private fun newStore() = WakeAckCacheStore(File(tmp.root, "wake_ack/v2"))

    private fun newResolver(
        snap: () -> WakeAckSelector.Snapshot,
        store: WakeAckCacheStore,
        assets: FakeAssets,
    ) = WakeAckResolver(
        snapshotProvider = snap,
        assetLister = { assets.names },
        assetReader = assets.reader,
        cacheStore = store,
        log = { logs.add(it) },
    )

    /** platform（及一切非 cloud）→ 恒 wozai.wav，永不读缓存。 */
    private val platformSnap = WakeAckSelector.Snapshot(
        WakeAckSelector.MODE_PLATFORM, "openai", "gpt-4o-mini-tts", "alloy",
    )

    /** cloud+aliyun 默认三元组 → wozai-aliyun.wav。 */
    private val defaultAliyunSnap = WakeAckSelector.Snapshot(
        WakeAckSelector.MODE_CLOUD,
        TtsVoiceDefaults.PROVIDER_ALIYUN,
        TtsVoiceDefaults.ALIYUN_MODEL,
        TtsVoiceDefaults.ALIYUN_VOICE,
    )

    /** cloud+aliyun 自定义音色（非默认三元组）→ 缓存命中时 PlayCache。 */
    private val customAliyunSnap = WakeAckSelector.Snapshot(
        WakeAckSelector.MODE_CLOUD,
        TtsVoiceDefaults.PROVIDER_ALIYUN,
        TtsVoiceDefaults.ALIYUN_MODEL,
        "custom-voice",
    )

    @Test
    fun `cache hit returns cached pcm with cache source log`() {
        val snap = customAliyunSnap
        val name = WakeAckSelector.fileName(WakeAckSelector.cacheKey(snap))
        val store = newStore()
        val assets = assets()
        // 预取侧产物形态：WAV 容器（pcmToWav）落缓存，resolver 读时剥头
        assertTrue(store.write(name, WavCodec.pcmToWav(pcmC, 24000)))
        val resolver = newResolver({ snap }, store, assets)

        val out = resolver.resolve()

        assertArrayEquals("缓存命中须返回缓存 PCM（剥头后）", pcmC, out)
        assertEquals("缓存命中不得读资产", 0, assets.reads)
        assertEquals("每次 resolve 必打一行日志", 1, logs.size)
        assertTrue("source 行须为 cache:$name，实际=${logs[0]}", logs[0].contains("source=cache:$name"))
    }

    @Test
    fun `corrupt cache falls back to vendor asset`() {
        // S9-3 回归守卫：坏缓存回退资产——缓存字节过 store validate（长偶/窗内/峰够）
        // 但 WAV 头坏（fmt chunk size 越界，剥头必抛）→ 回退厂商资产并返回资产 PCM。
        // 删 selectAndLoad 的 PlayCache 失败回退分支 → resolve 返 null → 红。
        val snap = customAliyunSnap
        val name = WakeAckSelector.fileName(WakeAckSelector.cacheKey(snap))
        val store = newStore()
        val assets = assets()
        val bad = WavCodec.pcmToWav(pcmC, 24000).also {
            // 偏移 16 起 LE 写 0x7FFFFFFF（fmt chunk size）：validate 照过（峰 32767），
            // stripWavHeader 的 chunk 越界 require 必抛
            it[16] = 0xFF.toByte(); it[17] = 0xFF.toByte()
            it[18] = 0xFF.toByte(); it[19] = 0x7F.toByte()
        }
        assertTrue("坏 WAV 须先骗过 store validate 才能测 resolver 层回退", store.write(name, bad))
        val resolver = newResolver({ snap }, store, assets)

        val out = resolver.resolve()

        assertArrayEquals("坏缓存须回退厂商资产 PCM", pcmB, out)
        assertEquals(1, assets.reads)
        assertTrue("回退后 source 行须指资产，实际=${logs[0]}", logs[0].contains("source=asset:wozai-aliyun.wav"))
    }

    @Test
    fun `same decision memo hit does not re-read`() {
        val store = newStore()
        val assets = assets()
        val resolver = newResolver({ platformSnap }, store, assets)

        val first = resolver.resolve()
        val second = resolver.resolve()

        assertArrayEquals(pcmA, first)
        assertArrayEquals("memo 命中须返回同一 PCM", first, second)
        assertEquals("同 Decision 不得重读资产字节", 1, assets.reads)
        assertEquals("memo 命中也必打 source 日志（S9-4 取证）", 2, logs.size)
    }

    @Test
    fun `decision change re-reads source`() {
        val store = newStore()
        val assets = assets()
        var snap = platformSnap
        val resolver = newResolver({ snap }, store, assets)

        assertArrayEquals(pcmA, resolver.resolve()) // PlayAsset(wozai.wav)
        assertEquals(1, assets.reads)

        snap = defaultAliyunSnap // 配置换 → PlayAsset(wozai-aliyun.wav)，Decision 变
        assertArrayEquals("Decision 变化须读到新源 PCM", pcmB, resolver.resolve())
        assertEquals("Decision 变化必须重读", 2, assets.reads)

        snap = platformSnap // 换回：按 Decision 键的 memo 命中
        assertArrayEquals(pcmA, resolver.resolve())
        assertEquals("回到已知 Decision 走 memo 不重读", 2, assets.reads)
    }
}
