package com.xiaoduo.deskpet.ui.screens

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * ROB-12：选附件大小防线（纯 JVM 逻辑，零 Android import）。
 * 选附件为任意 MIME（GetContent），此前 readBytes() 在 20MB 限制之前整读入内存，
 * 低端板选大文件内存尖峰可被系统杀。两道闸：游标 SIZE 预拒（[sizeExceeds]）+
 * 流式硬上限读取（[readBounded]，超限即停绝不整读）。
 */
internal object PickStreamLimit {

    /** 流式读取结局：[Done] 未超限；[Overflow] 超限（内容不返回，不落内存）。 */
    internal sealed interface Bounded {
        data class Done(val bytes: ByteArray) : Bounded
        object Overflow : Bounded
    }

    /** 有界流式读取：累计超 [maxBytes] 立即停读判 Overflow（内存上界 O(maxBytes)）。 */
    fun readBounded(input: InputStream, maxBytes: Int): Bounded {
        val out = ByteArrayOutputStream(minOf(maxBytes, 1 shl 20).coerceAtLeast(64))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return Bounded.Overflow
            out.write(chunk, 0, n)
        }
        return Bounded.Done(out.toByteArray())
    }

    /** 游标 SIZE 预判：provider 未给 size（null）不预拒，交 [readBounded] 硬上限兜底。 */
    fun sizeExceeds(size: Long?, maxBytes: Int): Boolean = size != null && size > maxBytes
}
