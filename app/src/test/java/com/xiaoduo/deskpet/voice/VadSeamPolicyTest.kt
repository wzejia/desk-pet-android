package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S40 唤醒接缝水位策略单测：LISTENING 期块在「vad.start 后、回灌消费前」窗口的三分支。
 *
 * 不变量锚（票据点名）：
 * - ack 时代（捕获早于接缝水位）→ BUFFER：陈块突发不再直灌 VAD（09-12 Case B 数学不可能
 *   形态的治理点），且由 FIFO 保证回灌触发时 ack 时代已全部到齐（时序完整）；
 * - 接缝后首个块 → BACKFILL_THEN_LIVE：先回灌（时序前缀）再按活块处理；
 * - 未挂账（pending=false）→ LIVE 零开销直通（多轮续听/按住路径零行为变更）。
 */
class VadSeamPolicyTest {

    private val policy = VadSeamPolicy()

    @Test
    fun notPendingGoesLive() {
        assertEquals(
            VadSeamPolicy.Action.LIVE,
            policy.onBlock(pending = false, capturedAtNs = 1L, seamNs = 100L),
        )
    }

    @Test
    fun ackEraBlockBuffers() {
        assertEquals(
            VadSeamPolicy.Action.BUFFER,
            policy.onBlock(pending = true, capturedAtNs = 99L, seamNs = 100L),
        )
    }

    @Test
    fun exactlyAtSeamIsPostSeam() {
        assertEquals(
            "恰好等于水位 = vad.start 之后捕获（接缝即播完瞬间），按接缝后处理",
            VadSeamPolicy.Action.BACKFILL_THEN_LIVE,
            policy.onBlock(pending = true, capturedAtNs = 100L, seamNs = 100L),
        )
    }

    @Test
    fun postSeamBlockTriggersBackfill() {
        assertEquals(
            VadSeamPolicy.Action.BACKFILL_THEN_LIVE,
            policy.onBlock(pending = true, capturedAtNs = 101L, seamNs = 100L),
        )
    }
}
