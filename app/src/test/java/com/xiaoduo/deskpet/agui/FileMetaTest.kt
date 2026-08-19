package com.xiaoduo.deskpet.agui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileMeta 解析 + fileId 接入对话 content 前缀契约（平台 web bundle 静态取证 2026-08-03，
 * CONSTRUCTION S7 待查①已关闭）：content = "@file:{fileId}?{fileName}" 多条 "|" 连接 + "\n" + 用户文本。
 */
class FileMetaTest {

    // ---- fromJson（上传信封 data 段解析） ----

    @Test
    fun `fromJson parses full data`() {
        val meta = FileMeta.fromJson(
            JSONObject(
                """{"fileId":"f123","fileName":"报告.pdf","filePath":"/upload/f123.pdf",
                "fileSize":2048,"fileSuffix":"pdf"}""",
            ),
        )
        assertEquals("f123", meta.fileId)
        assertEquals("报告.pdf", meta.fileName)
        assertEquals("/upload/f123.pdf", meta.filePath)
        assertEquals(2048L, meta.fileSize)
        assertEquals("pdf", meta.fileSuffix)
        assertEquals(UploadStatus.DONE, meta.status)
        assertTrue(meta.localId.isNotBlank()) // 本地列表键必有（平台不返回）
    }

    @Test
    fun `fromJson tolerates missing fields with fallbacks`() {
        val meta = FileMeta.fromJson(JSONObject("""{"fileId":"f1"}"""))
        assertEquals("f1", meta.fileId)
        assertEquals("", meta.fileName)
        assertEquals("", meta.filePath)
        assertEquals(0L, meta.fileSize)
        assertEquals("", meta.fileSuffix)
        assertEquals(UploadStatus.DONE, meta.status)
    }

    // ---- buildContent（content 前缀注入） ----

    @Test
    fun `buildContent without files returns user text unchanged`() {
        assertEquals("你好", FileMeta.buildContent("你好", emptyList()))
    }

    @Test
    fun `buildContent single file prepends prefix and newline`() {
        assertEquals(
            "@file:f1?报告.pdf\n帮我总结一下",
            FileMeta.buildContent("帮我总结一下", listOf("f1" to "报告.pdf")),
        )
    }

    @Test
    fun `buildContent multiple files joined with pipe`() {
        assertEquals(
            "@file:f1?报告.pdf|@file:f2?数据.xlsx\n对比这两份文件",
            FileMeta.buildContent("对比这两份文件", listOf("f1" to "报告.pdf", "f2" to "数据.xlsx")),
        )
    }

    @Test
    fun `buildContent skips blank file ids`() {
        assertEquals(
            "@file:f2?b.txt\n文本",
            FileMeta.buildContent("文本", listOf("" to "a.txt", "f2" to "b.txt")),
        )
        // 全空白 id = 无有效文件，原样返回
        assertEquals("文本", FileMeta.buildContent("文本", listOf("" to "a.txt")))
    }

    @Test
    fun `buildContent sanitizes separators inside file name`() {
        // 文件名含 | ? 换行会撞前缀格式（平台按格式解析），替换防注入错位
        assertEquals(
            "@file:f1?a_b_c d.txt\n文本",
            FileMeta.buildContent("文本", listOf("f1" to "a|b?c\nd.txt")),
        )
    }
}
