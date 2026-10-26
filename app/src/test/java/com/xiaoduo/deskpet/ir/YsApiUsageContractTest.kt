package com.xiaoduo.deskpet.ir

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S70 ysapi 使用面契约测试（dependency-auditor 审计 P2 落地）：
 * 扫描 app/src/main/java 全部 .kt 源文本，断言危险 API 调用名零出现。
 *
 * ysapi.jar 只允许经 [YsGpioReader] 使用 GPIO/连接面（getInstance / bindAIDLService /
 * unBindAIDLService / setConnectClickInterface / setGpioDirection / getGpioValue）；
 * 下列危险面（root 任意命令 / 静默安装卸载 / 系统升级 / 开机自启 / 守护进程 / ADB 开关）
 * 一旦在本仓主源码出现即红——防后续迭代「顺手」扩用 ysapi 其余能力。
 *
 * 注释排除：按行 strip 行注释与块注释（含 KDoc）后判定——守卫注释里提到危险 API 名不算违例。
 * 字符串字面量不参与注释排除；禁令词均为驼峰/带括号 API 名，合法日志文案不含，误伤风险可忽略。
 */
class YsApiUsageContractTest {

    private val mainSrcDir = File("src/main/java")

    // S70 回归守卫：ysapi.jar 危险面封禁（execSuCmd 本地 root 任意命令，审计 P2；删此条目
    // → 变异样例 execSuCmd 真实调用不被抓 → 变异验证用例红）。其余同理：名单删任一条，
    // 对应变异调用样例即漏检。
    private val forbiddenApiNames = listOf(
        "execSuCmd",       // root shell，系统侧代执行任意命令
        "do_exec",         // GPIOUtils 的 shell 直执行
        "execFor7",        // root 执行变体
        "silentInstallApk", // 静默安装
        "upgradeSystem",   // 系统升级
        "unInstallApk",    // 静默卸载
        "selfStart",       // 开机自启
        "daemon(",         // 进程守护（带括号防误伤普通词）
        "setADBOpen",      // ADB 开关
    )

    /** strip 行注释（// 到行尾）与块注释（/*…*/，含 KDoc）后返回可判定文本。 */
    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inBlock = false
        var inString = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            when {
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
                inString -> {
                    sb.append(c)
                    when {
                        c == '\\' && i + 1 < source.length -> { sb.append(next); i++ }
                        c == '"' -> inString = false
                    }
                }
                c == '/' && next == '/' -> { while (i < source.length && source[i] != '\n') i++ }
                c == '/' && next == '*' -> { inBlock = true; i++ }
                c == '"' -> { inString = true; sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun `主源码零出现ysapi危险API调用名`() {
        assertTrue("主源码目录不存在：${mainSrcDir.absolutePath}", mainSrcDir.isDirectory)
        val violations = mutableListOf<String>()
        mainSrcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = stripComments(file.readText())
            forbiddenApiNames.forEach { name ->
                if (text.contains(name)) violations += "${file.path} 含违禁 API「$name」"
            }
        }
        assertTrue(
            "ysapi 危险面封禁被破坏（审计 P2）:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
