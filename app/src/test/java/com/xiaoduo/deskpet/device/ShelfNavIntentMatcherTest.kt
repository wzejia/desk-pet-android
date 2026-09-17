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
    fun `借还词同时出现取先者`() {
        assertEquals("borrow", ShelfNavIntentMatcher.match("从借阅切到归还"))
        assertEquals("return", ShelfNavIntentMatcher.match("从归还切到借阅页面"))
    }
}
