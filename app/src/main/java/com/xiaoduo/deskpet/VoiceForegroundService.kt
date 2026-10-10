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
import com.xiaoduo.deskpet.voice.kws.KwsDefaults
import com.xiaoduo.deskpet.voice.kws.KwsEngine
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import com.xiaoduo.deskpet.voice.audio.AmbientCalibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 *
 * 生命周期骨架（thread-discipline 件 1/件 2，2026-10-10 Phase 3）：
 * - init/shadow/校准跑在 service-scoped [bootstrapScope]（onDestroy 单点 cancel），
 *   骨架逻辑在纯 JVM [KwsBootstrap]（守卫测试 KwsBootstrapTest）；
 * - 唤醒处理与 teardown 互斥经 [wakeGate]（纯 JVM WakeEntryGate，守卫测试
 *   WakeEntryGateTest）：onDestroy 先 close 等 in-flight 唤醒处理完成，再清 sink。
 *   默认参数构造保留无参形态（系统反射实例化）；测试可注入替代 scope。
 */
class VoiceForegroundService(
    private val bootstrapScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Service() {

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
     * 唤醒处理临界区门（thread-discipline 件 1）：onWake 的 startListening 包进
     * runExclusive；onDestroy 先 close（等待 in-flight 唤醒处理完成）再清 sink——
     * 消解 destroyed 读与 startListening 之间的 TOCTOU（孤儿 capture 无主清理）。
     * onWake 顶部的 destroyed 预检保留为快路径（省锁）。
     */
    private val wakeGate = WakeEntryGate()

    /**
     * init 引导门（thread-discipline 件 2）：在途拒绝重入（原 initGate 语义，防双载
     * onnx 模型）、销毁后拒绝、factory 结果在交付前查活性（销毁传播 = 结果丢弃）。
     */
    private val bootstrap = KwsBootstrap(bootstrapScope)

    /**
     * ROB-4：采麦喂入与引擎释放的单监视器串行门——sink 闭包（kws-init 线程挂上后由
     * VSM 采集线程调用）与 onDestroy 的 release 共用本门：close 后任何残余闭包
     * （null-check-then-invoke 窗口外已取出的引用）不再喂已释放引擎。
     */
    private val kwsGate = FeedReleaseGate()

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
        startKwsIfNeeded(startId)
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

    /**
     * 引擎加载走 bootstrapScope 协程（onnx 模型 ~5.4MB，主线程加载会卡 UI）；
     * 在途/销毁时 bootstrap 拒绝（KwsBootstrap 门）。[startId] 供 init 失败路径
     * stopSelf(startId)——防旧启动请求的失败收尾误停新请求（startId 显式语义）。
     */
    private fun startKwsIfNeeded(startId: Int) {
        synchronized(lock) { if (destroyed || engine != null) return }
        val accepted = bootstrap.bootstrap(
            engineFactory = {
                val e = KwsEngine(
                    assetManager = assets,
                    gateProvider = { Prefs.kwsGate(this) },
                    onKeyword = { keyword -> onWake(keyword) },
                )
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
                e to shadow
            },
            onFailure = { t ->
                // 主引擎加载失败：放行重试（job 已正常结束）+ 显式 stopSelf(startId)——
                // 防旧启动请求的失败收尾误停新请求（startId 显式语义）
                PetLogger.e(TAG, "KwsEngine init failed: ${t.message}")
                stopSelf(startId)
            },
            onReady = { pair ->
                val (e, shadow) = pair
                // 收尾与 onDestroy 互斥：加载窗口内服务已销毁 → 放引擎走人，不挂 sink（critic ①）。
                // destroyed 快路径 + bootstrapScope.isActive 双防线（scope.cancel 先于清理段）。
                val wired = synchronized(lock) {
                    if (destroyed || !bootstrapScope.isActive) {
                        PetLogger.w(TAG, "service destroyed during KwsEngine init, release and bail")
                        e.release()
                        shadow?.release()
                        false
                    } else {
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
                        true
                    }
                }
                if (!wired) return@bootstrap
                PetLogger.i(TAG, "always-on KWS started (shadow=${shadow != null}), gate=${Prefs.kwsGate(this)}")
                // S29 首次自动校准：采麦流已起（复用分支，单流纪律），静默采 5 秒写阈值；
                // 未授权/环境不稳时跳过不标记，下次服务启动重试；失败绝不阻断 KWS。
                // scope 化（件 2）：独立协程替代 runBlocking 桥——销毁即取消（Cancel 传播
                // 到 AmbientCalibrator 的挂起点，AudioRecord 由其 finally 释放）。
                if (!Prefs.autoCalibDone(this)) {
                    bootstrapScope.launch {
                        try {
                            val written = AmbientCalibrator.autoCalibrateIfFirstTime(this@VoiceForegroundService)
                            PetLogger.i(TAG, "auto calibration: ${if (written) "written" else "skipped"}")
                        } catch (t: Throwable) {
                            PetLogger.e(TAG, "auto calibration failed: ${t.message}")
                        }
                    }
                }
            },
        )
        // bootstrap 拒绝（在途/已销毁）：快路径预检后仍被拒只可能是竞态窄窗，静默即可
        // （在途=另一 init 已在跑；销毁=onDestroy 收尾中）。
        if (!accepted) {
            PetLogger.i(TAG, "bootstrap rejected (in-flight or destroyed), skip")
        }
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
        // 件 1（ocr[202]）：预检与 startListening 之间的 TOCTOU 窗由 wakeGate 消解——
        // onDestroy 先 gate.close() 等 in-flight 完成，此处不再可能与 teardown 交错。
        val accepted = wakeGate.runExclusive {
            VoiceClientFactory.session(this).startListening(playWakeAck = true)
        }
        if (!accepted) {
            PetLogger.w(TAG, "wake arrived during teardown, dropped by gate ($keyword)")
        }
    }

    override fun onDestroy() {
        PetLogger.i(TAG, "onDestroy, release KWS")
        // 件 2：单点取消——init/shadow/校准协程随 scope.cancel 拆解（破坏性动作前
        // 的 isActive 检查丢弃在途结果）。先 cancel 再清理：阻止新收尾挂 sink。
        bootstrapScope.cancel()
        synchronized(lock) {
            destroyed = true
            // 件 1（ocr[202]）：先关唤醒门并等待 in-flight 唤醒处理完成，再清 sink——
            // gate 内的 startListening 若在重新武装采麦，此处清理接得住，孤儿 capture 不可达。
            wakeGate.close()
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
