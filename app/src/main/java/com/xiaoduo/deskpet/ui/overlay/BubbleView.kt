package com.xiaoduo.deskpet.ui.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * 悬浮窗气泡（知识包 §4.2 参数逐项 + S7-c 升级）：
 * - max-height 100dp，超出内部滚动（ScrollingMovementMethod + 打字机 tick 后滚到底；
 *   程序控制滚动，不受显示层 FLAG_NOT_TOUCHABLE 约束）
 * - 圆角 10dp、不透明白底 #FFFFFF；深色模式反色 #171717
 *   （色值对齐 ui/theme/Theme.kt 色板：#0D0D0D/#F5F5F5/#171717）
 *   S3 二轮返工 P2：悬浮显示层（NOT_TOUCHABLE）被系统强制 0.8 alpha，
 *   原 0.92 半透明叠加后有效透明度仅 ≈0.74，深壁纸上呈浅灰卡；
 *   提为不透明后有效 0.8，文字对比足够。
 * - 1dp 描边（Theme.kt outline：浅 #E5E5E5 / 深 #2E2E2E），白底页面上不再隐形
 * - 字号 13sp（sp 自带 font-scale 换算）、pre-wrap（TextView 默认折行）
 * - markdown 基础集渲染（S7-c）：BubbleTextStrip 产 StyledText（BOLD/ITALIC/CODE span），
 *   本类转 SpannableString；行内代码背景取固定 0x33FFFFFF（深浅底上均可读，不引主题依赖）
 * - 打字机 40ms/字：showBubble 重置重打，appendBubble 流式续打；切片用 StyledText.prefix
 * - S39 手动滚动：窗口可触摸后（PetOverlayService 动态切 NOT_TOUCHABLE），用户上滑可看
 *   历史；触摸期间挂起自动拽底（否则下一 tick 立即拽回拉不动），松手贴底才恢复跟随
 *   （仲裁在 [BubbleScrollPolicy] 纯逻辑，5 测）；拖动滚动本体由 ScrollingMovementMethod 承担
 * - 偏差备忘：§4.2 的「背景模糊」未做（overlay 窗口级 blurBehind 会糊住整个显示层
 *   背景，非气泡局部；不透明底 + 1dp 描边已达可读性目标）
 */
class BubbleView(context: Context) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var target: StyledText = StyledText("")
    private var shown: Int = 0

    /** S39：用户拖动 vs 自动跟随仲裁。 */
    private val scrollPolicy = BubbleScrollPolicy()

    private val typeTick = object : Runnable {
        override fun run() {
            if (shown >= target.text.length) return
            shown++
            text = target.prefix(shown).toSpannable()
            // setText 只 requestLayout，当帧 layout 仍是旧的；post 到下一帧 layout
            // 刷新后再滚到底（否则末字触发换行时静止态少滚一行，停在不贴底位置）。
            // S39：用户拖动/未贴底时不拽（scrollPolicy 仲裁），用户看完回贴底自动恢复。
            if (scrollPolicy.shouldAutoFollow()) post { scrollToBottom() }
            handler.postDelayed(this, TYPEWRITER_MS)
        }
    }

    init {
        val dp = resources.displayMetrics.density
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Theme.kt 色板：浅色 surface #FFFFFF / onSurface #0D0D0D；深色 surfaceVariant #171717 / onSurface #F5F5F5
        val bgColor = if (dark) 0xFF171717.toInt() else 0xFFFFFFFF.toInt()
        setTextColor(if (dark) 0xFFF5F5F5.toInt() else 0xFF0D0D0D.toInt())
        textSize = 13f // sp，自动乘 font-scale（S7-c：11→13）
        maxHeight = (MAX_HEIGHT_DP * dp).toInt()
        // 超高内部滚动：打字机每次更新后程序滚到底（显示层 NOT_TOUCHABLE 不拦程序滚动）
        movementMethod = ScrollingMovementMethod.getInstance()
        setPadding(
            (PAD_H_DP * dp).toInt(), (PAD_V_DP * dp).toInt(),
            (PAD_H_DP * dp).toInt(), (PAD_V_DP * dp).toInt(),
        )
        // 1dp 描边（S3 返工#3）：白底页面上白底气泡近乎隐形；色取 Theme.kt outline
        val outlineColor = if (dark) 0xFF2E2E2E.toInt() else 0xFFE5E5E5.toInt()
        background = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * dp
            setColor(bgColor)
            setStroke((1f * dp).toInt().coerceAtLeast(1), outlineColor)
        }
    }

    /** 新文本：重置打字机从头打。 */
    fun typeNew(fullText: StyledText) {
        handler.removeCallbacks(typeTick)
        target = fullText
        shown = 0
        text = ""
        scheduleTick()
    }

    /** 流式追加：已打部分保留，剩余按 40ms/字续打。 */
    fun append(delta: StyledText) {
        if (delta.text.isEmpty()) return
        target += delta
        scheduleTick()
    }

    /** 停止打字并清空（hideBubble 用）。 */
    fun cancelAndClear() {
        handler.removeCallbacks(typeTick)
        target = StyledText("")
        shown = 0
        text = ""
    }

    private fun scheduleTick() {
        handler.removeCallbacks(typeTick)
        if (shown < target.text.length) handler.post(typeTick)
    }

    /** 滚到最后一行底部。须在 layout 刷新后调用（typeTick 经 post 延迟一帧）；
     *  layout 为 null（view 尚未首帧布局）时跳过，下一 tick 再滚。 */
    private fun scrollToBottom() {
        val l = layout ?: return
        val y = l.getLineTop(l.lineCount) - height
        scrollTo(0, if (y > 0) y else 0)
    }

    /** S39：视口是否贴底（松手时裁决是否恢复跟随；1px 容差防浮点/取整抖动）。 */
    private fun isAtBottom(): Boolean {
        val l = layout ?: return true // 未布局（内容不超屏）视为贴底
        val maxY = l.getLineTop(l.lineCount) - height
        return scrollY >= maxY - 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scrollPolicy.onDragStart()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scrollPolicy.onDragEnd(isAtBottom())
        }
        // 拖动滚动本体由 ScrollingMovementMethod 处理（movementMethod 使 View 可点击，
        // DOWN 被消费 = 气泡区域点击不透传下层——滚动可用的必要代价，语义见窗口 flag 注）
        return super.onTouchEvent(event)
    }

    companion object {
        const val TYPEWRITER_MS = 40L   // 40ms/字（25 字/s）
        const val MAX_HEIGHT_DP = 100   // S7-c：74→100，超高内部滚动
        const val CORNER_RADIUS_DP = 10 // §4.2 圆角 10px → dp
        private const val PAD_H_DP = 10
        private const val PAD_V_DP = 6

        /** 行内代码背景：固定半透明浅白，气泡深浅底上均可读（S7-c：不引主题依赖）。 */
        private const val CODE_BG_COLOR = 0x33FFFFFF

        /** StyledText → SpannableString（BOLD/ITALIC 样式 + CODE 等宽字体与背景）。 */
        fun StyledText.toSpannable(): SpannableString {
            val ss = SpannableString(text)
            for (span in spans) {
                when (span.type) {
                    Type.BOLD -> ss.setSpan(
                        StyleSpan(Typeface.BOLD),
                        span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    Type.ITALIC -> ss.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    Type.CODE -> {
                        ss.setSpan(
                            TypefaceSpan("monospace"),
                            span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        ss.setSpan(
                            BackgroundColorSpan(CODE_BG_COLOR),
                            span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
            return ss
        }
    }
}
