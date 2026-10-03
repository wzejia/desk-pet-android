package com.xiaoduo.deskpet.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S46 密集架页面导航意图匹配器单测：保守拦截口径（纯导航语态才命中，
 * 执行对象词出现即放行走 LLM）。
 */
class ShelfNavIntentMatcherTest {

    @Test
    fun `导航动词+目标词命中`() {
        assertEquals("borrow", ShelfNavIntentMatcher.match("打开借阅页面"))
        assertEquals("return", ShelfNavIntentMatcher.match("跳转到归还界面"))
        assertEquals("borrow", ShelfNavIntentMatcher.match("进入借阅"))
        assertEquals("return", ShelfNavIntentMatcher.match("切换到归还"))
        assertEquals("borrow", ShelfNavIntentMatcher.match("去借阅"))
        assertEquals("return", ShelfNavIntentMatcher.match("帮我跳转到还书页面"))
    }

    @Test
    fun `页面名词兜底命中`() {
        assertEquals("borrow", ShelfNavIntentMatcher.match("借阅页面"))
        assertEquals("return", ShelfNavIntentMatcher.match("归还界面"))
        assertEquals("borrow", ShelfNavIntentMatcher.match("借书页"))
    }

    @Test
    fun `盘点页面导航命中`() {
        assertEquals("inventory", ShelfNavIntentMatcher.match("打开盘点页面"))
        assertEquals("inventory", ShelfNavIntentMatcher.match("跳转到盘点界面"))
        assertEquals("inventory", ShelfNavIntentMatcher.match("进入盘点"))
        assertNull(ShelfNavIntentMatcher.match("盘点"))  // 裸目标词不劫持（可走 LLM→archives_inventory）
    }

    @Test
    fun `执行对象词出现不拦截`() {
        assertNull(ShelfNavIntentMatcher.match("借阅档案A"))
        assertNull(ShelfNavIntentMatcher.match("归还这份文件"))
        assertNull(ShelfNavIntentMatcher.match("打开借阅页面查编号001的档案"))
        assertNull(ShelfNavIntentMatcher.match("归还盒"))
    }

    @Test
    fun `裸目标词与无关语态不劫持`() {
        assertNull(ShelfNavIntentMatcher.match("借阅"))
        assertNull(ShelfNavIntentMatcher.match("归还"))
        assertNull(ShelfNavIntentMatcher.match("我要借书"))
        assertNull(ShelfNavIntentMatcher.match("打开整理台"))
        assertNull(ShelfNavIntentMatcher.match("查询档案"))
        assertNull(ShelfNavIntentMatcher.match(""))
    }

    @Test
    fun `S62 首页导航命中`() {
        assertEquals("home", ShelfNavIntentMatcher.match("打开首页"))
        assertEquals("home", ShelfNavIntentMatcher.match("去主页"))
        assertEquals("home", ShelfNavIntentMatcher.match("返回首页"))
        assertEquals("home", ShelfNavIntentMatcher.match("回到主界面"))
        assertEquals("home", ShelfNavIntentMatcher.match("切换到主页面"))
    }

    @Test
    fun `S62 首页裸词与话题形态不劫持（home 须导航动词）`() {
        // 「首页」自身含「页」——页面名词兜底对 home 关闭，防话题被劫持
        assertNull(ShelfNavIntentMatcher.match("首页"))
        assertNull(ShelfNavIntentMatcher.match("首页有什么"))
        assertNull(ShelfNavIntentMatcher.match("主界面显示的内容"))
        // 带执行对象词仍走执行（object 闸先于一切）
        assertNull(ShelfNavIntentMatcher.match("打开首页查档案"))
    }

    @Test
    fun `S62 首页回执文案`() {
        assertEquals("首页", ShelfNavIntentMatcher.pageName("home"))
        assertEquals("已回到首页", ShelfNavIntentMatcher.navSpeech("home"))
        assertEquals("已打开借阅页面", ShelfNavIntentMatcher.navSpeech("borrow")) // 既有语义不变
    }

    /** FUNC-4：源→目标句式（「从 A 切到 B」）候选跨多页 = 意图含糊，返回 null 交 LLM，
     *  绝不取首现词导航到用户要离开的页面（旧实现取先者锁定缺陷，KDoc 亦误写为预期）。 */
    @Test
    fun `FUNC-4 源到目标句式跨多页不劫持`() {
        assertNull(ShelfNavIntentMatcher.match("从借阅切到归还"))
        assertNull(ShelfNavIntentMatcher.match("从归还切到借阅页面"))
        assertNull(ShelfNavIntentMatcher.match("从首页切到盘点"))
        // 单一目标页仍正常命中（回归保护）
        assertEquals("return", ShelfNavIntentMatcher.match("切换到归还"))
        assertEquals("borrow", ShelfNavIntentMatcher.match("去借阅"))
    }

    // ---- FUNC-1(187)：问句/否定句不劫持为导航 ----

    @Test
    fun `FUNC-1 问句不劫持为导航`() {
        assertNull(ShelfNavIntentMatcher.match("借阅页面是什么"))
        assertNull(ShelfNavIntentMatcher.match("去借阅吗"))
        assertNull(ShelfNavIntentMatcher.match("归还页面长什么样"))
        assertNull(ShelfNavIntentMatcher.match("盘点页面在哪儿"))
    }

    @Test
    fun `FUNC-1 否定句不劫持为导航`() {
        assertNull(ShelfNavIntentMatcher.match("不要去借阅页面"))
        assertNull(ShelfNavIntentMatcher.match("别切到归还界面"))
    }
}
