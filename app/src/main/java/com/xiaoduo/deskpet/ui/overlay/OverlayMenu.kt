package com.xiaoduo.deskpet.ui.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 长按菜单（KD15）：PopupWindow 四项--打开 App / 查看最近一轮 / 语音开关 / 隐藏悬浮窗。
 * 样式对齐 ui/theme/Theme.kt 色板（浅色 surface #FFFFFF/onSurface #0D0D0D/outline #E5E5E5；
 * 深色 surfaceVariant #171717/onSurface #F5F5F5/outline #2E2E2E），圆角取 XiaoduoDimens.CornerCard=16dp。
 * S3 返工#1：popup 固定宽 [MENU_WIDTH_DP]dp（LinearLayout 竖排子行默认 MATCH_PARENT，
 * WRAP_CONTENT popup 会被撑到近全屏）；「语音开关」行尾带状态色点（绿=开 / 灰=关）。
 * S8-6：新增「查看最近一轮」--悬浮窗气泡 NOT_TOUCHABLE 无法回翻，菜单入口是唯一回看通道；
 * 无 assistant 内容（[canViewLastRound]=false）时置灰不可点，避免「打开 App 后无定位」误导。
 * 2026-08-13：新增「打断」--悬浮窗侧 barge-in 入口（停 TTS/「我在」/聆听/管线）。
 * 门控 [canInterrupt]=VSM 非 IDLE（快照语义，与「查看最近一轮」同范式）；IDLE 期置灰不可点。
 * S10：popup 横向钳位屏内--贴边态锚点半出屏时菜单不裁出屏（与纵向翻转同范式）。
 */
object OverlayMenu {

    private const val MENU_WIDTH_DP = 184

    fun show(
        anchor: View,
        voiceEnabled: Boolean,
        onOpenApp: () -> Unit,
        onToggleVoice: () -> Boolean, // 返回切换后的新状态
        onHideOverlay: () -> Unit,
        canViewLastRound: Boolean,
        onViewLastRound: () -> Unit,
        canInterrupt: Boolean,
        onInterrupt: () -> Unit,
    ) {
        val context = anchor.context
        val dp = context.resources.displayMetrics.density
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (dark) 0xFF171717.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (dark) 0xFFF5F5F5.toInt() else 0xFF0D0D0D.toInt()
        val dividerColor = if (dark) 0xFF2E2E2E.toInt() else 0xFFE5E5E5.toInt()
        val dotOffColor = if (dark) 0xFF3A3A3C.toInt() else 0xFFC7C7CC.toInt()

        fun stateDot(enabled: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (enabled) DOT_ON_COLOR else dotOffColor)
        }

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * dp
                setColor(bgColor)
            }
        }

        fun row(label: String): TextView = TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            setPadding(
                (16 * dp).toInt(), (12 * dp).toInt(),
                (16 * dp).toInt(), (12 * dp).toInt(),
            )
        }

        fun divider(): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(dividerColor)
        }

        val popup = PopupWindow(
            menu,
            (MENU_WIDTH_DP * dp).toInt(), // 固定宽：避免 MATCH_PARENT 子行把 WRAP_CONTENT 撑满屏
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true, // focusable：点外部可 dismiss
        ).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 8f * dp
        }

        row("打开 App").also {
            it.setOnClickListener { popup.dismiss(); onOpenApp() }
            menu.addView(it)
        }
        menu.addView(divider())

        // 打断（barge-in）：VSM IDLE（无聆听/生成/播报在途）时置灰不可点
        row("打断").also {
            if (canInterrupt) {
                it.setOnClickListener { popup.dismiss(); onInterrupt() }
            } else {
                it.alpha = 0.4f
                it.isEnabled = false
            }
            menu.addView(it)
        }
        menu.addView(divider())

        // S8-6「查看最近一轮」：canViewLastRound=false（气泡从未显示过非空内容）时置灰不可点
        row("查看最近一轮").also {
            if (canViewLastRound) {
                it.setOnClickListener { popup.dismiss(); onViewLastRound() }
            } else {
                it.alpha = 0.4f
                it.isEnabled = false
            }
            menu.addView(it)
        }
        menu.addView(divider())

        // 语音开关行：文本 + 行尾状态色点（真实开关态指示）
        val voiceDot = View(context).apply {
            val size = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = stateDot(voiceEnabled)
        }
        val voiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * dp).toInt(), (12 * dp).toInt(),
                (16 * dp).toInt(), (12 * dp).toInt(),
            )
            addView(
                TextView(context).apply {
                    text = "语音开关"
                    setTextColor(textColor)
                    textSize = 14f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(voiceDot)
        }
        voiceRow.setOnClickListener {
            voiceDot.background = stateDot(onToggleVoice())
        }
        menu.addView(voiceRow)
        menu.addView(divider())

        row("隐藏悬浮窗").also {
            it.setOnClickListener { popup.dismiss(); onHideOverlay() }
            menu.addView(it)
        }

        // 锚点下方空间不足时翻到上方（宠物贴屏底时菜单不被裁出屏）
        menu.measure(
            View.MeasureSpec.makeMeasureSpec((MENU_WIDTH_DP * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = menu.measuredHeight
        val popupW = (MENU_WIDTH_DP * dp).toInt()
        val anchorLoc = IntArray(2).also(anchor::getLocationOnScreen)
        val screenH = context.resources.displayMetrics.heightPixels
        val screenW = context.resources.displayMetrics.widthPixels
        val roomBelow = screenH - (anchorLoc[1] + anchor.height)
        val yoff = if (roomBelow < popupH && anchorLoc[1] >= popupH) -(anchor.height + popupH) else 0
        // S10 横向钳位：贴边态锚点半出屏（左半身在屏外/右半身压屏缘）时，showAsDropDown
        // 默认左对齐锚点会把菜单裁出屏--popup 左缘钳回 [0, 屏宽-popupW]
        // （与上方纵向翻转「出屏不出界」同一范式；屏宽于菜单恒成立，coerceAtLeast 仅防御）
        val xoff = anchorLoc[0].coerceIn(0, (screenW - popupW).coerceAtLeast(0)) - anchorLoc[0]
        popup.showAsDropDown(anchor, xoff, yoff)
    }

    private const val DOT_ON_COLOR = 0xFF34C759.toInt()
}
