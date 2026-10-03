package com.xiaoduo.deskpet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ROB-9：上传链路结果 → 用户提示的映射逻辑件。
 * OK 无 toast；读失败 / 超限 / 未捕获异常收敛分别有对应提示（异常不逸出协程，提示不缺位）。
 */
class ChatScreenUploadOutcomeTest {

    @Test
    fun `ok shows no toast`() {
        assertNull(uploadToastFor(UploadOutcome.OK))
    }

    @Test
    fun `read failed keeps original copy`() {
        assertEquals("读取文件失败", uploadToastFor(UploadOutcome.READ_FAILED))
    }

    @Test
    fun `too large keeps original copy`() {
        assertEquals("文件超过 20MB，未上传", uploadToastFor(UploadOutcome.TOO_LARGE))
    }

    @Test
    fun `crashed upload gets dedicated toast`() {
        assertEquals("上传失败，请重试", uploadToastFor(UploadOutcome.FAILED))
    }
}
