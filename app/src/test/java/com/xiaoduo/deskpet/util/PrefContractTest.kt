package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
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

    @Test
    fun `selfheal keys are preserved verbatim`() {
        // 自愈重启钩（诊断报告 §7.1.3）：killProcess 自尽冷却戳（毫秒时间戳，0/缺=从未）
        assertEquals(setOf("voice_proc_last_restart"), PrefContract.SELFHEAL_KEYS)
    }

    @Test
    fun `s70 pir wake key is pinned`() {
        // S70 PIR 红外唤醒开关（易声板第 1 路保留 IO；默认关=用户 2026-10-26 拍板）
        assertEquals("pir_wake_enabled", PrefContract.KEY_PIR_WAKE_ENABLED)
        assertEquals(false, PrefContract.DEFAULT_PIR_WAKE_ENABLED)
        assertEquals(setOf("pir_wake_enabled"), PrefContract.S70_KEYS)
    }
}
