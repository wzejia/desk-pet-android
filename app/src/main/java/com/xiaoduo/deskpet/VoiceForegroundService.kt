package com.xiaoduo.deskpet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.util.FeedReleaseGate
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.VoiceStateRouter
import com.xiaoduo.deskpet.voice.audio.OnceGate
import com.xiaoduo.deskpet.voice.kws.KwsCore
import com.xiaoduo.deskpet.voice.kws.KwsDefaults
import com.xiaoduo.deskpet.voice.kws.KwsEngine
import com.xiaoduo.deskpet.voice.audio.AmbientCalibrator
import com.xiaoduo.deskpet.voice.VoiceClientFactory

/**
 * KWS 常开前台服务（S5，对账表 KWS 节 + 知识包 §5）：
 *
 * - foregroundServiceType=microphone + 常驻通知（用户可见动作：点开 App / 暂停唤醒）
 * - **禁止自启**：唯一拉起路径 = 设置页用户显式开关（ContextCompat.startForegroundService）；
 *   通知「暂停唤醒」动作 = 写 pref 关闭 + stopSelf（sticky 不会再拉起）
 * - START_STICKY：被系统回收后由系统带 null intent 重启，走同一初始化路径
 * - 单条采麦流复用：不自建 AudioRecord，接管 VoiceSessionManager 的
 *   kwsSink 挂点 + startIdleCapture（知识包 §3.5 单流扇出，避免双流抢麦）
 * - 门控阈值每次 feed 读 Prefs（校准写入即时生效，无需重启服务）
 */
class VoiceForegroundService : Service() {

    @Volatile
    private var engine: KwsEngine? = null

    /** S22 影子引擎（低阈值第二 spotter）：仅 SPEAKING/PROCESSING 期喂流，命中只记
     *  日志不打断——板子一晚数据（真打断漏检补给率 vs 回声自误触率）定转正阈值。 */
    private var shadowEngine: KwsEngine? = null

    /**
     * 销毁标志（critic ① P1 麦泄漏竞态修复）。时序推演：
     * T0 用户关开关 → stopSelf；T1 系统回调 onDestroy（主线程）；
     * T2 init worker 线程模型加载完毕准备挂 sink。
     * 若 T2 晚于 T1 且无保护：服务已死无通知，但 kwsSink+startIdleCapture 已挂，
     * 麦克风持续采集、engine 永不 release = 麦泄漏。
     * 修复：onDestroy 与 init 收尾共用 [lock]——onDestroy 在锁内置标志+清理；
     * init 收尾在锁内先查标志，已销毁则 release 引擎直接退出，绝不挂 sink。
     */
    @Volatile
    private var destroyed = false

    private val lock = Any()

    /**
     * ROB-4：采麦喂入与引擎释放的单监视器串行门——sink 闭包（kws-init 线程挂上后由
     * VSM 采集线程调用）与 onDestroy 的 release 共用本门：close 后任何残余闭包
     * （null-check-then-invoke 窗口外已取出的引用）不再喂已释放引擎。
     */
    private val kwsGate = FeedReleaseGate()

