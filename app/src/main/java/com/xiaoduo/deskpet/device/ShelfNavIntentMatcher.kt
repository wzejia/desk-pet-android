package com.xiaoduo.deskpet.device

/**
 * 密集架页面导航意图匹配器（S46，纯 Kotlin 零 Android import，JVM 全测）。
 *
 * ASR/文字文本 → 页面标识（"borrow"/"return"）？保守策略：必须是**纯导航语态**
 * （导航动词/页面名词 + 借阅/归还目标词），且**不含执行对象词**（档案/文件/书名等）——
 * 「借阅《某档案》」是执行借阅（走平台 MCP → 结果卡），绝不能被本地导航劫持。
 *
 * 设计底线与 [DeviceIntentMatcher] 同源：意图不明确时返回 null 走正常 LLM 对话。
 *
 * 命中形态（实测话术域）：
 * - 「打开借阅页面」「跳转到归还界面」「进入借阅」「切换到归还」「去借阅」
 * - 「借阅页面」「归还界面」（页面名词兜底，无动词）
 * 不命中形态：
 * - 「借阅档案A」「归还这份文件」（对象词 → 执行借还）
 * - 「借阅」（裸目标词，无导航语态 → 语义含糊，不劫持）
 */
object ShelfNavIntentMatcher {

    private val BORROW_WORDS = listOf("借阅", "借书")
    private val RETURN_WORDS = listOf("归还", "还书")
    private val INVENTORY_WORDS = listOf("盘点")

    private val NAV_VERBS = listOf("打开", "跳转", "跳到", "转到", "进入", "切换", "切到", "去", "开一下")
    private val PAGE_NOUNS = listOf("页面", "界面", "页")

    /** 执行对象词：出现即视为执行借还（带档案对象），本地导航绝不拦截。 */
    private val OBJECT_WORDS = listOf("档案", "文件", "书名", "编号", "盒", "条码", "资料")

    /** 返回 "borrow"/"return"/"inventory"；null = 非纯导航意图（走 LLM 常规轮）。 */
    fun match(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (OBJECT_WORDS.any { t.contains(it) }) return null
        val page = pageOf(t) ?: return null
        val hasNavVerb = NAV_VERBS.any { t.contains(it) }
        val hasPageNoun = PAGE_NOUNS.any { t.contains(it) }
        // 保守：须有导航动词或页面名词二者其一；裸目标词不劫持
        return if (hasNavVerb || hasPageNoun) page else null
    }

    /** 同时出现多目标词时取先出现者（如「从借阅切到归还」→ borrow）。 */
    private fun pageOf(t: String): String? {
        data class Cand(val page: String, val idx: Int)
        val cands = buildList {
            BORROW_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("borrow", it)) } }
            RETURN_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("return", it)) } }
            INVENTORY_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("inventory", it)) } }
        }
        return cands.minByOrNull { it.idx }?.page
    }

    /** 页面标识 → 中文名（回执文案用）。 */
    fun pageName(page: String): String = when (page) {
        "borrow" -> "借阅"
        "return" -> "归还"
        "inventory" -> "盘点"
        else -> page
    }
}
