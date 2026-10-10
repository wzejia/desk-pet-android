package com.xiaoduo.deskpet.voice.wakeack

import android.content.Context
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.TtsVoiceDefaults
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.audio.WavCodec
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 唤醒应答源解析器（S9-3，Android 胶水层；构造注入 lambda 化，JVM 可测——本类逻辑
 * 零直接 Android 调用，全部经构造闭包，[from] 才做真机接线）。
 *
 * 热路径（唤醒播放，零网络）：读配置快照 → availability（assets.list 的 wozai*.wav
 * memo 一次 + 缓存 exists）→ [WakeAckSelector.select] 兜底链 → 读字节（缓存 File 或
 * assets，两种源统一为 WAV 容器）→ [WavCodec.stripWavHeader] → 裸 PCM（24k/16bit/mono）。
 * 返回 null = 全链落空（[DynamicWakeAck] 抛 IOException → VSM 既有 catch 降级直进聆听）。
 *
 * 缓存与资产统一存 WAV：预取落盘前 [WavCodec.pcmToWav]（S9-4 `run-as cat` 拉回可直接
 * 复听取证），本类对两种源统一剥头。
 *
 * **每次 resolve 必打一行 source 日志**（S9-4 真机验收取证生命线，memo 命中也打）：
 * `I/WakeAck: source=asset:wozai-aliyun.wav provider=aliyun model=… voice=…` /
 * `source=cache:<fileName>` / `source=none`（仅非凭据字段）。
 *
 * memo：按 Decision 键缓存解析结果——Decision 相同不重读字节（唤醒热路径剩一次
 * exists stat，连续唤醒无可感延迟）。配置切换 → Decision 变 → 自动重读。
 */
