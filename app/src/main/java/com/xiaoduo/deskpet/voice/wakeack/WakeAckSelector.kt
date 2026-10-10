package com.xiaoduo.deskpet.voice.wakeack

import com.xiaoduo.deskpet.net.TtsVoiceDefaults
import java.security.MessageDigest

/**
 * 唤醒应答源选择器（S9-2，纯逻辑，零 Android import）。
 *
 * 兜底链（唤醒播放路径零网络的结构前提：select 只消费快照与可用性，不做任何 IO）：
 *  1. platform → wozai.wav（**永不读缓存**）
 *  2. cloud 且恰等于厂商默认三元组（provider+model+voice 全等）→ wozai-{provider}.wav
 *  3. cloud 且缓存命中 → PlayCache
 *  4. → 厂商资产 wozai-{provider}.wav
 *  5. → wozai.wav
 *  6. → Mute（任何失败不劣于现状：装配期已校验 wozai.wav，正常到不了这层）
 *
 * provider 路由镜像 [com.xiaoduo.deskpet.cloud.CloudClientFactory.ttsSynthesize]
 * （volcengine/aliyun 精确匹配，未知/空 → openai），否则「设置显示 X、应答播 Y」。
 */
object WakeAckSelector {

    /** 与 NetConfig.MODE_* 同值（契约测试锁定，防两处漂移）。 */
    const val MODE_CLOUD = "cloud"
    const val MODE_PLATFORM = "platform"

    /** 平台模式（及一切非 cloud 值）的现行应答资产。 */
    const val PLATFORM_ASSET = "wozai.wav"

    /** TTS 配置快照（决策输入；装配层从 NetConfig 读出，select 不回读配置）。 */
    data class Snapshot(
        val ttsMode: String,
        val provider: String,
        val model: String,
        val voice: String,
    )

    /** 可用性输入：缓存文件是否已在 + 资产名查询（assets.list → set::contains 注入，可测）。 */
    data class Availability(
        val cacheFileExists: Boolean,
        val assetExists: (String) -> Boolean,
    )

    sealed class Decision {
        data class PlayAsset(val name: String) : Decision()
        data class PlayCache(val fileName: String) : Decision()
        object Mute : Decision()
    }

    /** provider 路由：镜像 CloudClientFactory.ttsSynthesize 的 when 分发（else → openai）。 */
    fun routeProvider(raw: String): String = when (raw) {
        TtsVoiceDefaults.PROVIDER_VOLCENGINE -> TtsVoiceDefaults.PROVIDER_VOLCENGINE
        TtsVoiceDefaults.PROVIDER_ALIYUN -> TtsVoiceDefaults.PROVIDER_ALIYUN
        else -> TtsVoiceDefaults.PROVIDER_OPENAI
    }

    /** 厂商默认音色资产名（如 wozai-aliyun.wav，路由后 provider）。 */
    fun vendorAssetName(provider: String): String = "wozai-${routeProvider(provider)}.wav"

    /**
     * 缓存键：长度前缀编码保证任意字符串无碰撞——"a"+"b|c" 与 "a|b"+"c" 的朴素拼接会同键，
     * 长度前缀可唯一分解。同元组稳定，异 provider/model/voice 必异。
     */
    fun cacheKey(s: Snapshot): String {
        val p = routeProvider(s.provider)
        return "${p.length}~$p${s.model.length}~${s.model}${s.voice.length}~${s.voice}"
    }

    /** 恰等于厂商默认三元组（provider 路由后 model+voice 全等；model 换则音色族换）。 */
    fun isBundledDefault(s: Snapshot): Boolean {
        // S9-2 回归守卫：三元组全等判定（漏任一比较 → 默认三元组用例/needsPrefetch 恰默认态 → 红）
        val p = routeProvider(s.provider)
        return s.model == TtsVoiceDefaults.model(p) && s.voice == TtsVoiceDefaults.voice(p)
    }

    /**
     * 缓存文件名 = readable 段（sanitize+钳位）+ SHA-256 短后缀。
     * sanitize 把 `/`、`:`、CJK 等替换为 `_` 并钳位长度 → 文件名对任意输入安全；
     * hash 后缀区分 sanitize 碰撞（"a/b" vs "a_b"）；同 key 稳定、异 key 必异。
     */
    fun fileName(key: String): String {
        // S9-2 回归守卫：hash 后缀区分 sanitize 碰撞（删后缀 → a/b vs a_b 用例 → 红）
        val readable = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(MAX_READABLE).ifEmpty { "ack" }
        return "ack-$readable-${sha256Hex(key).take(HASH_LEN)}.pcm"
    }

    /** 兜底链主体（见类 KDoc；任何失败不劣于现状）。 */
    fun select(s: Snapshot, a: Availability): Decision {
        // S9-2 回归守卫：platform（及一切非 cloud 值）直接走平台资产、永不读缓存
        //（删此分支 → platform+缓存命中用例 → 红）
        if (s.ttsMode != MODE_CLOUD) return platformAssetOrMute(a)
        val vendor = vendorAssetName(s.provider)
        if (isBundledDefault(s) && a.assetExists(vendor)) return Decision.PlayAsset(vendor)
        if (a.cacheFileExists) return Decision.PlayCache(fileName(cacheKey(s)))
        if (a.assetExists(vendor)) return Decision.PlayAsset(vendor)
        return platformAssetOrMute(a)
    }

    /** 预取判定：platform / 恰等于厂商默认 / 缓存已在 → false（不预取）；其余 → true。 */
    fun needsPrefetch(s: Snapshot, cacheFileExists: Boolean): Boolean {
        if (s.ttsMode != MODE_CLOUD) return false
        if (isBundledDefault(s)) return false
        return !cacheFileExists
    }

    private fun platformAssetOrMute(a: Availability): Decision =
        if (a.assetExists(PLATFORM_ASSET)) Decision.PlayAsset(PLATFORM_ASSET) else Decision.Mute

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val MAX_READABLE = 48
    private const val HASH_LEN = 10
}
