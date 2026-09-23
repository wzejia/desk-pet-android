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
    // S62：首页/主界面目标词。注意「首页」含页面名词「页」——裸词/话题形态由下方
    //  home 专属规则拦截（须导航动词），防「首页有什么」类聊天被劫持。
    private val HOME_WORDS = listOf("首页", "主页", "主界面", "主页面")

    private val NAV_VERBS = listOf("打开", "跳转", "跳到", "转到", "进入", "切换", "切到", "去", "开一下", "返回", "回到")
    private val PAGE_NOUNS = listOf("页面", "界面", "页")

    /** 执行对象词：出现即视为执行借还（带档案对象），本地导航绝不拦截。 */
    private val OBJECT_WORDS = listOf("档案", "文件", "书名", "编号", "盒", "条码", "资料")

    /** 返回 "borrow"/"return"/"inventory"/"home"；null = 非纯导航意图（走 LLM 常规轮）。 */
    fun match(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (OBJECT_WORDS.any { t.contains(it) }) return null
        val page = pageOf(t) ?: return null
        val hasNavVerb = NAV_VERBS.any { t.contains(it) }
        val hasPageNoun = PAGE_NOUNS.any { t.contains(it) }
        // 保守：须有导航动词或页面名词二者其一；裸目标词不劫持
        // S62：home 专属——必须带导航动词（「首页」自身含「页」，页面名词兜底会令
        //  「首页有什么」「首页显示的内容」类话题被劫持；「打开/返回/回到首页」才导航）。
        if (page == "home") return if (hasNavVerb) page else null
        return if (hasNavVerb || hasPageNoun) page else null
    }

    /** 同时出现多目标词时取先出现者（如「从借阅切到归还」→ borrow）。 */
    private fun pageOf(t: String): String? {
        data class Cand(val page: String, val idx: Int)
        val cands = buildList {
            BORROW_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("borrow", it)) } }
            RETURN_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("return", it)) } }
            INVENTORY_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("inventory", it)) } }
            HOME_WORDS.forEach { w -> t.indexOf(w).takeIf { i -> i >= 0 }?.let { add(Cand("home", it)) } }
        }
        return cands.minByOrNull { it.idx }?.page
    }

    /** 页面标识 → 中文名（回执文案用）。 */
    fun pageName(page: String): String = when (page) {
        "borrow" -> "借阅"
        "return" -> "归还"
        "inventory" -> "盘点"
        "home" -> "首页"
        else -> page
    }

    /** S62：导航成功回执文案（语音/文字两入口共用，home 用「回到」更自然）。 */
    fun navSpeech(page: String): String =
        if (page == "home") "已回到首页" else "已打开${pageName(page)}页面"
}
