package com.xiaoduo.deskpet.pet

/**
 * 小朵精灵帧表。纯 Kotlin，不依赖 Android API，可 JVM 单测。
 * 数据源：assets/pet_states.json（与桌面端同构；S13 起 Android 行为有意偏离桌面端——
 * 用户 2026-08-24 拍板：循环态全部静止=idle 行第 0 帧，说话=idle 行 6 帧，
 * 唤醒跳/点击挥手取消。桌面端行布局素材未动，仅 Android 侧 row/frames 映射变更）。
 * sheet 1536x1872，帧 192x208，8 列 x 9 行，行 Y 偏移 = 208 * row。
 */
data class PetState(
    val name: String,
    val row: Int,
    val frames: Int,
    val durationMs: Long,
)

object PetFrameTable {
    const val FRAME_WIDTH = 192
    const val FRAME_HEIGHT = 208
    const val COLS = 8
    const val SHEET_WIDTH = 1536
    const val SHEET_HEIGHT = 1872

    // S13（2026-08-24 用户拍板）：IDLE/WAITING/REVIEW/FAILED = idle 行(row 0)第 0 帧静止；
    // TALKING = idle 行 6 帧 @120ms/帧（眨眼+嘴型微动，身体不动）；
    // RUNNING/WAVING/JUMPING 行保留但不接线（唤醒跳/点击挥手已取消）。
    val IDLE = PetState("idle", row = 0, frames = 1, durationMs = 1100)
    val RUNNING_RIGHT = PetState("running-right", row = 1, frames = 8, durationMs = 1060)
    val RUNNING_LEFT = PetState("running-left", row = 2, frames = 8, durationMs = 1060)
    val WAVING = PetState("waving", row = 3, frames = 6, durationMs = 900)
    val JUMPING = PetState("jumping", row = 4, frames = 5, durationMs = 840)
    val FAILED = PetState("failed", row = 0, frames = 1, durationMs = 1220)
    val WAITING = PetState("waiting", row = 0, frames = 1, durationMs = 1600)
    val TALKING = PetState("talking", row = 0, frames = 6, durationMs = 720)
    val REVIEW = PetState("review", row = 0, frames = 1, durationMs = 1500)

    val ALL: List<PetState> = listOf(
        IDLE, RUNNING_RIGHT, RUNNING_LEFT, WAVING, JUMPING, FAILED, WAITING, TALKING, REVIEW
    )

    val DEFAULT: PetState = IDLE

    fun byName(name: String): PetState? = ALL.firstOrNull { it.name == name }
}
