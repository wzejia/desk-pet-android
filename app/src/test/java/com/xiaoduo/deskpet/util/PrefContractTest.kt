package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prefs 迁移保护：M1 既有键名/默认值抽成纯常量后，升级不得丢键。
 */
class PrefContractTest {

    @Test
    fun `m1 keys are preserved verbatim`() {
        assertEquals(setOf("pet_scale", "show_bubble"), PrefContract.M1_KEYS)
    }

    @Test
    fun `s7 keys are preserved verbatim`() {
        // S7-d 全屏语音模式转写开关：默认关（纯 TTS+小朵动画，ADR 0003）
        assertEquals(setOf("fullscreen_transcript"), PrefContract.S7_KEYS)
        assertEquals(false, PrefContract.DEFAULT_FULLSCREEN_TRANSCRIPT)
    }

    @Test
    fun `s41 shelf base url key and default are pinned`() {
        // S41 密集架 MCP 动画联动桥 POST 目标（同机默认 127.0.0.1:8848；分机部署改密集架 IP）
        assertEquals("shelf_base_url", PrefContract.KEY_SHELF_BASE_URL)
        assertEquals("http://127.0.0.1:8848", PrefContract.DEFAULT_SHELF_BASE_URL)
        assertEquals(setOf("shelf_base_url"), PrefContract.S41_KEYS)
    }

    @Test
    fun `defaults stay within declared bounds`() {
        // 盲测 v3 遮挡克制批评：默认缩放有意从 1.0 降到 0.5（≈1.9×图标高，不压 dock）
        assertEquals(0.5f, PrefContract.DEFAULT_SCALE)
        assertTrue(PrefContract.DEFAULT_SCALE in PrefContract.MIN_SCALE..PrefContract.MAX_SCALE)
        assertTrue(PrefContract.DEFAULT_SHOW_BUBBLE)
        assertEquals("xiaoduo_settings", PrefContract.FILE_SETTINGS)
    }

    // ==== 以下为 P3-T4（ocr[370]-[374]）新增：键保全集对账 + 默认值常量化 + legacy 甄别 ====

    @Test
    fun `all declared keys are registered in ALL_KEYS or LEGACY_KEYS`() {
        // ocr[370]：「无未登记键」完备性兜底——反射扫描全部 KEY_ 前缀常量，
        // 新键漏登记（=升级清理可静默删掉）必须在此变红
        val declared = PrefContract::class.java.fields
            .filter { it.name.startsWith("KEY_") }
            .map { it.get(null) as String }
            .toSet()
        val unregistered = declared - PrefContract.ALL_KEYS - PrefContract.LEGACY_KEYS
        assertEquals("存在未登记键：$unregistered", emptySet<String>(), unregistered)
    }

    @Test
    fun `sprint sets partition ALL_KEYS without overlap`() {
        val sets = listOf(
            PrefContract.M1_KEYS, PrefContract.S3_KEYS, PrefContract.S5_KEYS,
            PrefContract.S7_KEYS, PrefContract.S16_KEYS, PrefContract.S18_KEYS,
            PrefContract.S20_KEYS, PrefContract.S21_KEYS, PrefContract.S23_KEYS,
            PrefContract.S24_KEYS, PrefContract.S26_KEYS, PrefContract.S29_KEYS,
            PrefContract.S31_KEYS, PrefContract.S41_KEYS,
        )
        val union = sets.fold(emptySet<String>()) { acc, s -> acc + s }
        assertEquals("ALL_KEYS 必须等于各 Sprint 键集之和", union, PrefContract.ALL_KEYS)
        for (i in sets.indices) {
            for (j in i + 1 until sets.size) {
                assertTrue(
                    "键集重叠：${sets[i] intersect sets[j]}",
                    sets[i].intersect(sets[j]).isEmpty(),
                )
            }
        }
    }

