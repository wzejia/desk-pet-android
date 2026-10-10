package com.xiaoduo.deskpet.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-3（ocr[145]，spec 附录 B 3）：EncryptedSharedPreferences 透明迁移的 JVM 测试锚。
 * 策略层（回落次序/搬迁判定）纯 Kotlin；真 Keystore 集成（跨重启）不在本票，挂账主对话部署批。
 *
 * 锚（任务书 P3-T4 §1）：
 * ① 旧值存在 → 读到旧值且触发搬迁标记（写回新库=标记；旧库保留）；
 * ② 搬迁后同键再读 = 新值（不再回落）；
 * ③ 两库都空 → 默认值（且不把默认值写进新库）；
 * ④ 异带默认值键：两库空 → 键自定义默认；旧值在 → 原样搬迁。
 *
 * 变异锚：删回落读（策略跳过 LEGACY 分支）→ ① 红。
 */
class SecurePrefsTest {

    /** JVM 内存版 SharedPreferences 桩：实现接口本体，不触发 android.jar 桩方法。 */
    private class FakePrefs : SharedPreferences {
        val data = LinkedHashMap<String, Any?>()

        private fun raw(key: String?): Any? = key?.let { data[it] }

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            raw(key) as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (raw(key) as MutableSet<String>?) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = raw(key) as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = raw(key) as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = raw(key) as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            raw(key) as Boolean? ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val data: LinkedHashMap<String, Any?>) :
            SharedPreferences.Editor {
            private fun put(key: String?, value: Any?) {
                if (key != null) data[key] = value
            }

            override fun putString(key: String?, value: String?) = apply { put(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { put(key, values) }
            override fun putInt(key: String?, value: Int) = apply { put(key, value) }
            override fun putLong(key: String?, value: Long) = apply { put(key, value) }
            override fun putFloat(key: String?, value: Float) = apply { put(key, value) }
            override fun putBoolean(key: String?, value: Boolean) = apply { put(key, value) }
            override fun remove(key: String?) = apply { if (key != null) data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    private fun migrating(): Triple<MigratingSharedPreferences, FakePrefs, FakePrefs> {
        val secure = FakePrefs()
        val legacy = FakePrefs()
        return Triple(MigratingSharedPreferences(secure, legacy), secure, legacy)
    }

    // ---- SecurePrefStrategy 纯逻辑（回落次序裁决）----

    @Test
    fun `strategy prefers new store without migration`() {
        val d = SecurePrefStrategy.decide(hasNew = true, hasLegacy = true)
        assertEquals(SecurePrefStrategy.Source.NEW, d.source)
        assertFalse(d.migrate)
    }

    @Test
    fun `strategy falls back to legacy and flags migration`() {
        val d = SecurePrefStrategy.decide(hasNew = false, hasLegacy = true)
        assertEquals(SecurePrefStrategy.Source.LEGACY, d.source)
        assertTrue(d.migrate)
    }

    @Test
    fun `strategy takes default when both stores empty`() {
        val d = SecurePrefStrategy.decide(hasNew = false, hasLegacy = false)
        assertEquals(SecurePrefStrategy.Source.DEFAULT, d.source)
        assertFalse(d.migrate)
    }

    // ---- 锚 ①：旧值存在 → 读到旧值 + 搬迁标记（写回新库），旧库保留 ----

    @Test
    fun `anchor1 legacy value read back and migration mark fires`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putString("platform_token", "old-token-value")

        assertEquals("old-token-value", m.getString("platform_token", null))

        // 搬迁写回 = 标记：键已落新库且值一致
        assertTrue("搬迁标记：新库应已含该键", secure.contains("platform_token"))
        assertEquals("old-token-value", secure.getString("platform_token", null))
        // 旧库只搬不删（回读兼容，板子存量数据在用）
        assertTrue("旧库数据不得删除", legacy.contains("platform_token"))
    }

    // ---- 锚 ②：搬迁后同键再读 = 新值，不再回落旧库 ----

    @Test
    fun `anchor2 after migration same key reads new value`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putString("k", "old-value")
        assertEquals("old-value", m.getString("k", "def")) // 首读触发搬迁

        // 新库写入新值后（模拟续命写路径），同键再读必须命中新库
        secure.edit().putString("k", "new-value")
        // 即便旧库残留更旧的值也不得回落（新库优先）
        assertEquals("new-value", m.getString("k", "def"))
    }

    // ---- 锚 ③：两库都空 → 默认值，且默认值不落新库 ----

    @Test
    fun `anchor3 both empty yields default and pollutes nothing`() {
        val (m, secure, legacy) = migrating()

        assertEquals("def", m.getString("absent", "def"))

        assertFalse("默认值不得写进新库", secure.contains("absent"))
        assertFalse(legacy.contains("absent"))
    }

    // ---- 锚 ④：异带默认值键（默认值≠零值）的行为 ----

    @Test
    fun `anchor4 non-default default key keeps key default when both empty`() {
        val (m, secure, _) = migrating()
        // tts_volume 型：默认 0.45f ≠ 0f——两库空时回键自定义默认，而非类型零值
        assertEquals(0.45f, m.getFloat("tts_volume", 0.45f))
        assertFalse("异带默认值键空读不得落新库", secure.contains("tts_volume"))
    }

    @Test
    fun `anchor4 legacy value migrates verbatim even when it equals old default`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putFloat("kws_energy_gate", 0.015f)

        assertEquals(0.015f, m.getFloat("kws_energy_gate", 0.015f))
        assertTrue(secure.contains("kws_energy_gate"))
        assertEquals(0.015f, secure.getFloat("kws_energy_gate", 0f))
    }

    // ---- 各类型读路径都能搬迁（写回必须保类型，防 ClassCastException）----

    @Test
    fun `typed legacy values migrate with their type`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putBoolean("overlay_enabled", true)
            .putInt("overlay_x", 42)
            .putLong("platform_token_obtained_at", 1234L)

        assertTrue(m.getBoolean("overlay_enabled", false))
        assertEquals(42, m.getInt("overlay_x", -1))
        assertEquals(1234L, m.getLong("platform_token_obtained_at", 0L))

        assertTrue(secure.getBoolean("overlay_enabled", false))
        assertEquals(42, secure.getInt("overlay_x", -1))
        assertEquals(1234L, secure.getLong("platform_token_obtained_at", 0L))
    }

