package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.api.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatTurnLogic 单测：消息状态迁移 / 流式原文缓冲追加（半截 think 标签跨 delta 无损）/
 * 重发 wire messageId 决策（踩坑 #25）/ wire id 生成。
 */
class ChatTurnLogicTest {

    private fun newLogic() = ChatTurnLogic(clock = { 1_750_000_000_000L })

    private fun user(id: String, text: String, wireId: String? = null) =
        ChatMessage(id, ChatMessage.Role.USER, text, ChatMessage.Status.DONE, 1L, wireId)

    private fun assistant(id: String, text: String, status: ChatMessage.Status) =
        ChatMessage(id, ChatMessage.Role.ASSISTANT, text, status, 2L)

    // ---------------- wire id 生成 ----------------

    @Test
    fun `wire id format and uniqueness within same millisecond`() {
        val logic = newLogic()
        val a = logic.nextWireId()
        val b = logic.nextWireId()
        assertTrue(a.startsWith("u_1750000000000_"))
        assertTrue(b.startsWith("u_1750000000000_"))
        assertNotEquals(a, b)
    }

    // ---------------- 状态迁移 ----------------

    @Test
    fun `appendTurn appends user then assistant placeholder`() {
        val logic = newLogic()
        val u = user("u1", "你好", "u_1_1")
        val a = assistant("a1", "", ChatMessage.Status.SENDING)
        assertEquals(listOf(u, a), logic.appendTurn(emptyList(), u, a))
    }

