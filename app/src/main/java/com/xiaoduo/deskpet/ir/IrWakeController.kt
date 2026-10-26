package com.xiaoduo.deskpet.ir

import android.content.Context
import com.ys.rkapi.MyManager
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * GPIO 读取 seam：把「通道建立/关闭 + 电平读取」抽象出来，触发链整条 JVM 可测。
 * 生产实现 [YsGpioReader]（ysapi MyManager glue）；测试用 FakeGpioReader（IrWakeControllerTest）。
 */
interface GpioReader {
    /**
     * 打开通道（生产 = MyManager getInstance→bindAIDLService→setConnectClickInterface 三步）。
     * 通道就绪后回调 [onReady]（可能在任意 binder 线程）；固件缺位时回调可能永不达
     * （= 通道静默禁用，调用方须容忍）。
     */
    fun open(onReady: () -> Unit)

    /** 关闭通道（生产 = unBindAIDLService）。须幂等。 */
    fun close()

    /** 置输入方向。true=成功；false/抛异常=失败。 */
    fun setInput(pin: Int): Boolean

    /** 读电平。null=读失败（样本忽略）；抛异常=通道故障（调用方停轮询降级）。 */
    fun read(pin: Int): Boolean?
}

/**
 * S70 PIR 红外唤醒控制器：轮询易声板第 1 路保留 IO 电平，上升沿（经 [PirEdgeFilter]
 * 去抖+冷却）且语音会话处于 IDLE 态时调 [onWake]（= `VSM.startListening(playWakeAck=true)`，
 * 四态语义现成，本类不写任何状态机逻辑）。
 *
 * 日志经 [log] 注入（项目 seam 模式，仿 KwsEngine logger）：生产传 PetLogger，测试传 noop
 * （PetLogger 底层 android.util.Log 在 JVM 单测不可达）。
 *
 * 生命周期（守卫四问答卷，票 S70）：
 * - Q1 配对：start()→open（bind）+ onReady→setInput 成功→轮询协程；stop()→协程 cancel + close（unBind）。
 *   start 幂等（running 标志）；stop 后迟到的 onConnect 由 [running] 标志拦截。
 * - Q2 异常终态：enabled=false → 不 bind；open/setInput 失败 → log + close 通道 + 不起轮询
 *   （通道降级，VFS 其余功能零感知）；read 抛异常 → log + 停轮询（失败降级静默禁用）；
 *   onWake 抛 → catch+log，轮询继续（冷却窗兜重复触发）。
 * - Q3 取消：stop() 幂等、任何态可调、协程在 delay 检查点退出。
 * - Q4 重复触发四层防重：上升沿记忆 + 连续 2 样本去抖 + 30s 冷却（均在 [PirEdgeFilter]）
 *   + IDLE 门（本类 idleGate）；VSM 自身四态语义再兜底。
 */