    /**
     * init 单次门闩（backlog #8a，选门闩而非「收尾 engine 复查」）：复查只堵泄漏不堵
     * 双载——重复 onStartCommand 时两个 init 线程各加载一遍 ~5.4MB onnx 模型（内存/耗时
     * 尖峰），后到收尾者覆盖 engine 引用、先到者引擎永不 release。门闩从源头保证同实例
     * 只起一个 init 线程；init 失败 release 放行重试（紧接 stopSelf，防御性）。
     */
    private val initGate = OnceGate()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            PetLogger.i(TAG, "user paused wake-word from notification action")
            Prefs.setKwsEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 权限被系统撤销后 sticky 重启落到这：硬事实透传 + 关开关，不空转占麦
            PetLogger.e(TAG, "RECORD_AUDIO not granted, stop always-on KWS")
            Prefs.setKwsEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startKwsIfNeeded()
        return START_STICKY
    }

    /** 前台化；ColorOS/Android14 后台拉起 mic FGS 可能抛 SecurityException——透传日志 + 退出。 */
    private fun goForeground(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
            true
        } catch (e: Exception) {
            PetLogger.e(TAG, "startForeground(microphone) failed: ${e.message}")
            false
        }
    }

    /** 引擎加载走 worker 线程（onnx 模型 ~5.4MB，主线程加载会卡 UI）。 */
    private fun startKwsIfNeeded() {
        synchronized(lock) { if (destroyed || engine != null) return }
        // 已有 init 线程在跑（重复 onStartCommand 无 destroy 介于其间）→ 直接放弃，不起第二个
        val initToken = initGate.tryAcquire() ?: return
        Thread({
            val e = try {
                KwsEngine(
                    assetManager = assets,
                    gateProvider = { Prefs.kwsGate(this) },
                    onKeyword = { keyword -> onWake(keyword) },
                )
            } catch (t: Throwable) {
                PetLogger.e(TAG, "KwsEngine init failed: ${t.message}")
                initGate.release(initToken) // 失败放行重试（旧行为：engine==null 时可重入）
                stopSelf()
                return@Thread
            }
            // S22 影子引擎：独立加载失败不拖死主引擎（影子是观测通道非功能通道）
            val shadow = try {
                KwsEngine(
                    assetManager = assets,
                    gateProvider = { Prefs.kwsGate(this) },
                    onKeyword = { keyword ->
                        val st = VoiceClientFactory.session(this).stateFlow.value
                        PetLogger.i(TAG, "S22 shadow hit during $st (thr=${KwsDefaults.SHADOW_KEYWORDS_THRESHOLD}): $keyword")
                    },
                    logger = { msg -> PetLogger.i("KwsShadow", msg) },
                    keywordsThreshold = KwsDefaults.SHADOW_KEYWORDS_THRESHOLD,
                    // S35：影子观测通道保持 S25b MIN 档——主引擎提档后影子不跟进，
                    // 观测通道不与主链争抢（S34 教训：双引擎同权重 = 板子打满）。
                    decodeThreadPriority = Thread.MIN_PRIORITY,
                )
            } catch (t: Throwable) {
                PetLogger.e(TAG, "shadow KwsEngine init failed (non-fatal): ${t.message}")
                null
            }
            // 收尾与 onDestroy 互斥：加载窗口内服务已销毁 → 放引擎走人，不挂 sink（critic ①）
            synchronized(lock) {
                if (destroyed) {
                    PetLogger.w(TAG, "service destroyed during KwsEngine init, release and bail")
                    e.release()
                    shadow?.release()
                    return@Thread
                }
                engine = e
                shadowEngine = shadow
                val session = VoiceClientFactory.session(this)
                session.kwsSink = { block ->
                    // ROB-4：喂入经 kwsGate 与 release 串行——close 后旧闭包调用直达此处也被跳过
                    kwsGate.feed {
                        e.feed(block)
                        // 影子仅播报/处理期喂流（S22 口径）。S34 曾改全时段喂流，18:52-18:55
                        // 14.18 实测双引擎把 rk3568 打满（App CPU 202%），主+影子解码吞吐上限
                        // ≈10 块/s（单引擎时代恰满速），主引擎被挤到 decoded 4.4/s：队列（64 块
                        // 封顶丢最旧）恒满=时间轴撕裂 56%，唤醒必漏——即时回退。近漏检观测判
                        // BLOCKED（需更小模型/更强硬件/离线批量），数据存证见 notes+BLOCKED。
                        val st = session.stateFlow.value
                        if (st == VoiceStateRouter.State.SPEAKING || st == VoiceStateRouter.State.PROCESSING) {
                            shadow?.feed(block)
                        }
                    }
                }
                session.startIdleCapture()
            }
            PetLogger.i(TAG, "always-on KWS started (shadow=${shadow != null}), gate=${Prefs.kwsGate(this)}")
            // S29 首次自动校准：采麦流已起（复用分支，单流纪律），静默采 5 秒写阈值；
            // 未授权/环境不稳时跳过不标记，下次服务启动重试；失败绝不阻断 KWS。
            if (!Prefs.autoCalibDone(this)) {
                try {
                    val written = kotlinx.coroutines.runBlocking {
                        AmbientCalibrator.autoCalibrateIfFirstTime(this@VoiceForegroundService)
                    }
                    PetLogger.i(TAG, "auto calibration: ${if (written) "written" else "skipped"}")
                } catch (t: Throwable) {
                    PetLogger.e(TAG, "auto calibration failed: ${t.message}")
                }
            }
        }, "kws-init").start()
    }

    private fun onWake(keyword: String) {
        // 幻影唤醒防线（审计 P1 连带）：join 超时 skip-free 后，卡死线程若稍后醒来解码出命中，
        // 服务已毁——KwsCore 派发前 running 复查是第一道，这里是第二道
        if (destroyed) {
            PetLogger.w(TAG, "wake hit after service destroyed, dropped ($keyword)")
            return
        }
        // S54 多词路由：命中词+当前态 → VoiceStateRouter.decide。唤醒组全态动作；打断组
        // （好了/停下）仅 SPEAKING/PROCESSING 动作，IDLE 命中丢弃（防闲聊误唤醒，Q6=a）；
        // 「停」spike 已从 keywords.txt 禁用（S63 用户拍板：单音节误报体质）；若命中
        // 仍恒丢弃（Q7 路由不变，SPIKE_WORDS 墓碑）。丢弃只留日志，
        // 影子通道（独立引擎 0.15）照常观测——S55 快语速取证同用主/影差分。
        val st = VoiceClientFactory.session(this).stateFlow.value
        if (VoiceStateRouter.decide(keyword, st) == VoiceStateRouter.KwsHitAction.DROP) {
            PetLogger.i(TAG, "S54 kws hit dropped: $keyword during $st")
            return
        }
        PetLogger.i(TAG, "wake word hit ($keyword) during $st, -> startListening")
        // S12 唤醒词打断（spike 双合格转正）：SPEAKING/PROCESSING 麦块恒进 KWS，对话期
        // 命中 = 打断当前轮——startListening 内 cancelCurrentTurn（停播/封轮/收泡/回 IDLE）
        // 后接续播「我在」→ LISTENING；IDLE 期照旧直接唤醒；LISTENING 期不可达（VAD 占用）。
        // playWakeAck=true：KWS 唤醒路径播预录「我在」再进聆听（#9 顺序式，ADR 0003）
        VoiceClientFactory.session(this).startListening(playWakeAck = true)
    }

    override fun onDestroy() {
        PetLogger.i(TAG, "onDestroy, release KWS")
        synchronized(lock) {
            destroyed = true
            runCatching {
                val session = VoiceClientFactory.session(this)
                session.kwsSink = null
                session.stopIdleCapture()
            }.onFailure { PetLogger.e(TAG, "kws session teardown failed: ${it.message}") } // ROB-4：吞掉的异常留痕
            // ROB-4：feed 与 release 同一监视器串行——close 持门锁 release，
            // 在途/残余 sink 闭包的 feed 在锁外等待或被跳过，use-after-release 不可达
            kwsGate.close {
                engine?.release()
                engine = null
                shadowEngine?.release()
                shadowEngine = null
            }
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // 工程无自绘图标资源（res 仅 xml/），用系统麦克风图标
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小朵语音唤醒中")
            .setContentText("说「小朵小朵」即可对话；音频仅本地识别")
            .setContentIntent(openApp)
            .addAction(0, "暂停唤醒", pause)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "VoiceFgService"
        private const val CHANNEL_ID = "xiaoduo_kws"
        private const val NOTIFICATION_ID = 42
        const val ACTION_PAUSE = "com.xiaoduo.deskpet.action.KWS_PAUSE"
    }
}
