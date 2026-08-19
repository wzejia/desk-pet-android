package com.xiaoduo.deskpet.pet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约测试（知识包教训 #47 的护栏，U1）：
 * assets/pet_states.json（桌面端唯一真理的原样镜像）与 PetFrameTable（Kotlin 手写镜像）
 * 逐项比对，任一侧漂移即红。
 *
 * 上游源文件：E:\desk-pet-migration\desk-pet\pet\pet_states.json
 * 镜像 SHA256：914b11d7e28daad01a32873a4db63abecdc5304a03bea50090d69dc183848e12
 * （上游变动时：重新复制 + 更新本注释指纹 + 同步改 PetFrameTable）
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
    fun `rows are unique and within sheet`() {
        val rows = PetFrameTable.ALL.map { it.row }
        assertEquals("状态行号重复", rows.size, rows.toSet().size)
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
    }
}
