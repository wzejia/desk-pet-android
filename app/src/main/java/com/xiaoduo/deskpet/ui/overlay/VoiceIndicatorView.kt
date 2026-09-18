package com.xiaoduo.deskpet.ui.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.View

/**
 * S49 语音指示泡（悬浮窗版聆听声纹 / 思考三点，用户定案 2026-09-17）：
 * - 聆听（LISTENING）：镜像柱声纹 ×13，真实麦克风 RMS 驱动（[onLevel] 直喂目标值，
 *   帧循环内 EMA 120ms 时间常数趋近——全屏语音页同款帧率无关平滑）；
 * - 思考（PROCESSING）：三点错相跳动（0.85s 升余弦，相位各偏 1/3）；
 * - 恒定 132×40dp（[VoiceIndicatorModel.INDICATOR_W_DP]/[VoiceIndicatorModel.INDICATOR_H_DP]），
 *   与单行文字泡同高，两态切换气泡尺寸零变化；
 * - 自绘气泡卡背景（10dp 圆角 / 白底 1dp 描边 / 深色反色 #171717——BubbleView 同源色值），
 *   与 BubbleView 同容器二选一显示（互斥由 PetOverlayService 维护）。
 *
 * 动画管线：Choreographer 自循环帧回调（仅可见时跑）+ invalidate——plain View 无重组
 * 概念，不存在全屏页 Compose 版的板子冻结病史（S25c 是 Compose 帧管线问题）；
 * RMS 目标值 @Volatile 直写（VAD 分支每块喂，~12-50Hz），draw 阶段只读零分配。
 */
class VoiceIndicatorView(context: Context) : View(context) {

    enum class Mode { VOICEPRINT, DOTS }

    private val dp = resources.displayMetrics.density
    private var mode: Mode = Mode.VOICEPRINT

    /** VSM VAD 分支直喂的 RMS 目标值（已归一化），帧循环里 EMA 趋近。 */
    @Volatile
    private var rmsTarget = 0f

    /** EMA 后的显示电平（仅帧循环线程读写）。 */
    private var displayLevel = 0f

    /** 帧循环时间戳（ms），onDraw 读；未跑帧循环时 = 上次值（静止态也可安全绘制）。 */
    private var timeMs = 0L
    private var lastFrameNs = 0L
    private var loopRunning = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HALO_CYAN }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT_COLOR }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!loopRunning) return
            val dtMs = if (lastFrameNs == 0L) 16f
            else ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameNs = frameTimeNanos
            timeMs = frameTimeNanos / 1_000_000
            // 帧率无关 EMA：k = 1 − e^(−dt/120)（全屏页 ListeningWaveform 同款时间常数）
            val k = 1f - kotlin.math.exp(-dtMs / 120f)
            displayLevel += (rmsTarget - displayLevel) * k
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // 气泡卡外观：BubbleView.init 同源（圆角 10dp / 白 #FFFFFF·深 #171717 / 1dp 描边）
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        background = GradientDrawable().apply {
            cornerRadius = 10f * dp
            setColor(if (dark) 0xFF171717.toInt() else Color.WHITE)
            setStroke(
                (1f * dp).toInt().coerceAtLeast(1),
                if (dark) 0xFF2E2E2E.toInt() else 0xFFE5E5E5.toInt(),
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (VoiceIndicatorModel.INDICATOR_W_DP * dp).toInt(),
            (VoiceIndicatorModel.INDICATOR_H_DP * dp).toInt(),
        )
    }

    /** 切模式（show 时调用；同模式重复调用重置动画相位，无副作用）。 */
    fun setMode(m: Mode) {
        mode = m
        rmsTarget = 0f
        displayLevel = 0f
        startLoop()
    }

    /** RMS 目标直喂（VSM VAD 分支，任意线程；内部归一化 clamp）。 */
    fun onLevel(rms: Float) {
        rmsTarget = VoiceIndicatorModel.normalize(rms)
    }

    /** 停帧循环（hide 时调用；幂等）。 */
    fun stopLoop() {
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun startLoop() {
        if (loopRunning || !isAttachedToWindow) return
        loopRunning = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.VOICEPRINT -> drawVoiceprint(canvas)
            Mode.DOTS -> drawDots(canvas)
        }
    }

    /** 镜像柱声纹：柱高 = minH + (区高−minH)·level·包络·摆动，中轴对称展开。 */
    private fun drawVoiceprint(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val areaH = VoiceIndicatorModel.BARS_AREA_H_DP * dp
        val areaTop = (h - areaH) / 2f
        val minH = areaH * VoiceIndicatorModel.BAR_MIN_FRACTION
        val contentW = VoiceIndicatorModel.BARS_CONTENT_W_DP * dp
        val barW = VoiceIndicatorModel.BAR_W_DP * dp
        val gap = VoiceIndicatorModel.BAR_GAP_DP * dp
        val x0 = (w - contentW) / 2f
        val t = timeMs / 1000f
        val cy = areaTop + areaH / 2f
        for (i in 0 until VoiceIndicatorModel.BAR_COUNT) {
            val frac = VoiceIndicatorModel.barFraction(i, displayLevel, t)
            val barH = minH + (areaH - minH) * frac
            val x = x0 + i * (barW + gap)
            canvas.drawRoundRect(x, cy - barH / 2f, x + barW, cy + barH / 2f, barW / 2f, barW / 2f, barPaint)
        }
    }

    /** 三点错相跳动：cy = 卡高/2 − lift·hop（hop 升余弦 0.85s 错相 1/3）。 */
    private fun drawDots(canvas: Canvas) {
        val h = height.toFloat()
        val dotR = VoiceIndicatorModel.DOT_D_DP * dp / 2f
        val lift = VoiceIndicatorModel.DOT_LIFT_DP * dp
        val t = timeMs / 1000f
        for (i in 0 until VoiceIndicatorModel.DOT_COUNT) {
            val hop = VoiceIndicatorModel.dotHop(i, t)
            val cx = VoiceIndicatorModel.dotCenterXFrac(i) * width
            val cy = h / 2f - lift * hop
            canvas.drawCircle(cx, cy, dotR, dotPaint)
        }
    }

    companion object {
        /** HaloCyan（FullscreenVoiceScreen 同源）——声纹柱色。 */
        private val HALO_CYAN = 0xFF19B8D8.toInt()

        /** DotColor（FullscreenVoiceScreen 同源）——思考点色。 */
        private val DOT_COLOR = 0xFF9AA0A6.toInt()
    }
}
