package com.xiaoduo.deskpet.chat

import kotlin.random.Random

/**
 * 聊天页空态建议问句池（纯逻辑，JVM 单测）。
 * 文案与平台助手定位一致（档案管理专业法律咨询）；若后续平台下发建议问句，
 * 只需替换 POOL 数据源，抽批规则不变。「换一换」经 exclude 优先避开上一批，
 * 池内备选不足时回落全池（保证总能抽满 count 条）。
 */
object SuggestedQuestions {

    val POOL: List<String> = listOf(
        "会计档案保管期限是几年？",
        "电子档案双套制如何操作合规？",
        "库房温湿度超过标准怎么办？",
        "文书档案的归档范围包括哪些？",
        "档案数字化加工有哪些质量要求？",
        "涉密档案借阅需要履行什么手续？",
        "档案移交进馆的时间要求是什么？",
        "照片档案如何整理与编目？",
    )

    /** 从池中随机抽 [count] 条不重复问句；[exclude] 中的问句在备选充足时不出现。 */
    fun pick(
        count: Int,
        exclude: Collection<String> = emptyList(),
        random: Random = Random.Default,
    ): List<String> {
        require(count >= 0) { "count must be >= 0" }
        val fresh = POOL.filter { it !in exclude }
        val source = if (fresh.size >= count) fresh else POOL
        return source.shuffled(random).take(count)
    }
}
