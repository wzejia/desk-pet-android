package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁盘降级写守卫策略测试（P3-2 拍板 2026-10-10：静默降级 + 降级期禁写）。
 *
 * 变异锚：ChatDegradedPolicy.allowWrite 判定删 `!degraded`（恒放行）→
 * degradedRefusesAllWrites 必红；shouldMarkDegraded 判空删除 → markDegraded 两用例红。
 */
class ChatDegradedPolicyTest {

    @Test
    fun markDegraded_onNullCreateResult_only() {
        assertTrue("createSession 返 null 必须置降级位", ChatDegradedPolicy.shouldMarkDegraded(null))
        assertFalse(
            "正常会话不得置降级位",
            ChatDegradedPolicy.shouldMarkDegraded(ChatSession("id", "t", 1L, 1L)),
        )
    }

    @Test
    fun degradedRefusesAllWrites() {
        for (op in ChatDegradedPolicy.Op.entries) {
            assertFalse("降级期 $op 必须拒绝", ChatDegradedPolicy.allowWrite(op, degraded = true))
        }
    }

    @Test
    fun normalStateAllowsAllWrites() {
        for (op in ChatDegradedPolicy.Op.entries) {
            assertTrue("正常态 $op 必须放行", ChatDegradedPolicy.allowWrite(op, degraded = false))
        }
    }
}
