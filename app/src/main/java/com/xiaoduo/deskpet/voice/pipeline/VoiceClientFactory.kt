package com.xiaoduo.deskpet.voice.pipeline

import com.xiaoduo.deskpet.device.DeviceClient
import com.xiaoduo.deskpet.device.DeviceIntentMatcher
import com.xiaoduo.deskpet.device.ShelfMcpBridge
import com.xiaoduo.deskpet.device.ShelfNavIntentMatcher
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.NetEndpoints
import com.xiaoduo.deskpet.api.BubbleOwner
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.wakeack.DynamicWakeAck
import com.xiaoduo.deskpet.voice.wakeack.WakeAckPrefetcher
import com.xiaoduo.deskpet.voice.wakeack.WakeAckResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
        override fun show(text: String) {
            val svc = PetOverlayService.instance ?: return
            voiceRound = svc.showBubble(BubbleOwner.VOICE, text)
        }
        override fun append(delta: String) {
            PetOverlayService.instance?.appendBubble(BubbleOwner.VOICE, voiceRound, delta)
        }
        override fun hide() {
            PetOverlayService.instance?.hideBubble(BubbleOwner.VOICE, voiceRound)
        }
        override fun hideLater(delayMs: Long) {
            PetOverlayService.instance?.hideBubbleLater(BubbleOwner.VOICE, voiceRound, delayMs)
        }
    }

    /**
     * S49 指示层出口 → PetOverlayService 服务级 API（overlay 未运行时 no-op）。
     * 与 OverlayBubbleSink 共用 voiceRound 登记：指示泡 show 认领的新 round 同样是
     * 后续回复 delta append 的令牌基础（原「在听…」文字泡同款接管语义）。
     */
    private object OverlayIndicatorSink : VoiceSessionManager.IndicatorSink {
        override fun show(mode: VoiceSessionManager.IndicatorMode) {
            val svc = PetOverlayService.instance ?: return
            voiceRound = svc.showVoiceIndicator(mode)
        }
        override fun onLevel(rms: Float) {
            PetOverlayService.instance?.updateVoiceIndicatorLevel(rms)
        }
        override fun hide() {
            PetOverlayService.instance?.hideVoiceIndicator()
        }
    }

    /**
     * 进程级协程作用域（ocr[53]/thread-discipline 件 3 定案：自 session() 内联 hoist 为
     * 命名属性 + 显式契约）。
     *
     * 契约：与 [session] 单例同生命周期——进程内只此一份、生产路径**永不 cancel**、
     * 不暴露生产 teardown 路径（session 单例永不重建是显式前提：VSM 持有的
     * AudioCapture/播放器资源由该 scope 承载）。唯一重建入口 = [resetForTest]（测试专用）。
     */
    @Volatile
    internal var processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private set

    /**
     * 件 3 测试专用 reset（生产禁调）：cancel 旧 scope（其上 VSM 协程全量拆解）+
     * session 单例失效（下次 [session] 调用走 DCL 重建新实例、挂新 scope）+ 重建空 scope。
     * 变异锚：漏 cancel 旧 scope → VoiceClientFactoryProcessScopeTest 用例 1 红。
     */
    @VisibleForTesting
    internal fun resetForTest() {
        val old = processScope
        old.cancel()
        session = null
        processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** 进程内 VoiceSessionManager 单例。internal var = 件 3 测试缝（seed/失效断言用；
     *  生产代码只经 [session] 装配读写）。 */
    @Volatile
    internal var session: VoiceSessionManager? = null

    /** S49 语音侧 round 登记处（文字泡/指示泡共用：谁 show 谁接管，append/hide 带同一 round）。 */
    @Volatile
    private var voiceRound = 0

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
                // S49 指示层：仅 OVERLAY 载体生效（全屏页有声波/三点原生指示，悬浮窗隐藏期间窗口已 GONE）；
                // carrier 每调用现读，在途切换后续调用即改投（与 CarrierBubbleSink 同语义）
                indicator = object : VoiceSessionManager.IndicatorSink {
                    override fun show(mode: VoiceSessionManager.IndicatorMode) {
                        if (replyCarrier == ReplyCarrier.OVERLAY) OverlayIndicatorSink.show(mode)
                    }
                    override fun onLevel(rms: Float) {
                        if (replyCarrier == ReplyCarrier.OVERLAY) OverlayIndicatorSink.onLevel(rms)
                    }
                    override fun hide() {
                        if (replyCarrier == ReplyCarrier.OVERLAY) OverlayIndicatorSink.hide()
                    }
                },
                scope = processScope,
                wakeAck = wakeAck(app),
                // S18：VAD 阈值校准联动（每块现读，校准写入即时生效）
                vadSpeechLevel = { Prefs.vadSpeechLevel(app) },
                // S20：MCP 结果播报开关（每句现读，切换即时生效）
                speakMcpResultProvider = { Prefs.speakMcpResult(app) },
                // S21：工具轮后需重新唤醒（工具轮结束回 IDLE，KWS 守门）
                rewakeAfterToolCallProvider = { Prefs.rewakeAfterToolCall(app) },
                // S23：播放期回声消除实验开关（每块现读，切换即时生效）
                echoCancelProvider = { Prefs.echoCancelEnabled(app) },
                // S24：App 内播报音量（每轮现读，滑杆即时生效）
                ttsVolumeProvider = { Prefs.ttsVolume(app) },
                // S26 设备技能（整理台/净化柜本地意图）：地址空=禁用；OkHttp 单例共享
                // （NO_PROXY 局域网直连），每轮现读地址（改设置即时生效）
                deviceSkillMatcher = if (Prefs.deviceBaseUrl(app).isEmpty()) null
                else { text -> DeviceIntentMatcher.match(text) },
                deviceSkillExecutor = if (Prefs.deviceBaseUrl(app).isEmpty()) null
                else { cmd ->
                    DeviceClient(http = deviceHttp, baseUrl = Prefs.deviceBaseUrl(app)).execute(cmd)
                },
                // S46 密集架页面导航（本地意图）：shelfBaseUrl 空=禁用；同步 POST mcpUiNavigate，
                // 成败转 speech（语音轮 TTS 播报，无自带播报不置工具轮标记）
                shelfNavMatcher = if (Prefs.shelfBaseUrl(app).isEmpty()) null
                else { text -> ShelfNavIntentMatcher.match(text) },
                shelfNavExecutor = if (Prefs.shelfBaseUrl(app).isEmpty()) null
                else { page ->
                    val ok = shelfMcpClient(app).navigate(page)
                    if (ok) ShelfNavIntentMatcher.navSpeech(page) // S62：共用回执（home=已回到首页）
                    else "密集架联动不可用，请检查网络后重试"
                },
                // S41：密集架 MCP 动画联动桥（TOOL_CALL_START 工具名 → POST mcpUiSync；
                // baseUrl 每次调用现读 Prefs，改设置即时生效；失败只记日志不拖管线）
                shelfMcpSync = { tool, runId -> shelfMcpClient(app).syncAsync(tool, runId) },
                // S43：结果卡转发（run 收尾最后一次工具结果 → POST mcpUiResult，守卫同上）
                shelfMcpOutcome = { oc ->
                    shelfMcpClient(app).resultAsync(oc.toolCallName, oc.content, oc.argsJson, oc.runId) // S67：轮次边界
                },
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
        return DynamicWakeAck(
            resolve = { wakeAckResolver(context).resolve() },
            volumeProvider = { Prefs.ttsVolume(context) },
        )
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

    /** S26 设备技能共享连接（NO_PROXY 局域网直连）。 */
    private val deviceHttp by lazy { DeviceClient.defaultHttp() }

    /** S41 桥客户端进程内单例（Client 无会话状态、baseUrl provider 每次调用现读，
     *  与 wakeAckResolver 同款 @Volatile+双检装配）。 */
    @Volatile
    private var shelfMcpClient: ShelfMcpBridge.Client? = null

    private fun shelfMcpClient(context: Context): ShelfMcpBridge.Client =
        shelfMcpClient ?: synchronized(this) {
            shelfMcpClient ?: ShelfMcpBridge.Client(
                baseUrl = { Prefs.shelfBaseUrl(context.applicationContext) },
                http = shelfMcpHttp,
                logger = { msg -> PetLogger.i("ShelfMcpBridge", msg) },
            ).also { shelfMcpClient = it }
        }

    private val shelfMcpHttp by lazy { ShelfMcpBridge.defaultHttp() }

private const val TAG = "VoiceClientFactory"
    private const val WOZAI_ASSET = "wozai.wav"
    private const val RIFF_HEADER_BYTES = 44
}
