package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.ui.screens.relativeTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽屉会话列表相对时间标签（ui.screens.relativeTimeLabel）：
 * <1 分钟「刚刚」→ N 分钟/小时/天前 → ≥7 天落 yyyy-MM-dd。
 */
class DrawerTimeLabelTest {

    private val now = 1_800_000_000_000L // 固定 now，用例不随真实时钟漂移

    @Test
    fun `不到 1 分钟显示刚刚`() {
        assertEquals("刚刚", relativeTimeLabel(now, now))
        assertEquals("刚刚", relativeTimeLabel(now, now - 59_999L))
    }

    @Test
    fun `未来时间不出现负数`() {
        assertEquals("刚刚", relativeTimeLabel(now, now + 3_600_000L))
    }

    @Test
    fun `分钟档`() {
        assertEquals("1 分钟前", relativeTimeLabel(now, now - 60_000L))
        assertEquals("59 分钟前", relativeTimeLabel(now, now - 59L * 60_000L))
    }

    @Test
    fun `小时档`() {
        assertEquals("1 小时前", relativeTimeLabel(now, now - 3_600_000L))
        assertEquals("23 小时前", relativeTimeLabel(now, now - 23L * 3_600_000L))
    }

    @Test
    fun `天档`() {
        assertEquals("1 天前", relativeTimeLabel(now, now - 86_400_000L))
        assertEquals("6 天前", relativeTimeLabel(now, now - 6L * 86_400_000L))
    }

    @Test
    fun `超过 7 天落日期`() {
        val ts = now - 7L * 86_400_000L
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
        assertEquals(expected, relativeTimeLabel(now, ts))
    }
}