    // ---- contains = 两库并集；写路径只进新库 ----

    @Test
    fun `contains is union of both stores`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putString("only_legacy", "v")
        secure.edit().putString("only_secure", "v")

        assertTrue(m.contains("only_legacy"))
        assertTrue(m.contains("only_secure"))
        assertFalse(m.contains("absent"))
    }

    @Test
    fun `writes land in new store only`() {
        val (m, secure, legacy) = migrating()

        m.edit().putInt("w", 7).putString("s", "x").apply()

        assertTrue(secure.contains("w"))
        assertEquals("x", secure.getString("s", null))
        assertFalse("写路径不得触旧库", legacy.contains("w"))
    }

    // ---- P2 评审修复：remove 同步删 legacy，防迁移键复活（401 重登 tokenCache.clear 链）----

    @Test
    fun `remove propagates to legacy so migrated key does not resurrect`() {
        val (m, secure, legacy) = migrating()
        legacy.edit().putString("platform_token", "old-token-value")
        assertEquals("迁移期读到旧值", "old-token-value", m.getString("platform_token", null))

        m.edit().remove("platform_token").commit()

        assertNull("remove 后读不得复活 legacy 旧值", m.getString("platform_token", null))
        assertFalse("新库键已删", secure.contains("platform_token"))
        assertFalse("legacy 同键必须被同步删除（显式删除意图优先于只搬不删）", legacy.contains("platform_token"))
    }

    @Test
    fun `remove on absent key is harmless across both stores`() {
        val (m, secure, legacy) = migrating()
        m.edit().remove("never-existed").commit()
        assertFalse(secure.contains("never-existed"))
        assertFalse(legacy.contains("never-existed"))
        assertNull(m.getString("never-existed", null))
    }

}

