package com.xiaoduo.deskpet.voice

import android.content.Context
import com.xiaoduo.deskpet.cloud.CloudClientFactory
import com.xiaoduo.deskpet.net.TtsVoiceDefaults
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.api.TtsContract.Result
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 唤醒应答冷路径预取器（S9-3，方案 C 的「保存即合成 + 启动补漏」半边）。
 *
 * 触发点：设置页保存（SettingsScreen）+ 语音会话装配尾（VoiceClientFactory.session）。
 * caller 线程**仅 AtomicBoolean CAS 占位（零 IO）**；合成/校验/写盘全在
 * daemon Thread("wake-ack-prefetch")，绝不与唤醒播放热路径交叉（热路径零网络约束）。
 *
 * 链路：resolver 同路取 snapshot/availability → [WakeAckSelector.needsPrefetch]
 * false 即退（platform / 恰等于厂商默认 / 缓存已在）→ [PrefetchBackoff] 退避
 * （同 key 失败 10min 窗内跳过）→ [CloudClientFactory.ttsSynthesize]（与对话链
 * 同一函数，音色=对话音色由构造保证：openai 分支 voice=default → defaultVoice=
 * NetConfig.cloudTtsVoice；aliyun/volc 分支直接读 NetConfig）→ validate →
 * process → pcmToWav → 原子写缓存（key/fileName 用 selector 同一函数，与播放侧对账）。
 *
 * 失败全程静默（仅 PetLogger 日志，无 Toast 无状态行——后台补漏不该打扰用户），
 * 兜底链保证任何失败不劣于现状（厂商资产/wozai.wav 照播）。
 */
object WakeAckPrefetcher {

    private const val TAG = "WakeAckPrefetch"
    private const val THREAD_NAME = "wake-ack-prefetch"

    /** 单飞标志：caller 线程 CAS 置位，daemon finally 清（守卫四问①：谁开窗谁关窗）。 */
    private val inflight = AtomicBoolean(false)

    /** 失败退避台账（进程内存；同 key 失败 10min 窗内不再打同一合成）。 */
    private val backoff = PrefetchBackoff()

    /**
     * 预取入口（幂等可重入）：CAS 拿不到=已在飞，直接退（零等待零排队）。
     * 保存连点 / session 装配与保存交叠：同一时刻至多一个 daemon 在跑（守卫四问④）。
     */
    fun maybePrefetch(context: Context) {
        if (!inflight.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread(
            {
                try {
                    run(app)
                } finally {
                    // S9-3 回归守卫：finally 清单飞标志，异常路径也不留死锁
                    //（删此 → 一次异常后标志永置位，预取永久哑掉，改配置也不再补）
                    inflight.set(false)
                }
            },
            THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** daemon 线程主体：任何一步落空即退，不让异常逃出线程（守卫四问②：异常路径终态）。 */
    private fun run(app: Context) {
        val resolver = WakeAckResolver.from(app)
        runWith(resolver, synth = { CloudClientFactory.ttsSynthesize(app, TtsVoiceDefaults.TEXT) })
    }

    /**
     * 预取主体（注入化，JVM 可测；ROB-15）：resolver/selector/validate/process/pcmToWav
     * 等步骤整体护栏——任何异常不逃出线程（线程默认 uncaught handler 可杀进程，违背类
     * KDoc「不让异常逃出线程」），key 已知时记退避窗。
     */
    internal fun runWith(
        resolver: WakeAckResolver,
        synth: () -> Result,
        backoff: PrefetchBackoff = this.backoff,
        log: (String) -> Unit = { PetLogger.w(TAG, it) },
    ) {
        // ROB-15：单层整体兜底——此前仅 ttsSynthesize/cacheStore.write 有护栏，
        // resolver.snapshot()/selector/validate/process/pcmToWav 裸奔异常直达线程默认
        // uncaught handler（可杀进程）。catch(Exception)（Error 级如 OOM 不吞，照旧上抛）。
        var key: String? = null
        try {
            val snap = resolver.snapshot()
            if (!WakeAckSelector.needsPrefetch(snap, resolver.cacheExists(snap))) return
            key = WakeAckSelector.cacheKey(snap)
            if (backoff.shouldSkip(key, System.currentTimeMillis())) return
            val result = synth()
            when (result) {
                is Result.Error -> fail(backoff, key, result.message, log)
                is Result.Audio -> synthOk(resolver, backoff, key, result.pcm, log)
            }
        } catch (e: Exception) {
            log("prefetch aborted: ${e.javaClass.simpleName}: ${e.message}" +
                (key?.let { " (key=$it, backoff ${PrefetchBackoff.DEFAULT_WINDOW_MS / 60000}min)" } ?: ""))
            key?.let { backoff.recordFailure(it, System.currentTimeMillis()) }
        }
    }

    /** 合成成功 → 校验 → 后处理 → WAV 封装 → 原子写；任一步失败记退避+静默日志。 */
    private fun synthOk(
        resolver: WakeAckResolver,
        backoff: PrefetchBackoff,
        key: String,
        raw: ByteArray,
        log: (String) -> Unit,
    ) {
        if (!WakeAckAudio.validate(raw)) {
            fail(backoff, key, "synth pcm invalid (${raw.size}B, validate 拒)", log)
            return
        }
        val processed = WakeAckAudio.toBytes(WakeAckAudio.process(WakeAckAudio.toSamples(raw)))
        // 缓存统一 WAV 容器：S9-4 run-as 拉回可直接复听；resolver 侧读时统一剥头
        val wav = WavCodec.pcmToWav(processed, RevealScheduler.TTS_SAMPLE_RATE)
        val fileName = WakeAckSelector.fileName(key)
        val written = try {
            resolver.cacheStore.write(fileName, wav)
        } catch (e: Exception) {
            fail(backoff, key, "cache write threw: ${e.message}", log)
            return
        }
        if (!written) {
            fail(backoff, key, "cache write failed", log)
            return
        }
        backoff.recordSuccess(key)
        log("prefetch saved: $fileName (${wav.size}B wav, pcm ${processed.size}B)")
    }

    /** 失败终态：记退避窗（10min 内同 key 不再打）+ 静默日志（无 Toast/状态行）。 */
    private fun fail(backoff: PrefetchBackoff, key: String, reason: String, log: (String) -> Unit) {
        backoff.recordFailure(key, System.currentTimeMillis())
        log("prefetch failed: $reason (backoff ${PrefetchBackoff.DEFAULT_WINDOW_MS / 60000}min)")
    }
}
