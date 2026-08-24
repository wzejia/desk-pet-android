package com.xiaoduo.deskpet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import com.xiaoduo.deskpet.pet.FrameAnimator
import com.xiaoduo.deskpet.pet.PetFrameTable
import com.xiaoduo.deskpet.pet.PetState

/**
 * 小朵渲染视图：从 assets/spritesheet.webp 按帧表切帧绘制。
 * App 内主页与悬浮窗复用同一个 View。
 */
class PetView(context: Context) : View(context) {

    val animator = FrameAnimator(PetFrameTable.DEFAULT)

    private val sheet: Bitmap =
        context.assets.open("spritesheet.webp").use { BitmapFactory.decodeStream(it) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()

    private var attached = false
    private var lastFrameTimeNs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!attached) return
            if (lastFrameTimeNs != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameTimeNs) / 1_000_000
                animator.tick(deltaMs)
            }
            lastFrameTimeNs = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setState(state: PetState) = animator.setState(state)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        lastFrameTimeNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        attached = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = animator.sourceLeft
        val t = animator.sourceTop
        src.set(l, t, l + PetFrameTable.FRAME_WIDTH, t + PetFrameTable.FRAME_HEIGHT)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(sheet, src, dst, paint)
    }

    companion object {
        /** 宠物视图尺寸（不含气泡位），单位 dp。 */
        const val PET_WIDTH_DP = 192
        const val PET_HEIGHT_DP = 208
        /** 悬浮窗尺寸 = 宠物尺寸 192x208dp（2026-07-30 裁决偏差 A：M1 不预留气泡位，M2 加气泡时 updateLayout 扩高） */
        const val WINDOW_WIDTH_DP = 192
        const val WINDOW_HEIGHT_DP = 208
        /** M2 气泡区高度（加气泡时窗口扩高用），M1 未启用。 */
        const val BUBBLE_HEIGHT_DP = 80
    }
}
