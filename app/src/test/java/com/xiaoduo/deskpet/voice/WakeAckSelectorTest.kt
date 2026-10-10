package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.TtsVoiceDefaults
import com.xiaoduo.deskpet.voice.wakeack.WakeAckSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WakeAckSelector 单测（S9-2）：兜底链全链 / platform 永不读缓存 / 三家默认映射 /
 * cacheKey 稳定与区分 / fileName sanitize 碰撞 / needsPrefetch 四态 / 未知 provider 路由。
 */
class WakeAckSelectorTest {

    private fun cloudSnap(
        provider: String = TtsVoiceDefaults.PROVIDER_ALIYUN,
        model: String = TtsVoiceDefaults.ALIYUN_MODEL,
        voice: String = TtsVoiceDefaults.ALIYUN_VOICE,
    ) = WakeAckSelector.Snapshot(WakeAckSelector.MODE_CLOUD, provider, model, voice)

    private fun platformSnap() = WakeAckSelector.Snapshot(
        WakeAckSelector.MODE_PLATFORM,
        TtsVoiceDefaults.PROVIDER_ALIYUN,
        TtsVoiceDefaults.ALIYUN_MODEL,
        TtsVoiceDefaults.ALIYUN_VOICE,
    )

    private fun avail(cache: Boolean = false, assets: Set<String> = emptySet()) =
        WakeAckSelector.Availability(cache) { it in assets }

    /** 生产典型资产集（wozai.wav + 三厂商）。 */
    private val fullAssets = setOf(
        WakeAckSelector.PLATFORM_ASSET,
        "wozai-aliyun.wav", "wozai-volcengine.wav", "wozai-openai.wav",
    )

    // ---- platform 模式 ----

    @Test
    fun `platform mode plays wozai wav and never reads cache`() {
        // S9-2 回归守卫：platform 永不读缓存（删 select 的 platform 分支 → 本用例 → 红）
        val d = WakeAckSelector.select(platformSnap(), avail(cache = true, assets = fullAssets))
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai.wav"), d)
    }

    @Test
    fun `platform mode mutes when wozai missing even if vendor assets exist`() {
        val d = WakeAckSelector.select(
            platformSnap(),
            avail(cache = true, assets = setOf("wozai-aliyun.wav", "wozai-openai.wav")),
        )
        assertEquals(WakeAckSelector.Decision.Mute, d)
    }

    // ---- 三家厂商默认三元组 → 内置资产 ----

    @Test
    fun `aliyun default tuple selects bundled aliyun asset even with cache present`() {
        // 恰等于厂商默认 → 资产优先于缓存（默认音色不消费缓存）
        val d = WakeAckSelector.select(cloudSnap(), avail(cache = true, assets = fullAssets))
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai-aliyun.wav"), d)
    }

