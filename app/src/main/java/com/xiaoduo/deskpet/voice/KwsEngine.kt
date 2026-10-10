package com.xiaoduo.deskpet.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.xiaoduo.deskpet.util.PetLogger

/**
 * KWS 引擎封装（sherpa-onnx KeywordSpotter，Android 绑定层）：
 * 本类只剩 native 构建与适配，全部线程/门控/统计/release 时序逻辑在 [KwsCore]
 * （纯 JVM，KwsCoreTest 11 测 + KwsCoreHangoverTest 4 测；此前整类绑死 native 零测，审计 P1 竞态即藏于此）。
 *
 * 对账表逐项：
 * - keywords_threshold 0.3（08-10 真机验证值，锚定 [KwsDefaults]）/ num_threads 2 / provider cpu / 16kHz 直采直喂
 * - RMS 能量门控（默认 0.015，经 [gateProvider] 每次读取 = 校准写入即时生效）：
 *   rms<gate 整块不喂不判（防静音空转耗电）+ 挂起窗/gap-reset 保时间轴（S11-6 真机探针
 *   石锤「第一声必漏」机制后的增强，语义见 [KwsCore] KDoc，锚 [KwsDefaults.GATE_HANGOVER_BLOCKS]）
 * - 用法：acceptWaveform → 循环 isReady/decode/getResult → 命中 reset
 * - Int16 → float32（/32768）后喂 sherpa（知识包 §5）
 *
 * 检出率度量（KwsCore 落地）：命中行带流水号 #N + fed/gated 分母，
 * 每 60s 活动落 `kws stats:`，release 落汇总行。
 */
class KwsEngine(
    assetManager: AssetManager,
    gateProvider: () -> Float,
    onKeyword: (String) -> Unit,
    logger: (String) -> Unit = { msg -> PetLogger.i(TAG, msg) },
    modelDir: String = MODEL_DIR,
    keywordsThreshold: Float = KwsDefaults.KEYWORDS_THRESHOLD,
    /** S35：解码线程 Java 优先级，默认主引擎提档；影子通道显式传 MIN（观测不争抢）。 */
    decodeThreadPriority: Int = KwsCore.DEFAULT_DECODE_THREAD_PRIORITY,
) {

    private val spotter = KeywordSpotter(assetManager, buildConfig(modelDir, keywordsThreshold))
    private val stream: OnlineStream = spotter.createStream()

    private val core = KwsCore(
        native = SherpaNative(spotter, stream, logger),
        gateProvider = gateProvider,
        onKeyword = onKeyword,
        logger = logger,
        sampleRate = SAMPLE_RATE,
        queueCapacity = QUEUE_CAPACITY,
        decodeThreadPriority = decodeThreadPriority,
    )

    /** 麦块入口（采麦回调线程，100ms/3200B），语义见 [KwsCore.feed]。 */
    fun feed(block: ByteArray) = core.feed(block)

    /** 释放 native 资源（幂等；服务 onDestroy 调），时序保证见 [KwsCore.release]。 */
    fun release() = core.release()

    /** sherpa-onnx 真身 → [KwsCore.Native] 适配（唯一不可 JVM 测的薄层）。 */
    private class SherpaNative(
        private val spotter: KeywordSpotter,
        private val stream: OnlineStream,
        private val logger: (String) -> Unit,
    ) : KwsCore.Native {
        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
            stream.acceptWaveform(samples, sampleRate)

        override fun isReady(): Boolean = spotter.isReady(stream)

        override fun decode() = spotter.decode(stream)

        override fun resultKeyword(): String = spotter.getResult(stream).keyword

        override fun reset() = spotter.reset(stream)

        override fun release() {
            runCatching { stream.release() }
                .onFailure { logger("stream release failed: ${it.message}") }
            runCatching { spotter.release() }
                .onFailure { logger("spotter release failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "KwsEngine"
        const val MODEL_DIR = "kws-model"
        const val SAMPLE_RATE = 16_000

        /** 消费队列容量：64 块 ≈ 6.4s（与 AudioCapture 块缓冲同容量同策）。 */
        internal const val QUEUE_CAPACITY = 64

        /** 对账表 KWS 节逐项（主阈值 [KwsDefaults.KEYWORDS_THRESHOLD]=0.3 / 2线程 / cpu / 16k）。
         *  S22：阈值参数化——影子通道传 [KwsDefaults.SHADOW_KEYWORDS_THRESHOLD]。 */
        internal fun buildConfig(modelDir: String, keywordsThreshold: Float) = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                    joiner = "$modelDir/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                debug = false,
            ),
            keywordsFile = "$modelDir/keywords.txt",
            keywordsThreshold = keywordsThreshold,
            keywordsScore = 1.0f,
            maxActivePaths = 4,
            numTrailingBlanks = 1,
        )
    }
}