class IrWakeController(
    private val scope: CoroutineScope,
    private val gpioReader: GpioReader,
    private val enabledProvider: () -> Boolean,
    private val idleGate: () -> Boolean,
    private val onWake: () -> Unit,
    private val filter: PirEdgeFilter = PirEdgeFilter(),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val gpioPin: Int = GPIO_PIN,
    private val log: (String) -> Unit = { PetLogger.i(TAG, it) },
) {

    /** 通道/轮询活性标志：start 置位、stop 复位；onConnect 迟到时靠它拦截。 */
    @Volatile
    private var running = false

    @Volatile
    private var pollJob: Job? = null

    /** 开启红外唤醒通道。enabled=false 或 bind 失败时静默不启用（log 一行，绝不抛）。 */
    fun start() {
        if (running) return
        if (!enabledProvider()) {
            log("pir_wake_enabled=false, IR channel not started")
            return
        }
        running = true
        try {
            gpioReader.open { onChannelReady() }
        } catch (t: Throwable) {
            running = false
            // 非易声板 / binder 异常：通道静默禁用，KWS 主链不受影响
            log("E: IR bind failed, channel disabled: ${t.message}")
        }
    }

    /** onConnect 回调（可能在 binder 线程）：置输入方向成功才起轮询。 */
    private fun onChannelReady() {
        if (!running) {
            log("onConnect arrived after stop, drop")
            return
        }
        val ok = try {
            gpioReader.setInput(gpioPin)
        } catch (t: Throwable) {
            log("E: setInput($gpioPin) threw: ${t.message}")
            false
        }
        if (!ok) {
            log("E: setInput($gpioPin) failed, IR channel disabled")
            stop()
            return
        }
        startPolling()
    }

    private fun startPolling() {
        if (pollJob != null) return
        // S70 回归守卫：起轮询前二次 running 检查——binder onConnect 回调执行中途 stop()
        // 并发完成（pollJob 已被 stop 置 null）时，无此检查会 launch 无人 cancel 的孤轮询
        // （删此 → `onConnect回调中途并发stop则不起孤轮询` 红）。窄窗 + VFS onDestroy stop 兜底。
        if (!running) return
        log("IR polling started (pin=$gpioPin, interval=${pollIntervalMs}ms)")
        pollJob = scope.launch(
            Dispatchers.IO + CoroutineExceptionHandler { _, t ->
                // S70 回归守卫：轮询体内 read try-catch 之外的任何未捕获异常不得逸出
                // （删此 → 意外异常直冲 scope，SupervisorJob 下静默死协程/主线程 crash 风险），
                // 终态落 Q2「失败降级静默禁用」：log 一行 + 轮询槽位复位
                log("E: IR polling crashed, disabled: ${t.message}")
                pollJob = null
            },
        ) {
            while (isActive) {
                val level = try {
                    gpioReader.read(gpioPin)
                } catch (t: Throwable) {
                    // read 异常 = 通道故障（binder 死亡等）：失败降级停轮询（调研报告 §6 风险对策）
                    // S70 回归守卫：失败降级（删此 catch-return → 异常逸出协程 → `read抛异常则轮询降级停转且不崩溃` 红）
                    log("E: IR read failed, polling disabled: ${t.message}")
                    pollJob = null
                    return@launch
                }
                // 口径注记（critic P3-5）：非 IDLE 态的上升沿会先被 filter 记冷却起点
                //（onSample 返回 true 时 lastTriggerMs 已置位）再被 idleGate 丢弃——播报中
                // 人到场 = 该边沿被吞且 30s 冷却已开始。与「仅 IDLE 响应」拍板口径不冲突
                // （保守方向：宁可不唤，不扰播报）。
                if (filter.onSample(level, System.currentTimeMillis())) {
                    // S70 回归守卫：IDLE 门——仅待机态响应（用户拍板：LISTENING/PROCESSING/
                    // SPEAKING 忽略 PIR 触发；删此 → 非 IDLE 态 onWake 被调 → `非IDLE态命中不调onWake` 红）
                    if (idleGate()) {
                        log("PIR rising edge, -> startListening")
                        try {
                            onWake()
                        } catch (t: Throwable) {
                            log("E: onWake threw (polling continues): ${t.message}")
                        }
                    } else {
                        log("PIR edge dropped: not IDLE")
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    /** 停止并关闭通道。幂等，任何态可调（VFS onDestroy / setInput 失败共用）。 */
    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        try {
            gpioReader.close()
        } catch (t: Throwable) {
            log("E: IR close threw: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "IrWake"

        /** 轮询间隔（调研报告验收口径：延迟 ≤200ms，中断不可得的轮询权衡）。 */
        const val POLL_INTERVAL_MS = 200L

        /** 第 1 路保留 IO（V5.2+ 语义：传 1..N = 第 N 路；用户已确认 PIR 接 GPIO1）。 */
        const val GPIO_PIN = 1
    }
}

/**
 * 生产 glue：ysapi MyManager 三步初始化 + GPIO 读（MyManager 调用全部隔离在本类，
 * IrWakeController 本体零 SDK import；只在易声板固件上工作，bind 失败=open 抛/回调不达）。
 */
class YsGpioReader(private val context: Context) : GpioReader {

    /** getInstance 是进程级单例；仅作持有引用，未连接时全部 API 走失败路径。 */
    private var manager: MyManager? = null

    override fun open(onReady: () -> Unit) {
        val m = MyManager.getInstance(context)
        manager = m
        m.setConnectClickInterface(object : MyManager.ServiceConnectedInterface {
            override fun onConnect() {
                try {
                    onReady()
                } catch (t: Throwable) {
                    PetLogger.e("IrWake", "onReady threw: ${t.message}")
                }
            }
        })
        m.bindAIDLService(context)
    }

    override fun close() {
        try {
            manager?.unBindAIDLService(context)
        } catch (t: Throwable) {
            PetLogger.e("IrWake", "unBindAIDLService threw: ${t.message}")
        }
    }

    override fun setInput(pin: Int): Boolean {
        val m = manager ?: return false
        return try {
            // 1=输入方向（文档第 9 章：setGpioDirection(n, 0/1)）
            m.setGpioDirection(pin, 1)
        } catch (t: Throwable) {
            PetLogger.e("IrWake", "setGpioDirection threw: ${t.message}")
            false
        }
    }

    override fun read(pin: Int): Boolean? {
        val m = manager ?: return null
        // getGpioValue 返回 String 电平文本（"0"/"1"）；异常/意外文本一律 null=读失败
        return try {
            when (m.getGpioValue(pin)?.trim()) {
                "1" -> true
                "0" -> false
                else -> null
            }
        } catch (t: Throwable) {
            PetLogger.e("IrWake", "getGpioValue threw: ${t.message}")
            null
        }
    }
}
