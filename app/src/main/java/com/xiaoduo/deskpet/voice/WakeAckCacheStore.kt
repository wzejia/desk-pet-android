package com.xiaoduo.deskpet.voice

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 唤醒应答磁盘缓存（S9-2，java.io 实现，零 android.* import）。
 *
 * 生产 root = filesDir/wake_ack/v2（测试传临时目录）。
 * - write：validate 门槛 → tmp 写 → rename 原子替换（失败清 tmp 返 false）
 * - read：读全字节 + validate，坏文件删除并返 null（下次预取可补）
 * - exists：纯查询
 * - 构造即一次性清理兄弟旧版本目录（如 v1），防版本切换后陈旧缓存永驻
 *
 * fileName 一律来自 [WakeAckSelector.fileName]（已 sanitize），本类不重复校验。
 */
class WakeAckCacheStore(private val root: File) {

    /** 兄弟目录清理白名单：仅认 `v数字` 版本目录（防误传 filesDir 时清空应用私有目录兄弟、防两实例互删非版本目录）。 */
    private val legacyVersionDir = Regex("""v\d+""")

    init {
        // S9-2 回归守卫：构造即清兄弟旧版本目录（删此 → 旧版本目录清理用例 → 红）；
        // deleteRecursively 失败不致命（下次构造再试）
        cleanLegacySiblingDirs()
    }

    fun exists(fileName: String): Boolean = file(fileName).isFile

    /** 校验通过 + 原子落盘才返 true；非法字节/IO 失败返 false 且不留残缺文件。 */
    fun write(fileName: String, pcm: ByteArray): Boolean {
        if (!WakeAckAudio.validate(pcm)) return false
        root.mkdirs()
        val target = file(fileName)
        val tmp = File(root, "$fileName.tmp")
        return try {
            tmp.outputStream().use { it.write(pcm) }
            moveAtomically(tmp, target)
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    /** 读缓存；文件缺失/损坏/不可读 → null，坏文件顺手删除（防每次唤醒重复读垃圾）。 */
    fun read(fileName: String): ByteArray? {
        val f = file(fileName)
        if (!f.isFile) return null
        val bytes = try {
            f.readBytes()
        } catch (e: IOException) {
            null
        }
        if (bytes == null) return null
        if (!WakeAckAudio.validate(bytes)) {
            // S9-2 回归守卫：坏缓存删除并返 null（删 f.delete() → 坏缓存用例的 exists 断言 → 红）
            f.delete()
            return null
        }
        return bytes
    }

    private fun file(fileName: String): File = File(root, fileName)

    private fun moveAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 删 root 的兄弟**版本**目录（旧版本 v1 等），幂等（构造只走一次，本类单实例使用）。 */
    private fun cleanLegacySiblingDirs() {
        val parent = root.parentFile ?: return
        root.mkdirs()
        // S9-2 回归守卫：白名单只删 v\d+（删白名单条件 → 非版本目录误删用例 → 红）
        parent.listFiles { f -> f.isDirectory && f.name != root.name && legacyVersionDir.matches(f.name) }
            ?.forEach { legacy -> legacy.deleteRecursively() }
    }
}
