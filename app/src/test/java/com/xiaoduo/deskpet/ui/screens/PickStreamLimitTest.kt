package com.xiaoduo.deskpet.ui.screens

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROB-12：选附件大小防线（纯 JVM 逻辑件）。
 * 此前 readBytes() 在 20MB 限制之前整读入内存（任意 MIME picker，数百 MB 文件 → 内存尖峰）；
 * 现为两道闸：游标 SIZE 预拒 + 流式硬上限读取（超限即停，内存上界 O(max)）。
 */
class PickStreamLimitTest {

    @Test
    fun `small input read fully`() {
        val r = PickStreamLimit.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 8)
        assertTrue(r is PickStreamLimit.Bounded.Done)
        assertArrayEquals(byteArrayOf(1, 2, 3), (r as PickStreamLimit.Bounded.Done).bytes)
    }

    @Test
    fun `exactly max accepted not overflow`() {
        val data = ByteArray(8) { it.toByte() }
        val r = PickStreamLimit.readBounded(ByteArrayInputStream(data), 8)
        assertTrue(r is PickStreamLimit.Bounded.Done)
        assertArrayEquals(data, (r as PickStreamLimit.Bounded.Done).bytes)
    }

    @Test
    fun `max plus one overflows and bytes not returned`() {
        val data = ByteArray(9) { it.toByte() }
        val r = PickStreamLimit.readBounded(ByteArrayInputStream(data), 8)
        assertEquals(PickStreamLimit.Bounded.Overflow, r)
    }

    @Test
    fun `empty input yields empty done`() {
        val r = PickStreamLimit.readBounded(ByteArrayInputStream(ByteArray(0)), 8)
        assertTrue(r is PickStreamLimit.Bounded.Done)
        assertArrayEquals(ByteArray(0), (r as PickStreamLimit.Bounded.Done).bytes)
    }

    @Test
    fun `null size never pre-rejected falls through to stream cap`() {
        assertFalse(PickStreamLimit.sizeExceeds(null, 8))
    }

    @Test
    fun `size boundary equals max accepted beyond rejected`() {
        assertFalse(PickStreamLimit.sizeExceeds(8L, 8))
        assertTrue(PickStreamLimit.sizeExceeds(9L, 8))
    }
}