    @Test
    fun `sprint key sets are pinned verbatim`() {
        // 键名逐字钉死：防拼错键/静默改名（改名=丢用户配置）
        assertEquals(setOf("overlay_x", "overlay_y", "overlay_enabled", "voice_enabled"), PrefContract.S3_KEYS)
        assertEquals(setOf("kws_enabled", "kws_energy_gate"), PrefContract.S5_KEYS)
        assertEquals(setOf("platform_agent_id", "platform_agent_name"), PrefContract.S16_KEYS)
        assertEquals(setOf("speak_mcp_result"), PrefContract.S20_KEYS)
        assertEquals(setOf("rewake_after_tool"), PrefContract.S21_KEYS)
        assertEquals(setOf("echo_cancel"), PrefContract.S23_KEYS)
        assertEquals(setOf("tts_volume"), PrefContract.S24_KEYS)
        assertEquals(setOf("device_base_url"), PrefContract.S26_KEYS)
        assertEquals(setOf("kws_auto_calib_done"), PrefContract.S29_KEYS)
        assertEquals(setOf("jump_target_package"), PrefContract.S31_KEYS)
    }

    @Test
    fun `s3 keys stay in sync with Prefs inline constants`() {
        // S3 键此前只活在 Prefs 内联字面量（ocr[219] 指控）；本次入约但消费方引用不动，
        // 用双写对账防静默漂移（Prefs const 编译期内联，JVM 测试安全）
        assertEquals(Prefs.KEY_OVERLAY_X, PrefContract.KEY_OVERLAY_X)
        assertEquals(Prefs.KEY_OVERLAY_Y, PrefContract.KEY_OVERLAY_Y)
        assertEquals(Prefs.KEY_OVERLAY_ENABLED, PrefContract.KEY_OVERLAY_ENABLED)
        assertEquals(Prefs.KEY_VOICE_ENABLED, PrefContract.KEY_VOICE_ENABLED)
        assertEquals(Prefs.POSITION_UNSET, PrefContract.POSITION_UNSET)
        assertEquals(Prefs.DEFAULT_OVERLAY_ENABLED, PrefContract.DEFAULT_OVERLAY_ENABLED)
        assertEquals(Prefs.DEFAULT_VOICE_ENABLED, PrefContract.DEFAULT_VOICE_ENABLED)
    }

    @Test
    fun `hoisted defaults are pinned to runtime values`() {
        // ocr[371]：默认值只存在注释 → 常量化；消费方当前硬编码值即运行时事实，
        // 契约常量必须与其一致（消费方改引=越界上报，见汇报）
        assertEquals(true, PrefContract.DEFAULT_SPEAK_MCP_RESULT)
        assertEquals(true, PrefContract.DEFAULT_REWAKE_AFTER_TOOL)
        assertEquals(true, PrefContract.DEFAULT_ECHO_CANCEL)
        assertEquals(0.45f, PrefContract.DEFAULT_TTS_VOLUME)
        assertEquals(0.05f, PrefContract.DEFAULT_VAD_SPEECH_LEVEL)
        assertEquals(Prefs.DEFAULT_VAD_SPEECH_LEVEL.toFloat(), PrefContract.DEFAULT_VAD_SPEECH_LEVEL)
        assertEquals(0, PrefContract.DEFAULT_MIC_SOURCE_MODE)
        assertEquals("", PrefContract.DEFAULT_AGENT_DISPLAY_NAME)
        assertEquals(Prefs.DEFAULT_DEVICE_BASE_URL, PrefContract.DEFAULT_DEVICE_BASE_URL)
        assertEquals(false, PrefContract.DEFAULT_AUTO_CALIB_DONE)
        assertEquals("com.yinduo.compactShelf", PrefContract.DEFAULT_JUMP_PACKAGE)
    }

    @Test
    fun `legacy migration source key is pinned and out of upgrade-prunable sets`() {
        // ocr[374]：mic_raw_source 是 mic_source_mode 的迁移源，Prefs 仍在读它兜底；
        // 升级清理（ALL_KEYS 驱动）不得把它当可删键
        assertEquals(setOf("mic_raw_source"), PrefContract.LEGACY_KEYS)
        assertFalse(PrefContract.ALL_KEYS.contains(PrefContract.KEY_MIC_RAW_SOURCE))
        assertTrue(PrefContract.S18_KEYS.intersect(PrefContract.LEGACY_KEYS).isEmpty())
    }

    @Test
    fun `jump package default stays in sync with AppLauncher fallback`() {
        // ocr[372]：DEFAULT_JUMP_PACKAGE 与 AppLauncher.DEFAULT_TARGET_PACKAGE 双写——
        // 消费方改引（AppLauncher→PrefContract）属越界上报；本测试先钉住两处一致防静默漂移
        assertEquals(AppLauncher.DEFAULT_TARGET_PACKAGE, PrefContract.DEFAULT_JUMP_PACKAGE)
    }
}
