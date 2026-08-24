package com.xiaoduo.deskpet.pet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约测试（知识包教训 #47 的护栏，U1）：
 * assets/pet_states.json 与 PetFrameTable（Kotlin 手写镜像）逐项比对，任一侧漂移即红。
 *
 * 原为桌面端唯一真理的原样镜像；S13（2026-08-24 用户拍板）起 Android 行为有意偏离桌面端：
 * idle/waiting/review/failed 静止=row0 第 0 帧，talking=row0 6 帧，唤醒跳/点击挥手取消，
 * spritesheet 素材未动。镜像 SHA256：60b45124a1d2d75dda4f8ee9c7dbc50a783e65c49ba5c81a300848865f454366
 * （再变动时：同步改 PetFrameTable + 更新本注释指纹）
 *
 * 注意：JVM 单测工作目录 = app 模块目录，故可直接读 src/main/assets 下的真实出厂文件，
 * 不复制到 test resources，避免镜像套镜像。
 */
class PetFrameTableContractTest {

    private val jsonFile = File("src/main/assets/pet_states.json")

    private val json: JSONObject by lazy {
        assertTrue("pet_states.json 未进 assets：$jsonFile", jsonFile.exists())
        JSONObject(jsonFile.readText())
    }

    @Test
    fun `meta matches sheet constants`() {
        val meta = json.getJSONObject("_meta")
        assertEquals(PetFrameTable.FRAME_WIDTH, meta.getInt("frame_width"))
        assertEquals(PetFrameTable.FRAME_HEIGHT, meta.getInt("frame_height"))
        assertEquals(PetFrameTable.COLS, meta.getInt("cols"))
        assertEquals(PetFrameTable.SHEET_WIDTH, meta.getInt("sheet_width"))
        assertEquals(PetFrameTable.SHEET_HEIGHT, meta.getInt("sheet_height"))
    }

    @Test
    fun `default state matches meta`() {
        assertEquals(
            json.getJSONObject("_meta").getString("default_state"),
            PetFrameTable.DEFAULT.name
        )
    }

    @Test
    fun `every table state matches json row frames duration`() {
        for (state in PetFrameTable.ALL) {
            val key = "state '${state.name}'"
            assertTrue("$key 在 JSON 中缺失", json.has(state.name))
            val j = json.getJSONObject(state.name)
            assertEquals("$key row 漂移", state.row, j.getInt("row"))
            assertEquals("$key frames 漂移", state.frames, j.getInt("frames"))
            assertEquals("$key duration_ms 漂移", state.durationMs, j.getLong("duration_ms"))
        }
    }

    @Test
    fun `json has no extra states beyond table`() {
        val extra = json.keys().asSequence()
            .filter { it != "_meta" }
            .filter { PetFrameTable.byName(it) == null }
            .toList()
        assertTrue("JSON 有帧表未收录的状态：$extra", extra.isEmpty())
    }

    @Test
    fun `static states share idle row zero and talking uses idle row`() {
        // S13（2026-08-24 用户拍板）：循环态全部静止=idle 行(row 0)第 0 帧；说话=idle 行 6 帧
        for (state in listOf(PetFrameTable.IDLE, PetFrameTable.WAITING, PetFrameTable.REVIEW, PetFrameTable.FAILED)) {
            assertEquals("state '${state.name}' 应为静止 row 0", 0, state.row)
            assertEquals("state '${state.name}' 应为 frames=1 静止", 1, state.frames)
        }
        assertEquals(0, PetFrameTable.TALKING.row)
        assertEquals(6, PetFrameTable.TALKING.frames)
        assertEquals(720L, PetFrameTable.TALKING.durationMs) // 120ms/帧
    }

    @Test
    fun `states within sheet and legacy rows unique`() {
        for (state in PetFrameTable.ALL) {
            val bottom = (state.row + 1) * PetFrameTable.FRAME_HEIGHT
            assertTrue(
                "state '${state.name}' 行超出 sheet 高度",
                bottom <= PetFrameTable.SHEET_HEIGHT
            )
        }
        for (state in PetFrameTable.ALL) {
            assertTrue(
                "state '${state.name}' 帧数超列数",
                state.frames <= PetFrameTable.COLS
            )
            assertNotNull(PetFrameTable.byName(state.name))
        }
        // S13：row 0 有意共享；row 1-4 遗留行仍各自唯一（防未来素材表误并）
        val legacyRows = PetFrameTable.ALL.filter { it.row != 0 }.map { it.row }
        assertEquals("row0 以外行号重复", legacyRows.size, legacyRows.toSet().size)
    }
}
