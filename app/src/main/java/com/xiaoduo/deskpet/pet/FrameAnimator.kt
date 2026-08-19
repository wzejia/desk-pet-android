package com.xiaoduo.deskpet.pet

/**
 * 帧动画推进器。纯 Kotlin（不依赖 Android API），由调用方按真实流逝时间驱动 tick()。
 * - 循环模式：播完回到第 0 帧。
 * - 单次模式：停在最后一帧并触发 onFinished（用于「点击挥手一遍回 idle」）。
 * - 切状态重置到第 0 帧（与桌面端行为一致，知识包 §4.1）。
 */
class FrameAnimator(initial: PetState = PetFrameTable.DEFAULT) {

    var state: PetState = initial
        private set

    var frameIndex: Int = 0
        private set

    var playOnce: Boolean = false
        private set

    /** 单次模式播完置 true，此后 tick 不再推进。 */
    var finished: Boolean = false
        private set

    var onFinished: (() -> Unit)? = null

    private var elapsedMs: Long = 0

    /** 单帧时长 = 整行动画时长 / 帧数。 */
    val perFrameMs: Long get() = state.durationMs / state.frames

    fun setState(newState: PetState, once: Boolean = false, onFinished: (() -> Unit)? = null) {
        state = newState
        frameIndex = 0
        elapsedMs = 0
        playOnce = once
        finished = false
        this.onFinished = onFinished
    }

    /** 推进 deltaMs 毫秒，返回推进后的帧索引。 */
    fun tick(deltaMs: Long): Int {
        if (finished || deltaMs <= 0) return frameIndex
        elapsedMs += deltaMs
        val pf = perFrameMs
        while (elapsedMs >= pf) {
            elapsedMs -= pf
            frameIndex++
            if (frameIndex >= state.frames) {
                if (playOnce) {
                    frameIndex = state.frames - 1
                    finished = true
                    elapsedMs = 0
                    val cb = onFinished
                    onFinished = null
                    cb?.invoke()
                    break
                } else {
                    frameIndex = 0
                }
            }
        }
        return frameIndex
    }

    /** 当前帧在 spritesheet 中的源矩形左上坐标。 */
    val sourceLeft: Int get() = frameIndex * PetFrameTable.FRAME_WIDTH
    val sourceTop: Int get() = state.row * PetFrameTable.FRAME_HEIGHT
}
