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
    /** S20-1：TOOL_CALL_START 触发 onToolCallEvent（MCP 静音开关的标记源） */
    @Test
    fun `tool call start fires callback`() {
        var fired = 0
        val c = AguiSseConsumer(onToolCallEvent = { fired++ })
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"mjj_move"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_END","toolCallId":"t1"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"t1","content":"ok"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"已打开"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertEquals(1, fired)
        val r = c.finish()
        assertTrue(r is AguiSseConsumer.RunResult.Success)
    }

    /** S41：TOOL_CALL_START 回调透传 toolCallName（密集架动画联动桥的工具名源；一轮可多次） */
    @Test
    fun `tool call start passes tool call name`() {
        val names = mutableListOf<String>()
        val c = AguiSseConsumer(onToolCallEvent = { names.add(it) })
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"mjj_move"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t2","toolCallName":"mjj_close"}""")
        assertEquals(listOf("mjj_move", "mjj_close"), names)
    }

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

    /** ===== S43：工具结果捕获（结果卡数据源）===== */

    /** S43-1：TOOL_CALL_RESULT 捕获——名字经 toolCallId 映射、content 逐字、args 从 rawEvent 提取 */
    @Test
    fun `tool call result captured with name content and args`() {
        val c = AguiSseConsumer()
        c.feedLine(
            """data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"c_archive_query",""" +
                """"rawEvent":{"event":"on_chat_model_stream","data":{"chunk":{"type":"AIMessageChunk",""" +
                """"tool_calls":[{"name":"c_archive_query","args":{"query":"帮我查询一下《测试档案0001》","tm":"测试档案0001"},"type":"tool_call"}]}}}}"""
        )
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"t1","content":"{\"success\":true,\"status\":\"no_result\"}"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"好"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertTrue(c.finish() is AguiSseConsumer.RunResult.Success)
        val oc = c.takeToolCallOutcome()
        assertEquals("c_archive_query", oc?.toolCallName)
        assertEquals("{\"success\":true,\"status\":\"no_result\"}", oc?.content)
        assertTrue(oc?.argsJson?.contains("测试档案0001") == true)
    }

    /** S43-2：一轮多结果取最后一条（内部澄清轮次被覆盖，结果卡只见最终结果） */
    @Test
    fun `multiple results last one wins`() {
        val c = AguiSseConsumer()
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"c_archive_query"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"t1","content":"{\"status\":\"needs_clarification\"}"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"t1","content":"{\"status\":\"no_result\"}"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"好"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertTrue(c.finish() is AguiSseConsumer.RunResult.Success)
        assertEquals("{\"status\":\"no_result\"}", c.takeToolCallOutcome()?.content)
    }

    /** S43-3：纯文本轮（无 RESULT）→ takeToolCallOutcome 为 null（结果卡静默降级） */
    @Test
    fun `no result yields null outcome`() {
        val c = AguiSseConsumer()
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"mjj_move"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"已打开"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertTrue(c.finish() is AguiSseConsumer.RunResult.Success)
        assertEquals(null, c.takeToolCallOutcome())
    }

    /** S43-4：缺 START 名字映射的 RESULT（id 对不上）→ 名字空串但 content 仍透出 */
    @Test
    fun `result without matching start keeps content with blank name`() {
        val c = AguiSseConsumer()
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"tX","content":"ok"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"好"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertTrue(c.finish() is AguiSseConsumer.RunResult.Success)
        val oc = c.takeToolCallOutcome()
        assertEquals("", oc?.toolCallName)
        assertEquals("ok", oc?.content)
    }

    /** S43-5：rawEvent 缺失/形态不符 → argsJson 为 null，主链路不受影响（防御性提取） */
    @Test
    fun `malformed raw event yields null args but keeps result`() {
        val c = AguiSseConsumer()
        c.feedLine("""data: {"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"archive_return","rawEvent":"weird"}""")
        c.feedLine("""data: {"type":"TOOL_CALL_RESULT","toolCallId":"t1","content":"{\"status\":\"success\"}"}""")
        c.feedLine("""data: {"type":"TEXT_MESSAGE_CONTENT","delta":"好"}""")
        c.feedLine("""data: {"type":"RUN_FINISHED","runId":"r"}""")
        assertTrue(c.finish() is AguiSseConsumer.RunResult.Success)
        val oc = c.takeToolCallOutcome()
        assertEquals("archive_return", oc?.toolCallName)
        assertEquals(null, oc?.argsJson)
        assertEquals("{\"status\":\"success\"}", oc?.content)
    }
}
