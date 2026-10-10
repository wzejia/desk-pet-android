package com.xiaoduo.deskpet

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import com.xiaoduo.deskpet.pet.EdgeDock
import com.xiaoduo.deskpet.pet.OverlayClamp
import com.xiaoduo.deskpet.pet.PetState
import com.xiaoduo.deskpet.ui.PetView
import com.xiaoduo.deskpet.util.AppLauncher
import com.xiaoduo.deskpet.api.BubbleOwner
import com.xiaoduo.deskpet.ui.overlay.BubbleOwnership
import com.xiaoduo.deskpet.ui.overlay.BubbleTextStrip
import com.xiaoduo.deskpet.ui.overlay.BubbleView
import com.xiaoduo.deskpet.ui.overlay.OverlayMenu
import com.xiaoduo.deskpet.ui.overlay.VoiceIndicatorView
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.util.TapGesture
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import kotlin.math.abs

/**
 * 悬浮窗服务（S3 双窗口职责翻转版，知识包 §8 合规 + 手机精灵形态实证 docs/research-ref-jingling-launcher.png）：
 * - 交互层窗口：宠物可视尺寸（192x208dp @scale=1），渲染 PetView + 承载手势集 KD15：
 *   单击=挥手一次 / 拖拽=移动 / 长按=菜单。可触摸窗口在 Android 12+ 不被强制
 *   alpha 0.8，宠物全不透明实体感（spritesheet 帧定长 192x208，无动画溢出，无需 2× 显示层防溢出）。
 * - 显示层窗口：气泡专用容器（宽 2× 宠物、高 74dp+slack），FLAG_NOT_TOUCHABLE--
 *   不吞触摸；代价是系统对该窗口强制 0.8 alpha，气泡底色因此提为不透明
 *   （0.8 惩罚后仍 0.8 有效，文字对比足够，S3 二轮返工 P2）。气泡 attach 精灵正上方，
 *   X 向 clamp 到屏内（OverlayClamp.clampedBubbleLeft，贴边不裁字）。
 * - 两窗口位置联动：拖拽时气泡窗跟随交互层重算。
 * - 贴边收纳（S10）：拖至左右屏缘松手 -> 吸附滑入半身（EdgeDock 几何；贴边坐标是 clamp
 *   不动点，持久化/STICKY 恢复/尺寸重钳位零新机制）；贴边态单击=滑出恢复，
 *   非贴边态单击仍挥手；动画发起时即落盘目标坐标（被取消/被杀都恢复到确定态）。
 * - 持久化：拖拽 end clamp 后落盘；总开关 overlay_enabled；语音开关 voice_enabled；
 *   START_STICKY 杀进程重启（intent=null）按落盘状态恢复位置与窗口。
 * - 显示尺寸变化（wm size/分屏/折叠）：onConfigurationChanged 按当前 metrics 重 clamp
 *   双窗口位置（真机 P2：绝对 px 旧坐标在新屏上整体出屏）；clamp 结果不落盘，保留拖拽意图。
 * - 服务级 API（S2/S4 接线用）：LocalBinder + companion instance；
 *   气泡所有权归服务（backlog #4）：claimBubbleRound 认领 round/owner 令牌，
 *   showBubble / appendBubble / hideBubble / hideBubbleLater 全部带令牌校验，
 *   过期 round 丢弃+日志（跨通道互杀防线）；show_bubble=false 时一切弹泡 no-op 并立即隐藏。
 */
class PetOverlayService : Service() {

    inner class LocalBinder : Binder() {
        val service: PetOverlayService get() = this@PetOverlayService
    }