    @Test
    fun `applyDelta accumulates and marks streaming`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "你好")
        msgs = logic.applyDelta(msgs, "a1", "，世界")
        assertEquals("你好，世界", msgs[0].text)
        assertEquals(ChatMessage.Status.STREAMING, msgs[0].status)
    }

    @Test
    fun `applyDelta with unknown id leaves list unchanged`() {
        val logic = newLogic()
        val msgs = listOf(assistant("a1", "旧", ChatMessage.Status.DONE))
        assertEquals(msgs, logic.applyDelta(msgs, "ghost", "新"))
    }

    // ---------------- DATA-6：迟到 delta 不得复活终态轮次 ----------------

    @Test
    fun `DATA-6 late delta after markDone does not resurrect turn`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "流出半截")
        msgs = logic.markDone(msgs, "a1", "定稿全文")
        val after = logic.applyDelta(msgs, "a1", "迟到增量")
        assertEquals("定稿全文", after[0].text)
        assertEquals(ChatMessage.Status.DONE, after[0].status)
    }

    @Test
    fun `DATA-6 late delta after markError stays error`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "半截")
        msgs = logic.markError(msgs, "a1")
        val after = logic.applyDelta(msgs, "a1", "迟到增量")
        assertEquals("半截", after[0].text)
        assertEquals(ChatMessage.Status.ERROR, after[0].status)
    }

    @Test
    fun `DATA-6 late delta after cancel keeps partial done text`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "半截")
        msgs = logic.markCancelledDone(msgs, "a1")
        val after = logic.applyDelta(msgs, "a1", "迟到增量")
        assertEquals("半截", after[0].text)
        assertEquals(ChatMessage.Status.DONE, after[0].status)
    }

    /** 回归保护：重发重置为 SENDING 后新一轮 delta 必须照常应用。 */
    @Test
    fun `DATA-6 delta still applies after resend reset (sending state)`() {
        val logic = newLogic()
        val msgs = listOf(
            user("u1", "问", "u_1_1"),
            assistant("a1", "", ChatMessage.Status.ERROR),
        )
        val plan = logic.prepareResend(msgs, "a1")!!
        val rerun = logic.applyDelta(plan.messages, "a1", "新回复")
        assertEquals("新回复", rerun[1].text)
        assertEquals(ChatMessage.Status.STREAMING, rerun[1].status)
    }

    @Test
    fun `markDone cleans final text and sets done`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "<think>想</think>定稿")
        msgs = logic.markDone(msgs, "a1", "<think>想</think>定稿")
        assertEquals("定稿", msgs[0].text)
        assertEquals(ChatMessage.Status.DONE, msgs[0].status)
    }

    @Test
    fun `markError keeps streamed text and sets error`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "半截回复")
        msgs = logic.markError(msgs, "a1")
        assertEquals("半截回复", msgs[0].text)
        assertEquals(ChatMessage.Status.ERROR, msgs[0].status)
    }

    // ---------------- 流式原文缓冲（裸 < 闪现根因修复） ----------------

    @Test
    fun `char by char think tag never exposes bare bracket and joins losslessly`() {
        // delta 逐字符到达：旧实现把清洗结果当累加基，裸 < 闪现且 "hink>秘密" 漏成字面文本；
        // 原文缓冲累加后任何时刻可见文本都不含 < / 标签碎片，收尾完整
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        val full = "<think>秘密</think>正文"
        for (ch in full) {
            msgs = logic.applyDelta(msgs, "a1", ch.toString())
            assertTrue("leaked: <${msgs[0].text}>", !msgs[0].text.contains("<"))
            assertTrue("leaked: <${msgs[0].text}>", !msgs[0].text.contains("think"))
        }
        assertEquals("正文", msgs[0].text)
    }

    @Test
    fun `char by char stray close tag joins losslessly`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        val full = "前文</think>正文"
        for (ch in full) {
            msgs = logic.applyDelta(msgs, "a1", ch.toString())
            assertTrue("leaked: <${msgs[0].text}>", !msgs[0].text.contains("<"))
        }
        assertEquals("前文正文", msgs[0].text)
    }

    @Test
    fun `resend resets raw buffer so new round starts clean`() {
        val logic = newLogic()
        var msgs = listOf(
            user("u1", "问", "u_1_1"),
            assistant("a1", "", ChatMessage.Status.SENDING),
        )
        msgs = logic.applyDelta(msgs, "a1", "旧流出<thi")
        msgs = logic.markError(msgs, "a1")
        val plan = logic.prepareResend(msgs, "a1")!!
        // 新一轮 delta 从空文本重跑，不带上一轮的半截标签残留
        val rerun = logic.applyDelta(plan.messages, "a1", "新回复")
        assertEquals("新回复", rerun[1].text)
    }

    // ---------------- barge-in 打断收尾（#7 阶段1） ----------------

    @Test
    fun `markCancelledDone keeps partial text and marks done`() {
        val logic = newLogic()
        var msgs = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        msgs = logic.applyDelta(msgs, "a1", "半截回复")
        msgs = logic.markCancelledDone(msgs, "a1")
        assertEquals("半截回复", msgs[0].text)
        assertEquals(ChatMessage.Status.DONE, msgs[0].status)
    }

    @Test
    fun `markCancelledDone is not resendable (no error branch semantics)`() {
        val logic = newLogic()
        var msgs = listOf(
            user("u1", "问", "u_1_1"),
            assistant("a1", "", ChatMessage.Status.SENDING),
        )
        msgs = logic.applyDelta(msgs, "a1", "半截")
        msgs = logic.markCancelledDone(msgs, "a1")
        // DONE 非 ERROR：prepareResend 拒绝——被打断轮不出「重发」入口
        // （已流出文本是有效内容，重发会重复投递同一 user 文本拿第二份回复）
        assertNull(logic.prepareResend(msgs, "a1"))
    }

    @Test
    fun `markCancelledDone with unknown id leaves list unchanged`() {
        val logic = newLogic()
        val msgs = listOf(assistant("a1", "旧", ChatMessage.Status.DONE))
        assertEquals(msgs, logic.markCancelledDone(msgs, "ghost"))
    }

    // ---------------- DATA-5：rawBuffers 并发追加不丢增量 ----------------

    /** 两线程在同一 base 上并发 applyDelta（流式 IO 线程 × 主线程终态处理并发形态）：
     *  全部增量都必须进入共享缓冲——旧 HashMap+getOrPut 竞态会产生双缓冲/丢字符。 */
    @Test
    fun `DATA-5 concurrent applyDelta on same base loses no delta`() {
        val logic = newLogic()
        val base = listOf(assistant("a1", "", ChatMessage.Status.SENDING))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val done = java.util.concurrent.CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(ArrayList<List<ChatMessage>>())
        repeat(2) {
            pool.submit {
                try {
                    repeat(200) { results.add(logic.applyDelta(base, "a1", "x")) }
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        pool.shutdown()
        var maxLen = 0
        for (r in results) maxLen = maxOf(maxLen, r[0].text.length)
        assertEquals("并发增量不得丢失: $maxLen/400", 400, maxLen)
    }

    // ---------------- 重发决策（踩坑 #25） ----------------

    @Test
    fun `prepareResend reuses original wire id and resets assistant`() {
        val logic = newLogic()
        val msgs = listOf(
            user("u1", "第一条", "u_100_1"),
            assistant("a1", "", ChatMessage.Status.ERROR),
        )
        val plan = logic.prepareResend(msgs, "a1")!!
        assertEquals("a1", plan.assistantId)
        assertEquals("第一条", plan.userText)
        assertEquals("u_100_1", plan.wireId)
        assertEquals("", plan.messages[1].text)
        assertEquals(ChatMessage.Status.SENDING, plan.messages[1].status)
        assertEquals("u_100_1", plan.messages[0].wireId)
    }

    @Test
    fun `prepareResend picks nearest preceding user message`() {
        val logic = newLogic()
        val msgs = listOf(
            user("u1", "旧问题", "u_100_1"),
            assistant("a1", "旧回答", ChatMessage.Status.DONE),
            user("u2", "新问题", "u_100_2"),
            assistant("a2", "", ChatMessage.Status.ERROR),
        )
        val plan = logic.prepareResend(msgs, "a2")!!
        assertEquals("新问题", plan.userText)
        assertEquals("u_100_2", plan.wireId)
    }

    @Test
    fun `prepareResend mints wire id for legacy history and stamps it back`() {
        val logic = newLogic()
        val msgs = listOf(
            user("u1", "升级前旧历史"), // 无 wireId
            assistant("a1", "", ChatMessage.Status.ERROR),
        )
        val plan = logic.prepareResend(msgs, "a1")!!
        assertTrue(plan.wireId.startsWith("u_1750000000000_"))
        assertEquals(plan.wireId, plan.messages[0].wireId) // 回写：后续重发仍同 id
        val secondRound = plan.messages.map {
            if (it.id == "a1") it.copy(status = ChatMessage.Status.ERROR) else it
        }
        val plan2 = logic.prepareResend(secondRound, "a1")!!
        assertEquals(plan.wireId, plan2.wireId)
    }

    @Test
    fun `prepareResend rejects non error or unknown or userless`() {
        val logic = newLogic()
        val done = listOf(
            user("u1", "问", "u_1_1"),
            assistant("a1", "答", ChatMessage.Status.DONE),
        )
        assertNull(logic.prepareResend(done, "a1")) // 非 ERROR 不重发
        assertNull(logic.prepareResend(done, "ghost")) // 目标不存在
        val userless = listOf(assistant("a1", "", ChatMessage.Status.ERROR))
        assertNull(logic.prepareResend(userless, "a1")) // 前面没有 user 消息
    }
}
