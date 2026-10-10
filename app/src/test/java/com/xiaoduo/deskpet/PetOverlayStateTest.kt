package com.xiaoduo.deskpet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PetOverlayState 活性记账守卫（2026-10-10 spec §4.3 定案，Phase 0）。
 *
 * 防线目标：service 死于 onDestroy 之外（异常路径未清位）时，设置页 stale-true 误判
 * 「悬浮窗在跑」→ 拖 scale 滑条误重启。活性记账 = markStarted 起算 + markHeartbeat 续期，
 * isAlive = 已启动且心跳未过期；时钟由调用方注入（nowUptimeMs），纯 JVM 可测。
 *
 * 变异锚：删 markHeartbeat 记账（测 heartbeatExtendsAlive）、删 markHeartbeat 的
 * startedFlag 守卫（测 clearedThenHeartbeatDoesNotRevive）必红。
 */
class PetOverlayStateTest {

    @Before
    fun reset() {
        PetOverlayState.markCleared()
    }

    @Test
    fun initialState_notAlive_andHeartbeatCannotRevive() {
        assertFalse(PetOverlayState.isAlive(1_000L))
        PetOverlayState.markHeartbeat(2_000L)
        assertFalse(PetOverlayState.isAlive(2_001L))
    }

    @Test
    fun markStarted_alive_withinWindow() {
        PetOverlayState.markStarted(10_000L)
        assertTrue(PetOverlayState.running)
        assertTrue(PetOverlayState.isAlive(10_000L))
        assertTrue(PetOverlayState.isAlive(10_000L + PetOverlayState.HEARTBEAT_INTERVAL_MS))
    }

    @Test
    fun staleness_boundary_exactPasses_plusOneFails() {
        PetOverlayState.markStarted(10_000L)
        val boundary = 10_000L + PetOverlayState.STALE_AFTER_MS
        assertTrue(PetOverlayState.isAlive(boundary))
        assertFalse(PetOverlayState.isAlive(boundary + 1L))
    }

    @Test
    fun heartbeatExtendsAlive() {
        val t0 = 10_000L
        PetOverlayState.markStarted(t0)
        // 无心跳：第一窗口后过期
        val expired = t0 + PetOverlayState.STALE_AFTER_MS + 1L
        assertFalse(PetOverlayState.isAlive(expired))
        // 喂心跳后从心跳时刻重新起算
        val hb = t0 + PetOverlayState.HEARTBEAT_INTERVAL_MS
        PetOverlayState.markHeartbeat(hb)
        assertTrue(PetOverlayState.isAlive(hb + PetOverlayState.STALE_AFTER_MS))
        assertFalse(PetOverlayState.isAlive(hb + PetOverlayState.STALE_AFTER_MS + 1L))
    }

    @Test
    fun markCleared_notAlive() {
        PetOverlayState.markStarted(10_000L)
        PetOverlayState.markCleared()
        assertFalse(PetOverlayState.running)
        assertFalse(PetOverlayState.isAlive(10_001L))
        assertFalse(PetOverlayState.isAlive(10_000L + PetOverlayState.STALE_AFTER_MS + 1L))
    }

    /** 不变式（评审 P3：常量调参变异盲区）——stale 窗必须 ≥2 个心跳周期，
     *  否则单次心跳抖动即误判 stale、设置页重启静默失效。调参破坏此关系必红。 */
    @Test
    fun constants_staleWindow_coversTwoHeartbeats() {
        assertTrue(PetOverlayState.STALE_AFTER_MS >= 2 * PetOverlayState.HEARTBEAT_INTERVAL_MS)
    }

    /** 变异锚：markHeartbeat 若置 running（复活 bug 形态）必红。 */
    @Test
    fun clearedThenHeartbeatDoesNotRevive() {
        PetOverlayState.markStarted(10_000L)
        PetOverlayState.markCleared()
        PetOverlayState.markHeartbeat(20_000L)
        assertFalse(PetOverlayState.running)
        assertFalse(PetOverlayState.isAlive(20_001L))
    }
}
