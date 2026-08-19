package com.xiaoduo.deskpet.agui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AguiSseConsumer 单测。契约依据：docs/m1-dev-kit/android-dev-brief.md §2.2 + 踩坑 #23-26。
 * 三件套铁律：主消费 TEXT_MESSAGE_CONTENT.delta / 无 RUN_FINISHED 显式 Error /
 * 0 字 EmptyRun / MESSAGES_SNAPSHOT 前缀比对补尾。
 */
class AguiSseConsumerTest {

    private fun consumer(collector: MutableList<String>): AguiSseConsumer =
        AguiSseConsumer(onDelta = { collector.add(it) })

    /** 1. 正常流：RUN_STARTED → RAW → STEP_STARTED → TEXT_MESSAGE_*×N → SNAPSHOT → RUN_FINISHED，全文拼接 */
    @Test
    fun normalFlowConcatenatesFullText() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\",\"runId\":\"r1\"}")
        c.feedLine("data: {\"type\":\"RAW\",\"content\":\"ignored\"}")
        c.feedLine("data: {\"type\":\"RAW\",\"content\":\"ignored2\"}")
        c.feedLine("data: {\"type\":\"STEP_STARTED\",\"stepName\":\"think\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\",\"role\":\"assistant\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"，世界\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"！\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"你好，世界！\"}]}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\",\"runId\":\"r1\"}")
        val result = c.finish()

        assertEquals(listOf("你好", "，世界", "！"), deltas)
        assertTrue(result is AguiSseConsumer.RunResult.Success)
        assertEquals("你好，世界！", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 2. 断流：finish() 时未见 RUN_FINISHED → 显式 Error，绝不伪装成功（踩坑 #26） */
    @Test
    fun streamEndWithoutRunFinishedYieldsError() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"半截话\"}")
        val result = c.finish()

        assertTrue(result is AguiSseConsumer.RunResult.Error)
        assertTrue((result as AguiSseConsumer.RunResult.Error).reason.isNotBlank())
    }

    /** 3. 空 run：有 RUN_FINISHED 但全程 0 字 → EmptyRun（调用方负责同 id 重试，本类不管） */
    @Test
    fun emptyRunWithRunFinishedYieldsEmptyRun() {
        val c = consumer(mutableListOf())
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"STEP_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertTrue(result is AguiSseConsumer.RunResult.EmptyRun)
    }

    /** 4. SNAPSHOT 补尾：content 为 string，比已产出长则补尾部增量（踩坑 #26 兜底） */
    @Test
    fun snapshotBackfillsTailWithStringContent() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好\"}")
        c.feedLine("data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"你好世界\"}]}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals(listOf("你好", "世界"), deltas)
        assertEquals("你好世界", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 5. SNAPSHOT 补尾：content 为 [{type:"text",text}] 分片形态 */
    @Test
    fun snapshotBackfillsTailWithChunkedContent() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好\"}")
        c.feedLine(
            "data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[" +
                "{\"id\":\"u1\",\"role\":\"user\",\"content\":\"问个事\"}," +
                "{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[" +
                "{\"type\":\"text\",\"text\":\"你好世\"},{\"type\":\"text\",\"text\":\"界呀\"}]}]}"
        )
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals(listOf("你好", "世界呀"), deltas)
        assertEquals("你好世界呀", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 6. SNAPSHOT 不长于已产出 → 不补（防重复） */
    @Test
    fun snapshotNotLongerThanProducedDoesNotBackfill() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好世界\"}")
        c.feedLine("data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"你好世界\"}]}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals(listOf("你好世界"), deltas)
        assertEquals("你好世界", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 6b. SNAPSHOT 比已产出长但不是已产出前缀 → 不补尾（保护 startsWith 前缀守卫，critic 变异实验曾假绿） */
    @Test
    fun snapshotLongerButNotPrefixDoesNotBackfill() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"你好\"}")
        c.feedLine("data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"再见世界呀\"}]}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals(listOf("你好"), deltas)
        assertEquals("你好", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 7. 多段 TEXT_MESSAGE_START/END（工具调用后多段）都要累加 */
    @Test
    fun multipleTextMessageSegmentsAccumulate() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"第一段。\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}")
        c.feedLine("data: {\"type\":\"STEP_STARTED\",\"stepName\":\"tool\"}")
        c.feedLine("data: {\"type\":\"RAW\",\"content\":\"tool call\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m2\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m2\",\"delta\":\"第二段。\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m2\",\"delta\":\"还有。\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m2\"}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals("第一段。第二段。还有。", (result as AguiSseConsumer.RunResult.Success).text)
    }

    /** 8. 心跳/空行/event 行/坏 JSON 容错：不崩、坏行记 skipped，正常事件照常 */
    @Test
    fun heartbeatAndMalformedLinesAreTolerated() {
        val deltas = mutableListOf<String>()
        val c = consumer(deltas)
        c.feedLine("")
        c.feedLine(": heartbeat")
        c.feedLine("event: message")
        c.feedLine("data: {这不是合法JSON")
        c.feedLine("data: {\"type\":\"RUN_STARTED\"}")
        c.feedLine("data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"好\"}")
        c.feedLine("data: {\"type\":\"RUN_FINISHED\"}")
        val result = c.finish()

        assertEquals(1, c.skippedLines)
        assertEquals(listOf("好"), deltas)
        assertEquals("好", (result as AguiSseConsumer.RunResult.Success).text)
    }
}