    private val binder = LocalBinder()

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** 活性心跳（spec §4.3）：周期续 PetOverlayState 活性戳，onDestroy 停——
     *  service 死于 onDestroy 之外时设置页 isAlive 因心跳过期判 false。 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            PetOverlayState.markHeartbeat(SystemClock.elapsedRealtime())
            handler.postDelayed(this, PetOverlayState.HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, PetOverlayState.HEARTBEAT_INTERVAL_MS)
    }

    private var displayBox: FrameLayout? = null
    private var interactionBox: FrameLayout? = null
    private var petView: PetView? = null
    private var bubble: BubbleView? = null
    private var voiceIndicator: VoiceIndicatorView? = null
    private lateinit var displayLp: WindowManager.LayoutParams
    private lateinit var interactionLp: WindowManager.LayoutParams
    private var built = false

    private var petW = 0
    private var petH = 0
    private var edgeMarginPx = 0
    private var bubbleGapPx = 0

    /** S10 贴边滑动动画（滑入半身/滑出）；任何新手势/尺寸变化/销毁先 cancel 接管位置。 */
    private var slideAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        PetLogger.init(applicationContext)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        PetLogger.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent != null = 显式拉起（设置页开关）；intent == null = STICKY 杀进程重启
        if (intent != null) {
            Prefs.setOverlayEnabled(this, true)
        }
        if (!Prefs.overlayEnabled(this)) {
            PetLogger.i(TAG, "onStartCommand but overlay_enabled=false, stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureWindows()
        return START_STICKY
    }

    /**
     * 显示尺寸/密度变化（wm size、分屏、折叠屏展开收起）时按当前 metrics 重 clamp：
     * 持久化与拖拽语义都是绝对 px，旧坐标在新屏上可能整体出屏（真机 P2:1080x2400 存的
     * (540,1414) 在 720x1600 上精灵完全不可见）。交互窗 clamp 后气泡窗随 moveTo 重算
     * （updateBubbleWindow 内 clampedBubbleLeft 已按当前屏宽贴边 clamp）。
     * 不重算 petW/petH：scale/拖拽语义不动，窗口 px 尺寸保持构建时值。
     * 刻意不落盘：落盘值保留用户最后拖拽意图；启动恢复（initialPosition）与本回调都会
     * 再 clamp，临时尺寸变化（折叠展开又收起）不篡改用户原位置。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!built) return
        cancelSlideAnimation() // 尺寸重算接管位置前先停滑动动画（守卫：动画帧不再覆写重钳位结果）
        val dm = resources.displayMetrics
        val (cx, cy) = OverlayClamp.clamp(
            interactionLp.x, interactionLp.y, petW, petH,
            dm.widthPixels, dm.heightPixels
        )
        if (cx != interactionLp.x || cy != interactionLp.y) {
            moveTo(cx, cy)
            PetLogger.i(
                TAG,
                "config changed -> reclamped to ($cx,$cy), screen=${dm.widthPixels}x${dm.heightPixels}"
            )
        } else {
            // 宠物位置未动，气泡 X 仍依赖新屏宽，需重 clamp
            updateBubbleWindow()
            PetLogger.d(TAG, "config changed, position in bounds; bubble reclamped")
        }
    }

    // ---------------- 双窗口构建 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureWindows() {
        if (built) return
        val dm = resources.displayMetrics
        val dp = dm.density
        val scale = Prefs.petScale(this)

        petW = (PetView.PET_WIDTH_DP * scale * dp).toInt()
        petH = (PetView.PET_HEIGHT_DP * scale * dp).toInt()
        // S7-c：气泡窗宽 2×宠物(~384dp) -> 固定 ~260dp（窄气泡对齐聊天气泡观感）
        val bubbleWinW = (BUBBLE_WIN_WIDTH_DP * dp).toInt()
        val bubbleWinH = ((BubbleView.MAX_HEIGHT_DP + BUBBLE_WIN_SLACK_DP) * dp).toInt()
        edgeMarginPx = (EDGE_MARGIN_DP * dp).toInt()
        bubbleGapPx = (BUBBLE_GAP_DP * dp).toInt()

        val (ix, iy) = initialPosition(dm.widthPixels, dm.heightPixels)

        // 交互层：宠物尺寸，渲染 PetView + 承载手势。无 NOT_TOUCHABLE -> 全不透明
        interactionLp = WindowManager.LayoutParams(
            petW, petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = INTERACTION_TITLE
        }

        // 显示层：气泡专用容器。S39：NOT_TOUCHABLE 改为动态切换（show/hide 时开/关，
        // 见 [setBubbleTouchable]）——常驻可触摸会让气泡 GONE 后窗口矩形仍拦截触摸；
        // 常驻不可触摸则气泡内容无法手动滑动查看。可触摸期间无 0.8 alpha 系统惩罚
        // （气泡底本就不透明，有效不透明度 0.8→1.0，视觉提升）。
        displayLp = WindowManager.LayoutParams(
            bubbleWinW, bubbleWinH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = DISPLAY_TITLE
        }

        val interaction = FrameLayout(this)
        val pet = PetView(this)
        interaction.addView(pet, FrameLayout.LayoutParams(petW, petH))
        attachGestures(interaction, pet)

        val bubbleLayer = FrameLayout(this).apply { clipChildren = false }
        val bubbleView = BubbleView(this).apply {
            maxWidth = bubbleWinW - (16 * dp).toInt() * 2
            visibility = View.GONE
        }
        bubbleLayer.addView(
            bubbleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        // S49 语音指示泡（聆听声纹/思考三点）：与文字泡同锚点（底对齐居中）二选一，
        // 恒定 132×40dp（View 内 onMeasure 自定）。默认 GONE，show 时接管。
        val indicatorView = VoiceIndicatorView(this).apply { visibility = View.GONE }
        bubbleLayer.addView(
            indicatorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        // 打字机逐字改变气泡宽度 -> 每次布局重算气泡窗 X（贴边 clamp）
        bubbleView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBubbleWindow() }

        var firstAttached: View? = null // ROB-5：记录首个已挂窗口，失败路径先摘再退（防 WindowLeaked）
        runCatching {
            wm.addView(bubbleLayer, displayLp) // 先加气泡层，交互层在上
            firstAttached = bubbleLayer
            wm.addView(interaction, interactionLp)
        }.onFailure {
            firstAttached?.let { v -> runCatching { wm.removeView(v) } }
            PetLogger.e(TAG, "addView failed", it)
            stopSelf()
            return
        }

        displayBox = bubbleLayer
        interactionBox = interaction
        petView = pet
        bubble = bubbleView
        voiceIndicator = indicatorView
        built = true
        moveTo(ix, iy)
        // §5.4 渲染锚：交互窗首帧 draw 即「悬浮窗可见」下界（真可见性上界=录屏逐帧）；
        // OnDrawListener 回调内禁止移除 listener，首帧后靠标志短路。
        var firstFrameDrawn = false
        interaction.viewTreeObserver.addOnDrawListener {
            if (!firstFrameDrawn) {
                firstFrameDrawn = true
                PetLogger.i(TAG, "overlay first-frame drawn")
            }
        }
        PetOverlayState.markStarted(SystemClock.elapsedRealtime())
        startHeartbeat()
        PetLogger.i(
            TAG,
            "dual windows added: interaction=${petW}x${petH}px(opaque pet) " +
                "bubbleLayer=${bubbleWinW}x${bubbleWinH}px(NOT_TOUCHABLE) at ($ix,$iy) scale=$scale"
        )
    }

    /** 初始位置：优先落盘值（clamp 到当前屏幕），否则默认屏幕右侧中上
     *  （盲测 v3 遮挡克制批评：居中偏上会压图标文字区；右侧中上避开 dock 与首屏图标行）。 */
    private fun initialPosition(screenW: Int, screenH: Int): Pair<Int, Int> {
        val (sx, sy) = Prefs.overlayPosition(this)
        val raw = if (sx != Prefs.POSITION_UNSET && sy != Prefs.POSITION_UNSET) {
            sx to sy
        } else {
            (screenW - petW - edgeMarginPx) to ((screenH - petH) / 4)
        }
        return OverlayClamp.clamp(raw.first, raw.second, petW, petH, screenW, screenH)
    }

    /** 位置联动：交互层移到 (ix,iy)，气泡窗跟随重算（见 [updateBubbleWindow]）。 */
    private fun moveTo(ix: Int, iy: Int) {
        val interaction = interactionBox ?: return
        interactionLp.x = ix
        interactionLp.y = iy
        runCatching {
            wm.updateViewLayout(interaction, interactionLp)
        }.onFailure { PetLogger.e(TAG, "updateViewLayout failed", it) }
        updateBubbleWindow()
    }

    /**
     * 气泡窗定位（S3 返工#2）：气泡底边 = 宠物顶边 - gap，水平理想居中于宠物；
     * 宠物贴屏缘时整窗平移，保证气泡本体钳在屏内可见区（贴边不裁字）。
     * 气泡宽度随打字机变化，故由 OnLayoutChange 与 moveTo 共同驱动。
     */
    private fun updateBubbleWindow() {
        val bubbleLayer = displayBox ?: return
        if (!built) return
        val dm = resources.displayMetrics
        val margin = (BUBBLE_EDGE_MARGIN_DP * dm.density).toInt()
        val bw = bubble?.width ?: 0
        val petCenterX = interactionLp.x + petW / 2
        val bubbleLeft = OverlayClamp.clampedBubbleLeft(petCenterX, bw, dm.widthPixels, margin)
        // 气泡在窗内居中 -> 窗 X = 气泡期望左缘 - (窗宽 - 气泡宽)/2
        displayLp.x = bubbleLeft - (displayLp.width - bw) / 2
        displayLp.y = interactionLp.y - bubbleGapPx - displayLp.height
        runCatching {
            wm.updateViewLayout(bubbleLayer, displayLp)
        }.onFailure { PetLogger.e(TAG, "updateBubbleWindow failed", it) }
    }

    // ---------------- S10 贴边滑动动画 ----------------

    /**
     * X 轴滑动动画（S10 贴边滑入/滑出）：~200ms 逐帧 [moveTo]（气泡窗随现有联动走）。
     * 落盘时机=发起时写目标坐标（动画被取消/进程被杀都恢复到确定态，不存中间帧）。
     * 目标 x 再过一次 clamp 防御调用方漂移；贴边/滑出坐标本就是 clamp 不动点，正常路径零变化。
     */
    private fun animateSlideTo(targetX: Int, reason: String) {
        cancelSlideAnimation()
        val dm = resources.displayMetrics
        val endX = OverlayClamp.clamp(
            targetX, interactionLp.y, petW, petH, dm.widthPixels, dm.heightPixels
        ).first
        val startX = interactionLp.x
        Prefs.setOverlayPosition(this, endX, interactionLp.y)
        if (startX == endX) {
            PetLogger.i(TAG, "edge slide ($reason) no-op, already at x=$endX")
            return
        }
        val anim = ValueAnimator.ofInt(startX, endX).apply {
            duration = SLIDE_DURATION_MS
            addUpdateListener { a -> moveTo((a.animatedValue as Int), interactionLp.y) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (slideAnimator === animation) slideAnimator = null
                }
            })
        }
        slideAnimator = anim
        anim.start()
        PetLogger.i(TAG, "edge slide ($reason): $startX -> $endX, target persisted")
    }

    /** 取消进行中的滑动动画（新手势/尺寸变化/销毁接管位置时；动画停在当前帧，lp 即当前位置）。 */
    private fun cancelSlideAnimation() {
        slideAnimator?.cancel()
        slideAnimator = null
    }

    // ---------------- 手势集（KD15）----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(target: View, pet: PetView) {
        val dp = resources.displayMetrics.density
        val touchSlop = 12 * dp
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressFired = false
        // S28 双击打断：非贴边态单击本就是 no-op（S13），双击零冲突；贴边态第一击已滑出、
        // 第二击打断（边缘场景行为自然）。窗口 400ms——系统 DoubleTapTimeout(300ms) 对真人
        // 双击 UP-UP 间隔偏紧（S31 板子实测无效根因）；判定后清零防三击误触发两轮。
        var lastTapUpAt = 0L
        val doubleTapWindowMs = 400L

        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressFired = true
                PetLogger.d(TAG, "long press -> show menu")
                showMenu(target)
            }
        }

        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSlideAnimation() // 新手势接管位置（守卫：拖拽 startX 取当前帧不跳变）
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = interactionLp.x; startY = interactionLp.y
                    dragging = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                        PetLogger.d(TAG, "drag start from ($startX,$startY)")
                    }
                    if (dragging) {
                        moveTo(startX + dx.toInt(), startY + dy.toInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    // S31 真人按压漂移修复：UP 净位移 ≤TapGesture.MAX_DRIFT_PX（32px）视为点
                    // 按——即使漂移超 touchSlop(12px) 误置 dragging（板子 1x 密度 12px≈1.7mm，
                    // 手指自然漂移必超，旧逻辑 tap 永被吞、双击永不成立）也按 tap 处理；
                    // >32px 才是真拖拽。与 TapGestureTest 锁定同源。
                    val isTapGesture = ev.action == MotionEvent.ACTION_UP && !longPressFired &&
                        TapGesture.isTap(ev.rawX - downRawX, ev.rawY - downRawY)
                    if (dragging && !isTapGesture) {
                        val dm = resources.displayMetrics
                        val (cx, cy) = OverlayClamp.clamp(
                            interactionLp.x, interactionLp.y, petW, petH,
                            dm.widthPixels, dm.heightPixels
                        )
                        moveTo(cx, cy)
                        // S10 贴边吸附：clamp 后中心距缘 ≤ 半身位（zone=petW/2，精灵仍贴缘/半出屏）
                        // -> 动画滑入半身并落盘目标；未命中维持原位（反例：屏中松手零变化）
                        val dock = EdgeDock.dockSide(cx + petW / 2, dm.widthPixels, petW / 2)
                        if (dock != null) {
                            animateSlideTo(EdgeDock.dockedX(dock, petW, dm.widthPixels), "dock $dock")
                        } else {
                            Prefs.setOverlayPosition(this, cx, cy)
                            PetLogger.d(TAG, "drag end at ($cx,$cy), position persisted")
                        }
                    } else if (isTapGesture) {
                        if (dragging) moveTo(startX, startY) // 撤销假拖拽期间的窗口微动，回原位
                        // S28 双击=打断输出（cancelCurrentTurn：停 TTS/流式，与长按菜单「打断」
                        // 同通道；IDLE 时 VSM 内部 no-op 无害）。双击优先于单击动作。
                        val now = android.os.SystemClock.uptimeMillis()
                        val doubleTap = now - lastTapUpAt <= doubleTapWindowMs
                        lastTapUpAt = if (doubleTap) 0L else now
                        if (doubleTap) {
                            PetLogger.i(TAG, "overlay pet double tap -> cancelCurrentTurn")
                            requestInterrupt(this@PetOverlayService)
                            dragging = false
                            return@setOnTouchListener true
                        }
                        // S10 贴边态单击=滑出恢复（几何推导，无状态位）；非贴边态维持挥手
                        val dm = resources.displayMetrics
                        val docked = EdgeDock.dockedSideOf(interactionLp.x, petW, dm.widthPixels)
                        if (docked != null) {
                            animateSlideTo(EdgeDock.slideOutX(docked, petW, dm.widthPixels), "undock $docked")
                        } else {
                            // S13：点击挥手取消（用户 2026-08-24 拍板），非贴边态单击无动作
                            PetLogger.d(TAG, "overlay pet clicked (no-op, waving removed in S13)")
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- 长按菜单 ----------------

    private fun showMenu(anchor: View) {
        OverlayMenu.show(
            anchor = anchor,
            voiceEnabled = Prefs.voiceEnabled(this),
            onOpenDenseShelf = {
                val ok = AppLauncher.startWithToast(this)
                PetLogger.i(TAG, "menu: open dense shelf app, ok=$ok")
            },
            onToggleVoice = {
                val newState = !Prefs.voiceEnabled(this)
                Prefs.setVoiceEnabled(this, newState)
                PetLogger.i(TAG, "menu: voice_enabled -> $newState")
                newState
            },
            onHideOverlay = {
                PetLogger.i(TAG, "menu: hide overlay")
                Prefs.setOverlayEnabled(this, false)
                stopSelf()
            },
            canCreateNewSession = !(chatBusyProvider?.invoke() ?: true), // S48 无 VM 桥（VM 未建）保守置灰
            onCreateNewSession = {
                val ok = runCatching { chatNewSession?.invoke() ?: false }.getOrElse { false }
                PetLogger.i(TAG, "menu: create new session, ok=$ok")
                if (ok) showBubble(BubbleOwner.CHAT, "已开启新会话") // 用户拍板：成功气泡反馈
            },
            canInterrupt = VoiceClientFactory.session(this).state != VoiceStateRouter.State.IDLE,
            onInterrupt = {
                PetLogger.i(TAG, "menu: interrupt current turn")
                requestInterrupt(this)
            },
        )
    }

    // ---------------- 气泡服务级 API（S2/S4 接线用，round/owner 令牌）----------------

    /** 所有权判定核心（纯逻辑，JVM 单测钉死）；本层只做接线与视图 IO。 */
    private val bubbleOwnership = BubbleOwnership()

    /** 气泡 markdown 渲染的流式会话：每 round / 每次 showBubble 全量重置重建。
     *  只在主线程 runnable 内读写（claim 里的重建仅作 append 先于 show 的兜底）。 */
    @Volatile
    private var bubbleStrip = BubbleTextStrip.Session()

    /**
     * S8-6：气泡是否曾显示过非空内容（菜单「查看最近一轮」置灰门控）。
     * 置位点在 [showBubble]/[appendBubble] 的 APPLY 分支（内容非空才置）；
     * 一旦置 true 永不复位--只要历史上有过一轮可回看，「查看最近一轮」就可用。
     */
    @Volatile
    private var hasShownBubbleContent = false

    /**
     * 认领新 round：回合开始（文字轮发送 / 语音回首轮次 / debug show）时调用。
     * 旧 round 的 show/append/hide（含已到点的延迟 hide）即刻全部失效。
     */
    fun claimBubbleRound(owner: BubbleOwner): Int {
        val round = bubbleOwnership.claim(owner)
        bubbleStrip = BubbleTextStrip.Session()
        PetLogger.d(TAG, "bubble round claimed: owner=$owner round=$round")
        return round
    }

    /** 认领并立即弹泡（语音/debug 便捷口）：返回本轮 round 供后续 append/hide 使用。 */
    fun showBubble(owner: BubbleOwner, text: String): Int {
        val round = claimBubbleRound(owner)
        showBubble(owner, round, text)
        return round
    }

    /** 带令牌弹泡：仅当前 round 受理；show_bubble=false 时 no-op 并立即隐藏（不验 round）。
     *  文本经 BubbleTextStrip 渲染 markdown 基础集（全量重置语义：重建流式会话再喂入）。
     *  S49：文字泡显示即收指示泡（同容器互斥：回复 delta/我在/错误顶替声纹/三点）。 */
    fun showBubble(owner: BubbleOwner, round: Int, text: String) {
        when (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this))) {
            BubbleOwnership.Verdict.APPLY -> {
                if (text.isNotEmpty()) hasShownBubbleContent = true
                handler.post {
                    // ROB-6：post 前校验与落地间可插入新轮 claim/隐藏——落地前以同一令牌复验，
                    // 过期 runnable 不再以旧文本重建 bubbleStrip/重开触摸
                    when (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this))) {
                        BubbleOwnership.Verdict.APPLY -> Unit // 落地
                        BubbleOwnership.Verdict.FORCE_HIDE -> {
                            PetLogger.d(TAG, "bubble show re-judged force-hide after post")
                            forceHideBubble()
                            return@post
                        }
                        BubbleOwnership.Verdict.DROP_STALE -> {
                            PetLogger.d(TAG, "bubble show dropped after post: stale owner=$owner round=$round")
                            return@post
                        }
                    }
                    voiceIndicator?.let {
                        it.stopLoop()
                        it.visibility = View.GONE
                    }
                    bubble?.let {
                        val session = BubbleTextStrip.Session()
                        bubbleStrip = session
                        it.visibility = View.VISIBLE
                        it.typeNew(session.feed(text).text)
                        setBubbleTouchable(true) // S39：可见期开触摸（可滑动查看）
                        PetLogger.d(TAG, "bubble show, ${text.length} chars")
                    }
                }
            }
            BubbleOwnership.Verdict.FORCE_HIDE -> {
                PetLogger.d(TAG, "show_bubble=false, showBubble no-op + hide")
                forceHideBubble()
            }
            BubbleOwnership.Verdict.DROP_STALE ->
                PetLogger.d(TAG, "showBubble dropped: stale owner=$owner round=$round")
        }
    }

    /** 带令牌流式追加：气泡未显示时自动弹出，打字机续打；校验语义同 [showBubble]。
     *  delta 经 BubbleTextStrip 流式渲染（半截标记 hold，reset 兜底全量重打）。
     *  S49：同 showBubble，文字上屏即收指示泡（思考三点被回复内容顶替）。 */
    fun appendBubble(owner: BubbleOwner, round: Int, delta: String) {
        when (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this))) {
            BubbleOwnership.Verdict.APPLY -> {
                if (delta.isNotEmpty()) hasShownBubbleContent = true
                handler.post {
                    // ROB-6：同 showBubble——落地前复验，过期 runnable 不再追加喂错会话
                    when (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this))) {
                        BubbleOwnership.Verdict.APPLY -> Unit
                        BubbleOwnership.Verdict.FORCE_HIDE -> {
                            PetLogger.d(TAG, "bubble append re-judged force-hide after post")
                            forceHideBubble()
                            return@post
                        }
                        BubbleOwnership.Verdict.DROP_STALE -> {
                            PetLogger.d(TAG, "bubble append dropped after post: stale owner=$owner round=$round")
                            return@post
                        }
                    }
                    voiceIndicator?.let {
                        it.stopLoop()
                        it.visibility = View.GONE
                    }
                    bubble?.let {
                        if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
                        val emit = bubbleStrip.feed(delta)
                        if (emit.reset) it.typeNew(emit.text) else it.append(emit.text)
                        setBubbleTouchable(true) // S39：append 自动弹出路径同样开触摸
                    }
                }
            }
            BubbleOwnership.Verdict.FORCE_HIDE -> {
                PetLogger.d(TAG, "show_bubble=false, appendBubble no-op + hide")
                forceHideBubble()
            }
            BubbleOwnership.Verdict.DROP_STALE ->
                PetLogger.d(TAG, "appendBubble dropped: stale owner=$owner round=$round")
        }
    }

    /** 带令牌收泡：仅当前 round 受理，过期丢弃+日志（不许静默）。 */
    fun hideBubble(owner: BubbleOwner, round: Int) {
        if (bubbleOwnership.judgeHide(owner, round) == BubbleOwnership.Verdict.APPLY) {
            forceHideBubble()
            PetLogger.d(TAG, "bubble hide: owner=$owner round=$round")
        } else {
            PetLogger.d(TAG, "hideBubble dropped: stale owner=$owner round=$round")
        }
    }

    /** FUNC-5 流尾补发（audit-fix 跨包接线）：流结束时把 hold 点挡住的残余内容按一次性渲染规则补上屏。
     *  令牌/落地复验语义同 [appendBubble]；flush 幂等（无持有内容吐空），气泡未显示时不补发不弹出。 */
    fun flushBubble(owner: BubbleOwner, round: Int) {
        if (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this)) != BubbleOwnership.Verdict.APPLY) return
        handler.post {
            when (bubbleOwnership.judgePublish(owner, round, Prefs.showBubble(this))) {
                BubbleOwnership.Verdict.APPLY -> Unit
                BubbleOwnership.Verdict.FORCE_HIDE -> {
                    PetLogger.d(TAG, "bubble flush re-judged force-hide after post")
                    forceHideBubble()
                    return@post
                }
                BubbleOwnership.Verdict.DROP_STALE -> {
                    PetLogger.d(TAG, "bubble flush dropped after post: stale owner=$owner round=$round")
                    return@post
                }
            }
            bubble?.takeIf { it.visibility == View.VISIBLE }?.let {
                val emit = bubbleStrip.flush()
                if (emit.reset) it.typeNew(emit.text) else it.append(emit.text)
            }
        }
    }

    /** 带 round 的延迟隐藏原语（文字轮完成 12s 收泡用）：到点复验，新 round 占用时不生效。 */
    fun hideBubbleLater(owner: BubbleOwner, round: Int, delayMs: Long) {
        // FUNC-5 跨包接线：收泡调度=流结束信号，先把 hold 残余补上屏再排收泡
        // （文字轮/语音轮收泡都走本原语，单点覆盖；flush 幂等，重复调用无副作用）
        flushBubble(owner, round)
        handler.postDelayed({
            if (bubbleOwnership.judgeHide(owner, round) == BubbleOwnership.Verdict.APPLY) {
                forceHideBubble()
                PetLogger.d(TAG, "delayed bubble hide fired: owner=$owner round=$round")
            } else {
                PetLogger.d(TAG, "delayed bubble hide dropped: stale owner=$owner round=$round")
            }
        }, delayMs)
    }

    /** 无条件收泡（FORCE_HIDE 路径与令牌校验通过后的实际执行）。指示泡一并收（互斥同容器）。
     *  ROB-7：触摸开关随视图操作一并 post 到主线程原子执行——hideBubble/FORCE_HIDE 可从
     *  binder/worker 线程到达，旧实现此处同步调 setBubbleTouchable 走非主线程窗口更新。 */
    private fun forceHideBubble() {
        handler.post {
            bubble?.let {
                it.cancelAndClear()
                it.visibility = View.GONE
            }
            voiceIndicator?.let {
                it.stopLoop()
                it.visibility = View.GONE
            }
            setBubbleTouchable(false) // S39：隐藏即还触摸穿透（防常驻窗口矩形拦截）
        }
    }

    /**
     * S39：气泡窗口触摸开关（显示=开：用户可上下滑动查看内容；隐藏=关：触摸穿透回
     * 下层，气泡 GONE 后矩形区域不留隐形拦截块）。无变化 no-op（防重复 updateViewLayout）。
     * 代价语义：气泡可见期间其矩形区域触摸被消费（拖动=滚动；点击不透传）——
     * TextView+movementMethod 消费 DOWN 是滚动可用的必要条件，气泡为临时内容
     * 展示件，区域不可点可接受（用户拍板 2026-09-11）。
     */
    private fun setBubbleTouchable(on: Boolean) {
        // ROB-7：窗口未建（onStartCommand 前 service 可被 bind、companion 实例 onCreate 即发布；
        // onDestroy 后 post 残余）时 lateinit displayLp 未初始化——降级 no-op 防崩溃
        if (!built) return
        val touchable = displayLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (on == touchable) return
        displayLp.flags = if (on) {
            displayLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            displayLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(displayBox, displayLp) }
            .onFailure { PetLogger.e(TAG, "bubble touch mode update failed: ${it.message}") }
        PetLogger.d(TAG, "bubble touchable=$on")
    }

    // ---------------- S49 语音指示泡（聆听声纹/思考三点，同容器互斥文字泡） ----------------

    /**
     * 显示语音指示泡并认领语音 round（原「在听…」提示泡的同款接管语义——后续
     * OverlayBubbleSink.append 的回复 delta 以本 round 为令牌基础，删认领则回复丢）。
     * show_bubble=false 时随开关禁用（与文字泡同判）。返回 round 供工厂侧登记。
     */
    fun showVoiceIndicator(mode: VoiceSessionManager.IndicatorMode): Int {
        val round = claimBubbleRound(BubbleOwner.VOICE)
        when (bubbleOwnership.judgePublish(BubbleOwner.VOICE, round, Prefs.showBubble(this))) {
            BubbleOwnership.Verdict.APPLY -> handler.post {
                val ind = voiceIndicator ?: return@post
                ind.setMode(
                    if (mode == VoiceSessionManager.IndicatorMode.VOICEPRINT) VoiceIndicatorView.Mode.VOICEPRINT
                    else VoiceIndicatorView.Mode.DOTS,
                )
                bubble?.visibility = View.GONE // 互斥：指示态不显文字（我在泡由指示接管）
                ind.visibility = View.VISIBLE
                setBubbleTouchable(false) // 指示泡无内容可滑 → 恒穿透
                PetLogger.d(TAG, "voice indicator show: $mode, round=$round")
            }
            BubbleOwnership.Verdict.FORCE_HIDE -> {
                PetLogger.d(TAG, "show_bubble=false, indicator no-op + hide")
                forceHideBubble()
            }
            BubbleOwnership.Verdict.DROP_STALE ->
                PetLogger.d(TAG, "indicator show dropped: stale round=$round")
        }
        return round
    }

    /** 聆听声纹 RMS 直喂（VSM VAD 分支高频调用；view 侧 @Volatile 直写，不 post）。 */
    fun updateVoiceIndicatorLevel(rms: Float) {
        voiceIndicator?.onLevel(rms)
    }

    /** 收指示泡（只动指示视图，不碰文字泡；VSM transition 离聆听/思考时调用，幂等）。 */
    fun hideVoiceIndicator() {
        handler.post {
            voiceIndicator?.let {
                it.stopLoop()
                it.visibility = View.GONE
            }
            PetLogger.d(TAG, "voice indicator hide")
        }
    }

    // ---------------- 精灵状态服务级 API（S4 VoiceSessionManager 接线用） ----------------

    /** 切换精灵动画状态（listening->WAITING / processing->REVIEW / speaking->TALKING / idle->IDLE）。 */
    fun setPetState(state: PetState) {
        handler.post {
            petView?.setState(state)
        }
    }

    // ---------------- 全屏语音模式（S7-d）：双窗口隐藏/恢复 ----------------

    /** 诊断位：当前是否被全屏语音模式隐藏（仅可见性语义，与 overlay_enabled 无关）。 */
    @Volatile
    var windowsHidden = false
        private set

    /**
     * 隐藏/恢复双窗口（S7-d 全屏语音模式进/出配对调用）：只切双窗 root View 的
     * GONE/VISIBLE--不动 pref、不停服务、不动气泡 round 令牌。隐藏期间
     * showBubble/appendBubble/setPetState 落到 GONE View 上无副作用，无需短路。
     */
    fun setWindowsHidden(hidden: Boolean) {
        handler.post {
            windowsHidden = hidden
            val v = if (hidden) View.GONE else View.VISIBLE
            interactionBox?.visibility = v
            displayBox?.visibility = v
            PetLogger.i(TAG, "windows hidden -> $hidden")
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onDestroy() {
        PetLogger.i(TAG, "onDestroy, removing dual windows")
        cancelSlideAnimation() // 守卫：removeView 后不再有动画帧回调 updateViewLayout
        handler.removeCallbacks(heartbeatRunnable) // 停活性心跳
        PetOverlayState.markCleared()
        // 不写 overlay_enabled（S3 返工#5）：onDestroy 也会由系统 teardown（划卡/回收）触发，
        // 在这里落 false 会篡改用户持久化偏好、STICKY 重启无法恢复。
        // 落盘 false 只发生在显式关闭路径：菜单「隐藏悬浮窗」（本类）/ 设置页开关（stopService 前自管）。
        displayBox?.let { runCatching { wm.removeView(it) } }
        interactionBox?.let { runCatching { wm.removeView(it) } }
        displayBox = null
        interactionBox = null
        petView = null
        bubble = null
        voiceIndicator?.stopLoop()
        voiceIndicator = null
        built = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PetOverlay"

        /**
         * S31 双击打断修复：文字轮不在 VSM 管辖（文字轮播报期 vsm.state=IDLE，
         * vsm.cancelCurrentTurn 内部 no-op——板子/模拟器实测双击无效根因）。
         * ChatViewModel init 时把统一门控版 cancelCurrentTurn 挂到本桥；悬浮窗双击与
         * 长按菜单「打断」优先走桥，无桥（VM 未建）回退 VSM（历史行为，仅语音轮）。
         */
        @Volatile
        var chatInterrupt: (() -> Unit)? = null

        /**
         * S48 悬浮窗「创建新会话」桥：ChatViewModel init 时挂载（busyProvider 提供菜单弹出
         * 时刻的忙态快照供置灰；newSession 成功 true / busy 拒绝 false）。双桥均可为 null
         * （VM 未建极端态：菜单项置灰不可点，与「查看最近一轮」无桥时同语义）。
         */
        @Volatile
        var chatBusyProvider: (() -> Boolean)? = null

        @Volatile
        var chatNewSession: (() -> Boolean)? = null

        /** 悬浮窗侧统一打断入口：双击与长按菜单共用。 */
        fun requestInterrupt(context: android.content.Context) {
            val bridge = chatInterrupt
            if (bridge != null) {
                PetLogger.i(TAG, "overlay interrupt -> chat bridge (voice/text unified gate)")
                runCatching { bridge() }
            } else {
                PetLogger.i(TAG, "overlay interrupt -> vsm fallback (chat bridge absent)")
                runCatching { VoiceClientFactory.session(context).cancelCurrentTurn() }
            }
        }
        private const val EDGE_MARGIN_DP = 48
        private const val BUBBLE_GAP_DP = 4
        private const val SLIDE_DURATION_MS = 200L // S10 贴边滑入/滑出时长
        private const val BUBBLE_WIN_WIDTH_DP = 260 // S7-c：气泡窗固定宽 ~260dp（原 2×宠物 ~384dp）
        private const val BUBBLE_WIN_SLACK_DP = 4 // 气泡窗高 = 气泡 max 100dp + 余量
        private const val BUBBLE_EDGE_MARGIN_DP = 4 // 气泡距屏缘最小留白
        const val DISPLAY_TITLE = "xiaoduo:display"
        const val INTERACTION_TITLE = "xiaoduo:interaction"

        /** 进程内便捷口（调试 receiver / 后续 OverlayController 用）。 */
        @Volatile
        var instance: PetOverlayService? = null
            private set

        /** 无 binder 便捷转发：服务未运行 / 悬浮窗未构建时 no-op 不崩。 */
        fun setPetState(state: PetState) {
            instance?.setPetState(state)
        }

    }
}