class WakeAckResolver(
    private val snapshotProvider: () -> WakeAckSelector.Snapshot,
    private val assetLister: () -> Set<String>,
    private val assetReader: (String) -> ByteArray?,
    internal val cacheStore: WakeAckCacheStore,
    private val log: (String) -> Unit = { PetLogger.i(TAG, it) },
) {

    /** 解析产物：实际命中源（日志字段）+ 裸 PCM（null=落空）。 */
    private class Sourced(val source: String, val pcm: ByteArray?)

    /**
     * memo：Decision → 解析产物（ConcurrentHashMap，唤醒与预取线程并发安全）。
     * 同 Decision 不重读字节；Decision 集有限（资产名/缓存名），无需淘汰。
     */
    private val memo = ConcurrentHashMap<WakeAckSelector.Decision, Sourced>()

    @Volatile
    private var assetNamesMemo: Set<String>? = null

    /** 配置快照（预取 daemon 与本类 resolve 同路取快照，单一真理源）。 */
    fun snapshot(): WakeAckSelector.Snapshot = snapshotProvider()

    /** 快照对应缓存文件是否已在（预取 needsPrefetch 输入；一次 stat，非字节读）。 */
    fun cacheExists(s: WakeAckSelector.Snapshot): Boolean =
        cacheStore.exists(cacheFileName(s))

    /**
     * 全链解析（唤醒播放热路径，零网络）：select 兜底链 → 读字节 → 剥头。
     * null = 全链落空（Mute 或各层读/剥失败且无更低层）。
     */
    fun resolve(): ByteArray? {
        val snap = snapshotProvider()
        val names = assetNames()
        val cached = cacheExists(snap)
        val decision = WakeAckSelector.select(
            snap,
            WakeAckSelector.Availability(
                cacheFileExists = cached,
                assetExists = { it in names },
            ),
        )
        val result = memo[decision]
            ?: selectAndLoad(snap, names, cacheFileExists = cached)
                .also {
                    // FUNC-7：仅成功结果入 memo——NONE/兜底失败链（坏缓存、读失败、空资产）
                    // 不缓存，下次 resolve 重试，瞬态失败不进程级终身化、可自愈。
                    if (it.pcm != null) memo[decision] = it
                }
        // S9-3 回归守卫：source 日志行（删此 → S9-4 真机验收无取证链 → 红不了但验收必挂；
        // 测试以日志内容断言锁死格式）
        log("source=${result.source} provider=${snap.provider} model=${snap.model} voice=${snap.voice}")
        return result.pcm
    }

    /** 选一层并装载；该层失败（坏缓存/读失败/剥头失败）→ 链下一层，任何失败不劣于现状。 */
    private fun selectAndLoad(
        snap: WakeAckSelector.Snapshot,
        names: Set<String>,
        cacheFileExists: Boolean,
    ): Sourced {
        val d = WakeAckSelector.select(
            snap,
            WakeAckSelector.Availability(cacheFileExists) { it in names },
        )
        return when (d) {
            is WakeAckSelector.Decision.PlayCache ->
                // S9-3 回归守卫：坏缓存回退资产（缓存过 store validate 但 WAV 头坏 → 剥头失败，
                // 或 read 返 null）→ 重选下一层（删回退直接返 null → 坏缓存用例 → 红）
                cacheSourced(d.fileName) ?: selectAndLoad(snap, names, cacheFileExists = false)
            is WakeAckSelector.Decision.PlayAsset ->
                // 厂商资产读/剥失败 → 平台资产 wozai.wav；wozai 本身失败 → 全链落空
                assetSourced(d.name)
                    ?: if (d.name == WakeAckSelector.PLATFORM_ASSET) NONE
                    else assetSourced(WakeAckSelector.PLATFORM_ASSET) ?: NONE
            WakeAckSelector.Decision.Mute -> NONE
        }
    }

    /** 缓存层：read（store 内 validate，坏文件删+null）→ 剥头；任一失败 null 走下层。 */
    private fun cacheSourced(fileName: String): Sourced? =
        cacheStore.read(fileName)?.let { wav ->
            stripToPcm(wav)?.let { Sourced("cache:$fileName", it) }
        }

    /** 资产层：读字节 → 剥头；失败 null 走下层。 */
    private fun assetSourced(name: String): Sourced? =
        assetReader(name)?.let { wav ->
            stripToPcm(wav)?.let { Sourced("asset:$name", it) }
        }

    /** WAV → 裸 PCM；坏 RIFF（chunk 越界/无 data）返 null 走兜底链，不崩唤醒路径。 */
    private fun stripToPcm(wav: ByteArray): ByteArray? =
        runCatching { WavCodec.stripWavHeader(wav) }.getOrNull()

    /**
     * assets.list 的 wozai*.wav 名单，每实例 memo 一次（资产进程内不变，重复列目录浪费）。
     * FUNC-7：空集不 memo——首次 assets.list 失败被 [from] 接线层吞成空集时，memo 空集
     * 会让 vendor 层进程级终身查无资产；留空重列，下次 resolve 即自愈（每次落一行诊断）。
     */
    private fun assetNames(): Set<String> =
        assetNamesMemo ?: assetLister().also {
            if (it.isNotEmpty()) {
                assetNamesMemo = it
            } else {
                log("assets list empty (lister failure or no vendor assets), will re-list next resolve")
            }
        }

    private fun cacheFileName(s: WakeAckSelector.Snapshot): String =
        WakeAckSelector.fileName(WakeAckSelector.cacheKey(s))

    companion object {
        private const val TAG = "WakeAck"
        private val NONE = Sourced("none", null)
        private val WOZAI_ASSET_RE = Regex("wozai.*\\.wav")
        private const val CACHE_DIR = "wake_ack/v2"

        /**
         * 生产接线：快照镜像 [com.xiaoduo.deskpet.cloud.CloudClientFactory.ttsSynthesize]
         * 的 provider 路由与 model/voice 取值（volc=speaker/model、aliyun=model/voice、
         * 其余=cloudTtsModel/cloudTtsVoice），否则「设置显示 X、应答播 Y」。
         */
        fun from(context: Context): WakeAckResolver {
            val app = context.applicationContext
            return WakeAckResolver(
                snapshotProvider = {
                    val provider = WakeAckSelector.routeProvider(NetConfig.ttsCloudProvider(app))
                    val (model, voice) = when (provider) {
                        TtsVoiceDefaults.PROVIDER_VOLCENGINE ->
                            NetConfig.volcTtsModel(app) to NetConfig.volcTtsSpeaker(app)
                        TtsVoiceDefaults.PROVIDER_ALIYUN ->
                            NetConfig.aliyunTtsModel(app) to NetConfig.aliyunTtsVoice(app)
                        else -> NetConfig.cloudTtsModel(app) to NetConfig.cloudTtsVoice(app)
                    }
                    WakeAckSelector.Snapshot(
                        ttsMode = NetConfig.ttsMode(app),
                        provider = provider,
                        model = model,
                        voice = voice,
                    )
                },
                assetLister = {
                    runCatching { app.assets.list("").orEmpty() }
                        .getOrDefault(emptyArray())
                        .filter { WOZAI_ASSET_RE.matches(it) }
                        .toSet()
                },
                assetReader = { name ->
                    runCatching { app.assets.open(name).use { it.readBytes() } }.getOrNull()
                },
                cacheStore = WakeAckCacheStore(File(app.filesDir, CACHE_DIR)),
            )
        }
    }
}
