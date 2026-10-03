package com.xiaoduo.deskpet.util

import com.xiaoduo.deskpet.net.TokenEntry
import com.xiaoduo.deskpet.net.TokenPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 新鲜度判定（时钟注入纯逻辑）：36 字符 UUID + 25 分钟缓存窗口。
 */
class TokenPolicyTest {

    private val uuid = "3f8a2c1e-9b7d-4e5f-a6c8-1d2e3f4a5b6c"
    private val t0 = 1_800_000_000_000L

    @Test
    fun `ttl is pinned to 25 minutes per knowledge pack contract`() {
        // 契约字面量锚点（知识包 §2.2：RuoYi 30min 过期，客户端缓存 25min）——
        // 边界用例若全部符号化引用 TTL_MS，改常量测试会自我跟随全绿，必须钉住字面量。
        assertEquals(25L * 60 * 1000, TokenPolicy.TTL_MS)
    }

    @Test
    fun `fresh token right after login`() {
        assertTrue(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0))
    }

    @Test
    fun `fresh at 24 minutes`() {
        assertTrue(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0 + 24L * 60 * 1000))
    }

    @Test
    fun `expired at 26 minutes`() {
        assertFalse(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0 + 26L * 60 * 1000))
    }

    @Test
    fun `expired exactly at ttl`() {
        assertFalse(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0 + TokenPolicy.TTL_MS))
    }

    @Test
    fun `expired long after ttl`() {
        assertFalse(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0 + 10 * TokenPolicy.TTL_MS))
    }

    @Test
    fun `clock rollback is not fresh`() {
        assertFalse(TokenPolicy.isFresh(TokenEntry(uuid, t0), t0 - 1))
    }

    @Test
    fun `null entry is not fresh`() {
        assertFalse(TokenPolicy.isFresh(null, t0))
    }

    @Test
    fun `malformed token is rejected`() {
        assertFalse(TokenPolicy.isValidToken("not-a-uuid"))
        assertFalse(TokenPolicy.isValidToken(uuid.substring(0, 35))) // 非 36 字符
        assertFalse(TokenPolicy.isFresh(TokenEntry("not-a-uuid", t0), t0))
    }

    @Test
    fun `valid 36-char uuid accepted`() {
        assertTrue(TokenPolicy.isValidToken(uuid))
    }

    /** SEC-3(ocr[144])：toString 脱敏——data class 默认 toString 会在日志/调试输出泄露原始 token。 */
    @Test
    fun `SEC-3 toString masks raw token`() {
        val entry = TokenEntry(uuid, t0)
        val s = entry.toString()
        assertTrue("toString 不得含原始 token: $s", !s.contains(uuid))
        assertTrue("脱敏标记可见: $s", s.contains("masked"))
        // data class 语义不受影响：copy/equals 照旧（不因 toString 覆写破坏）
        assertEquals(entry, entry.copy(obtainedAtMs = t0))
    }

    @Test
    fun `SEC-3 short or empty token masked without exception`() {
        assertTrue(!TokenEntry("", t0).toString().contains("masked token="))
        assertTrue(!TokenEntry("abc", t0).toString().contains("abc"))
    }
}