    @Test
    fun `volcengine default tuple selects bundled volcengine asset`() {
        val d = WakeAckSelector.select(
            cloudSnap(TtsVoiceDefaults.PROVIDER_VOLCENGINE, TtsVoiceDefaults.VOLC_MODEL, TtsVoiceDefaults.VOLC_VOICE),
            avail(assets = fullAssets),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai-volcengine.wav"), d)
    }

    @Test
    fun `openai default tuple selects bundled openai asset`() {
        val d = WakeAckSelector.select(
            cloudSnap(TtsVoiceDefaults.PROVIDER_OPENAI, TtsVoiceDefaults.OPENAI_MODEL, TtsVoiceDefaults.OPENAI_VOICE),
            avail(assets = fullAssets),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai-openai.wav"), d)
    }

    @Test
    fun `default tuple with missing vendor asset falls through to wozai`() {
        // R2/R3 场景：volc 资产暂缺 → 走链落到平台资产（不劣于现状）
        val d = WakeAckSelector.select(
            cloudSnap(TtsVoiceDefaults.PROVIDER_VOLCENGINE, TtsVoiceDefaults.VOLC_MODEL, TtsVoiceDefaults.VOLC_VOICE),
            avail(cache = false, assets = setOf(WakeAckSelector.PLATFORM_ASSET)),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai.wav"), d)
    }

    // ---- 未知/空 provider 路由（镜像 CloudClientFactory else 分支）----

    @Test
    fun `unknown or empty provider routes to openai`() {
        for (raw in listOf("", "azure", "Volcengine", "MULTIMODAL")) {
            assertEquals(TtsVoiceDefaults.PROVIDER_OPENAI, WakeAckSelector.routeProvider(raw))
        }
        // 精确匹配仅小写全等
        assertEquals(TtsVoiceDefaults.PROVIDER_ALIYUN, WakeAckSelector.routeProvider("aliyun"))
        assertEquals(TtsVoiceDefaults.PROVIDER_VOLCENGINE, WakeAckSelector.routeProvider("volcengine"))
        // 未知 provider + openai 默认三元组 → openai 资产；cacheKey 也按路由后 provider
        val d = WakeAckSelector.select(
            cloudSnap("azure", TtsVoiceDefaults.OPENAI_MODEL, TtsVoiceDefaults.OPENAI_VOICE),
            avail(assets = fullAssets),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai-openai.wav"), d)
        assertEquals(
            WakeAckSelector.cacheKey(cloudSnap("azure", "m", "v")),
            WakeAckSelector.cacheKey(cloudSnap(TtsVoiceDefaults.PROVIDER_OPENAI, "m", "v")),
        )
    }

    // ---- 自定义三元组：缓存 → 厂商资产 → wozai → Mute 全链 ----

    @Test
    fun `custom tuple with cache hit plays cache`() {
        val snap = cloudSnap(voice = "custom-voice")
        val d = WakeAckSelector.select(snap, avail(cache = true, assets = fullAssets))
        assertEquals(
            WakeAckSelector.Decision.PlayCache(WakeAckSelector.fileName(WakeAckSelector.cacheKey(snap))),
            d,
        )
    }

    @Test
    fun `custom tuple without cache falls to vendor asset`() {
        val d = WakeAckSelector.select(
            cloudSnap(voice = "custom-voice"),
            avail(cache = false, assets = fullAssets),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai-aliyun.wav"), d)
    }

    @Test
    fun `vendor asset missing falls to platform wozai`() {
        val d = WakeAckSelector.select(
            cloudSnap(voice = "custom-voice"),
            avail(cache = false, assets = setOf(WakeAckSelector.PLATFORM_ASSET, "wozai-openai.wav")),
        )
        assertEquals(WakeAckSelector.Decision.PlayAsset("wozai.wav"), d)
    }

    @Test
    fun `all sources missing mutes`() {
        val d = WakeAckSelector.select(cloudSnap(voice = "custom-voice"), avail(cache = false))
        assertEquals(WakeAckSelector.Decision.Mute, d)
    }

    // ---- cacheKey / fileName ----

    @Test
    fun `cacheKey is stable per tuple and distinct across tuple parts`() {
        val a = cloudSnap(voice = "v1")
        assertEquals(WakeAckSelector.cacheKey(a), WakeAckSelector.cacheKey(a.copy()))
        // 异 provider / model / voice 必异
        assertNotEquals(
            WakeAckSelector.cacheKey(cloudSnap(TtsVoiceDefaults.PROVIDER_OPENAI, "m", "v")),
            WakeAckSelector.cacheKey(cloudSnap(TtsVoiceDefaults.PROVIDER_ALIYUN, "m", "v")),
        )
        assertNotEquals(
            WakeAckSelector.cacheKey(cloudSnap(model = "m1", voice = "v")),
            WakeAckSelector.cacheKey(cloudSnap(model = "m2", voice = "v")),
        )
        assertNotEquals(
            WakeAckSelector.cacheKey(cloudSnap(model = "m", voice = "v1")),
            WakeAckSelector.cacheKey(cloudSnap(model = "m", voice = "v2")),
        )
        // 长度前缀编码：朴素 "p|m|v" 拼接会同键的结构碰撞样本，这里必异
        assertNotEquals(
            WakeAckSelector.cacheKey(cloudSnap(model = "a", voice = "b|c")),
            WakeAckSelector.cacheKey(cloudSnap(model = "a|b", voice = "c")),
        )
    }

    @Test
    fun `fileName sanitizes unsafe chars and clamps length`() {
        // /、:、CJK → _；输出仅 [A-Za-z0-9_.-]
        val name = WakeAckSelector.fileName("openai|gpt-4o/mini:tts|小龙女")
        assertTrue(name.matches(Regex("""ack-[A-Za-z0-9_-]+-[0-9a-f]+\.pcm""")))
        // 超长段钳位（readable ≤ 48）
        val long = WakeAckSelector.fileName("p|" + "a".repeat(500) + "|v")
        assertTrue(long.length <= 4 + 48 + 1 + 10 + 4)
        // 同 key 稳定
        assertEquals(name, WakeAckSelector.fileName("openai|gpt-4o/mini:tts|小龙女"))
    }

    @Test
    fun `fileName hash suffix disambiguates sanitize collisions`() {
        // S9-2 回归守卫：hash 后缀区分 sanitize 碰撞（删后缀 → 两 key 同名 → 红）
        assertNotEquals(WakeAckSelector.fileName("x|y|a/b"), WakeAckSelector.fileName("x|y|a_b"))
        assertNotEquals(WakeAckSelector.fileName("x|y|a:b"), WakeAckSelector.fileName("x|y|a_b"))
    }

    // ---- needsPrefetch 四态 ----

    @Test
    fun `needsPrefetch covers four states`() {
        // 1) platform → false
        assertFalse(WakeAckSelector.needsPrefetch(platformSnap(), cacheFileExists = false))
        // 2) 恰等于厂商默认 → false（内置资产在场）
        assertFalse(
            WakeAckSelector.needsPrefetch(
                cloudSnap(TtsVoiceDefaults.PROVIDER_VOLCENGINE, TtsVoiceDefaults.VOLC_MODEL, TtsVoiceDefaults.VOLC_VOICE),
                cacheFileExists = false,
            ),
        )
        // 2b) model 换则音色族换 → true（三元组全等判定，非只看 voice）
        assertTrue(
            WakeAckSelector.needsPrefetch(
                cloudSnap(TtsVoiceDefaults.PROVIDER_VOLCENGINE, "other-model", TtsVoiceDefaults.VOLC_VOICE),
                cacheFileExists = false,
            ),
        )
        // 3) 自定义 + 缓存已在 → false
        assertFalse(WakeAckSelector.needsPrefetch(cloudSnap(voice = "custom"), cacheFileExists = true))
        // 4) 自定义 + 无缓存 → true
        assertTrue(WakeAckSelector.needsPrefetch(cloudSnap(voice = "custom"), cacheFileExists = false))
    }

    // ---- 契约：常量与 NetConfig 单一真理源 ----

    @Test
    fun `constants match NetConfig contract`() {
        // mode 值与 NetConfig 对齐（防两处漂移）；NetConfig 常量为编译期内联，JVM 无类加载副作用
        assertEquals(NetConfig.MODE_CLOUD, WakeAckSelector.MODE_CLOUD)
        assertEquals(NetConfig.MODE_PLATFORM, WakeAckSelector.MODE_PLATFORM)
        // TTS 默认值单一真理源：NetConfig 默认常量 == TtsVoiceDefaults（S9-2 NetConfig 去重后锁定）
        assertEquals(TtsVoiceDefaults.OPENAI_MODEL, NetConfig.DEFAULT_CLOUD_TTS_MODEL)
        assertEquals(TtsVoiceDefaults.OPENAI_VOICE, NetConfig.DEFAULT_CLOUD_TTS_VOICE)
        assertEquals("gpt-4o-mini-tts", NetConfig.DEFAULT_CLOUD_TTS_MODEL)
        assertEquals("alloy", NetConfig.DEFAULT_CLOUD_TTS_VOICE)
        assertEquals("qwen-audio-3.0-tts-flash", TtsVoiceDefaults.ALIYUN_MODEL)
        assertEquals("longanfengyue", TtsVoiceDefaults.ALIYUN_VOICE)
        assertEquals("seed-tts-2.0-standard", TtsVoiceDefaults.VOLC_MODEL)
        assertEquals("zh_female_qingxinnvsheng_uranus_bigtts", TtsVoiceDefaults.VOLC_VOICE)
        assertEquals("我在", TtsVoiceDefaults.TEXT)
    }
}
