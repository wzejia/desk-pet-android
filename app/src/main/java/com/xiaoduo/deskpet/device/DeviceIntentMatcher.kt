package com.xiaoduo.deskpet.device

/**
 * 设备意图匹配器（S26-1，纯 Kotlin 零 Android import，JVM 全测）。
 *
 * ASR 文本 → [DeviceCommand]？保守策略：**必须显式包含设备名词**（整理台/净化柜），
 * 否则返回 null 走正常 LLM 对话——设备词不出现时绝不劫持聊天（设计底线）。
 *
 * 设备与接口约束来自实测文档（docs 见 CONSTRUCTION S26 节引言）：
 * - 整理台开机=选模式（手动0/自动1/感应2），裸「开机」默认手动；
 * - 净化柜开机=标准0/快速1/强力2，裸「开机」默认标准；
 * - 风速 1/2/3 档仅整理台手动模式有效（设备侧约束，匹配器不管，执行侧处理）；
 * - ASR 容错：口语前缀（帮我把/请/给我）、动词变体（打开/开一下/启动/关掉/关闭）。
 */
object DeviceIntentMatcher {

    enum class Device { ZLT, JHG } // 整理台 / 净化柜

    sealed interface Command {
        val device: Device
        /** mode：ZLT 0手动/1自动/2感应；JHG 0标准/1快速/2强力。 */
        data class PowerOn(override val device: Device, val mode: Int) : Command
        data class PowerOff(override val device: Device) : Command
        /** speed 1低/2中/3高（仅整理台手动模式有效；作用对象固定整理台）。 */
        data class FanSpeed(override val device: Device, val speed: Int) : Command
        data class QueryStatus(override val device: Device) : Command
    }

    private val ZLT_WORDS = listOf("整理台", "整理臺", "实验台", "試驗台", "實驗台")
    private val JHG_WORDS = listOf("净化柜", "淨化柜", "净化櫃", "淨化櫃")

    fun match(text: String): Command? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val device = deviceOf(t) ?: return null
        val purify = purifyPhrase(t) // 「标准/快速/强力净化」本身含开机语义（仅净化柜）
        return when {
            // FUNC-1：否定门先于一切动作分支——「不要打开整理台」绝不执行（null 走 LLM）
            isNegated(t) -> null
            // FUNC-1：疑问门先于动作分支——「开机了吗」→ 查询；「强力模式是什么」→ null 走 LLM
            isQuestion(t) -> if (isQuery(t)) Command.QueryStatus(device) else null
            isOff(t) -> Command.PowerOff(device)
            isOn(t) -> Command.PowerOn(device, modeOf(t, device))
            device == Device.JHG && purify != null -> Command.PowerOn(device, purify)
            // FUNC-1：风速分支解析失败回落查询判定，不再吞掉 isQuery 回退
            device == Device.ZLT && (t.contains("风速") || hasBareGear(t)) -> fanSpeed(t) ?: queryOrNull(t, device)
            isQuery(t) -> Command.QueryStatus(device)
            else -> null
        }
    }

    // FUNC-1：疑问/否定标记（动作分支前置门；词表刻意保守——误放行走 LLM，误拦截丢指令，
    // 取「宁可不动作」方向）
    private val NEGATION_WORDS = listOf("不要", "别", "不用")
    private val QUESTION_WORDS = listOf("吗", "呢", "什么", "怎么", "多少", "？", "?")

    private fun isNegated(t: String) = NEGATION_WORDS.any { t.contains(it) }
    private fun isQuestion(t: String) = QUESTION_WORDS.any { t.contains(it) }

    private fun queryOrNull(t: String, device: Device): Command? =
        if (isQuery(t)) Command.QueryStatus(device) else null

    private fun deviceOf(t: String): Device? = when {
        ZLT_WORDS.any { t.contains(it) } -> Device.ZLT
        JHG_WORDS.any { t.contains(it) } -> Device.JHG
        else -> null
    }

    private val ON_WORDS = listOf("开机", "打开", "开启", "启动", "开一下", "开开", "开始")
    private val OFF_WORDS = listOf("关机", "关闭", "关掉", "关上", "停下", "停机", "停止")

    private fun isOn(t: String) = ON_WORDS.any { t.contains(it) }
    private fun isOff(t: String) = OFF_WORDS.any { t.contains(it) }

    /** 模式词在开机语句中出现则选对应档；档位词本身也含开机语义（如「强力净化」=开机）。 */
    private fun modeOf(t: String, device: Device): Int = when (device) {
        Device.ZLT -> when {
            t.contains("自动") -> 1
            t.contains("感应") -> 2
            else -> 0
        }
        Device.JHG -> when {
            t.contains("强力") -> 2
            t.contains("快速") -> 1
            else -> 0
        }
    }

    private val GEAR_WORDS = listOf("一档" to 1, "1档" to 1, "二档" to 2, "2档" to 2, "三档" to 3, "3档" to 3)
    private val SPEED_WORDS = listOf("低速" to 1, "中速" to 2, "高速" to 3, "低档" to 1, "中档" to 2, "高档" to 3)

    private fun hasBareGear(t: String) = GEAR_WORDS.any { t.contains(it.first) } || SPEED_WORDS.any { t.contains(it.first) }

    private fun fanSpeed(t: String): Command? {
        (SPEED_WORDS + GEAR_WORDS).forEach { (word, n) ->
            if (t.contains(word)) return Command.FanSpeed(Device.ZLT, n)
        }
        // 「风速调到2」数字直给
        Regex("风速[^0-9]*([123])").find(t)?.let { return Command.FanSpeed(Device.ZLT, it.groupValues[1].toInt()) }
        return null
    }

    private val QUERY_WORDS = listOf("状态", "温度", "湿度", "pm2", "PM2", "空气", "怎么样", "开了吗", "开着吗", "运行", "了吗") // FUNC-1：「开机了吗/关了吗」类问句=查询

    /** 净化短语自带档位：档位词（标准/快速/强力）+「净化」结尾语义（不含柜名误触——
     *  「净化柜」一词不能当开机词，否则查询/闲聊句全误判开机）。 */
    private fun purifyPhrase(t: String): Int? = when {
        t.contains("强力") && t.contains("净化") -> 2
        t.contains("快速") && t.contains("净化") -> 1
        t.contains("标准") && t.contains("净化") -> 0
        else -> null
    }

    private fun isQuery(t: String) = QUERY_WORDS.any { t.contains(it) }
}
