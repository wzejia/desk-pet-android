package com.xiaoduo.deskpet.pet

/**
 * 小朵精灵帧表。纯 Kotlin，不依赖 Android API，可 JVM 单测。
 * 数据源：桌面仓库 pet/pet_states.json（唯一真理，禁止漂移——知识包教训 #47）。
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

    val IDLE = PetState("idle", row = 0, frames = 6, durationMs = 1100)
    val RUNNING_RIGHT = PetState("running-right", row = 1, frames = 8, durationMs = 1060)
    val RUNNING_LEFT = PetState("running-left", row = 2, frames = 8, durationMs = 1060)
    val WAVING = PetState("waving", row = 3, frames = 6, durationMs = 900)
    val JUMPING = PetState("jumping", row = 4, frames = 5, durationMs = 840)
    val FAILED = PetState("failed", row = 5, frames = 8, durationMs = 1220)
    val WAITING = PetState("waiting", row = 6, frames = 6, durationMs = 1600)
    val TALKING = PetState("talking", row = 7, frames = 6, durationMs = 720)
    val REVIEW = PetState("review", row = 8, frames = 6, durationMs = 1500)

    val ALL: List<PetState> = listOf(
        IDLE, RUNNING_RIGHT, RUNNING_LEFT, WAVING, JUMPING, FAILED, WAITING, TALKING, REVIEW
    )

    val DEFAULT: PetState = IDLE

    fun byName(name: String): PetState? = ALL.firstOrNull { it.name == name }
}
