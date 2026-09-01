package com.xiaoduo.deskpet.voice

import android.content.Context
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.NetEndpoints
import com.xiaoduo.deskpet.ui.overlay.BubbleOwner
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 语音链路 Android 装配层（IO 胶水，不放协议逻辑）：
 * 端点取 NetConfig（默认内网生产地址），OkHttp 用全局 NO_PROXY+白名单单例，
 * TTS 流式 404 永久记忆落独立 prefs 文件（不与端点配置混存）。
 */
object VoiceClientFactory {

    fun asr(context: Context): AsrClient = AsrClient(
        http = HttpClients.shared,
        endpoint = NetConfig.asrBase(context.applicationContext),
        logger = { msg -> PetLogger.i("AsrClient", msg) },
    )

    fun tts(context: Context): TtsClient {
        val base = NetConfig.ttsBase(context.applicationContext)
        return TtsClient(
            http = HttpClients.shared,
            streamUrl = NetEndpoints.ttsStreamUrl(base),
            synthesizeUrl = NetEndpoints.ttsSynthesizeUrl(base),
            streamSupport = PrefsStreamSupport(context.applicationContext),
            logger = { msg -> PetLogger.i("TtsClient", msg) },
        )
    }

    /** TTS 流式 404 永久记忆（§2.4：流式 404 → 永久记住走非流式）。 */
    private class PrefsStreamSupport(context: Context) : TtsClient.StreamSupport {
        private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun isUnsupported(): Boolean = sp.getBoolean(KEY, false)

        override fun markUnsupported() {
            sp.edit().putBoolean(KEY, true).apply()
        }

        companion object {
            private const val FILE = "xiaoduo_voice"
            private const val KEY = "tts_stream_unsupported"
        }
    }

    /**
     * 气泡出口 → S3 PetOverlayService 服务级 API（overlay 未运行时 no-op）。
     * round/owner 令牌（backlog #4）：每次 show 认领新语音 round（接管气泡），
     * 后续 append/hide 带同一 round；被文字轮接管后本 round 调用自动失效（服务侧丢弃+日志）。
     */
    private object OverlayBubbleSink : VoiceSessionManager.BubbleSink {
        @Volatile
        private var round = 0

        override fun show(text: String) {
            val svc = PetOverlayService.instance ?: return
            round = svc.showBubble(BubbleOwner.VOICE, text)
        }
        override fun append(delta: String) {
            PetOverlayService.instance?.appendBubble(BubbleOwner.VOICE, round, delta)
        }
        override fun hide() {
            PetOverlayService.instance?.hideBubble(BubbleOwner.VOICE, round)
        }
        override fun hideLater(delayMs: Long) {
            PetOverlayService.instance?.hideBubbleLater(BubbleOwner.VOICE, round, delayMs)
        }
    }

    @Volatile
    private var session: VoiceSessionManager? = null

    /** 唤醒应答解析器进程内单例（S9-3：memo 挂实例上，跨唤醒复用；播放热路径共用）。 */
    @Volatile
    private var resolver: WakeAckResolver? = null

    /**
     * S7-d 回复载体（全屏语音模式）：全屏页进入置 FULLSCREEN、退出恢复 OVERLAY。
     * CarrierBubbleSink 每次调用现读本值转发，在途轮中途切换后续调用即改投。
     */
    @Volatile
    var replyCarrier: ReplyCarrier = ReplyCarrier.OVERLAY

    /** 进程内 VoiceSessionManager 单例（设置页语音自检 / S5 KWS 共用）。 */
    fun session(context: Context): VoiceSessionManager {
        val app = context.applicationContext
        val s = session ?: synchronized(this) {
            session ?: VoiceSessionManager(
                capture = AudioCapture(sourceMode = Prefs.micSourceMode(app)),
                asr = asr(app),
                tts = tts(app),
                aguiFactory = { AguiClientFactory.create(app) },
                appContext = app,
                // S7-d：载体分发（overlay 分支 round 令牌机制零改动，fullscreen 恒 no-op）
                bubble = CarrierBubbleSink(
                    carrier = { replyCarrier },
                    overlay = OverlayBubbleSink,
                    fullscreen = FullscreenBubbleSink,
                ),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                wakeAck = wakeAck(app),
                // S18：VAD 阈值校准联动（每块现读，校准写入即时生效）
                vadSpeechLevel = { Prefs.vadSpeechLevel(app) },
                // S20：MCP 结果播报开关（每句现读，切换即时生效）
                speakMcpResultProvider = { Prefs.speakMcpResult(app) },
            ).also { session = it }
        }
        // S9-3 冷路径接线：装配完成后触发应答音预取（保存即合成的「启动补漏」半边）。
        // 必须在 synchronized 块外——caller 线程仅 CAS 占位零 IO，不持锁碰网络
        WakeAckPrefetcher.maybePrefetch(app)
        return s
    }

    /**
     * #9 唤醒应答「我在」装配（S9-3 动态化）：装配期仍读 assets/wozai.wav 粗校验
     * （保证兜底链最底层资产在场，生产行为矩阵与现状对齐），合法 → [DynamicWakeAck]
     * （运行时经 [WakeAckResolver] 按当前 TTS 配置解析：缓存→厂商资产→wozai.wav，
     * 播放路径零网络；cancel 语义自旧 AssetWakeAck 迁入 DynamicWakeAck）。
     * 文件缺失/头非法 → log + null（VSM 降级：唤醒直接进 LISTENING，不致命）。
     */
    private fun wakeAck(context: Context): VoiceSessionManager.WakeAck? {
        val wav = runCatching {
            context.assets.open(WOZAI_ASSET).use { it.readBytes() }
        }.getOrElse {
            PetLogger.w(TAG, "wozai.wav load failed, wake-ack disabled: ${it.message}")
            return null
        }
        if (!isStandardWav(wav)) {
            PetLogger.w(TAG, "wozai.wav header invalid (need 44B RIFF PCM), wake-ack disabled: ${wav.size}B")
            return null
        }
        return DynamicWakeAck { wakeAckResolver(context).resolve() }
    }

    /** 解析器单例：memo/资产名单 memo 挂实例上，跨唤醒复用（线程安全：@Volatile+双检）。 */
    private fun wakeAckResolver(context: Context): WakeAckResolver =
        resolver ?: synchronized(this) {
            resolver ?: WakeAckResolver.from(context.applicationContext)
                .also { resolver = it }
        }

    /** 标准 44B 头 RIFF/WAVE 粗检（非标准多 chunk 头判非法走降级，不猜偏移）。 */
    private fun isStandardWav(bytes: ByteArray): Boolean =
        bytes.size > RIFF_HEADER_BYTES &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    private const val TAG = "VoiceClientFactory"
    private const val WOZAI_ASSET = "wozai.wav"
    private const val RIFF_HEADER_BYTES = 44
}
