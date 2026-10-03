package com.xiaoduo.deskpet.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DeviceIntentMatcher 单测（S26-1）。
 * 设计底线锚：不含设备名词的任何话都返回 null（不劫持 LLM 对话）。
 */
class DeviceIntentMatcherTest {

    private fun m(t: String) = DeviceIntentMatcher.match(t)

    // ---- 整理台 ----

    @Test
    fun `zlt plain power on defaults manual mode`() {
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.ZLT, 0),
            m("打开整理台"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.ZLT, 0),
            m("整理台开机"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.ZLT, 0),
            m("帮我把整理台打开"),
        )
    }

    @Test
    fun `zlt mode words select mode`() {
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.ZLT, 1),
            m("整理台自动模式开机"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.ZLT, 2),
            m("打开整理台感应模式"),
        )
    }

    @Test
    fun `zlt power off variants`() {
        assertEquals(
            DeviceIntentMatcher.Command.PowerOff(DeviceIntentMatcher.Device.ZLT),
            m("整理台关机"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOff(DeviceIntentMatcher.Device.ZLT),
            m("把整理台关掉"),
        )
    }

    @Test
    fun `zlt fan speed gears`() {
        assertEquals(DeviceIntentMatcher.Command.FanSpeed(DeviceIntentMatcher.Device.ZLT, 1), m("整理台风速低速"))
        assertEquals(DeviceIntentMatcher.Command.FanSpeed(DeviceIntentMatcher.Device.ZLT, 2), m("把整理台风速调到二档"))
        assertEquals(DeviceIntentMatcher.Command.FanSpeed(DeviceIntentMatcher.Device.ZLT, 3), m("整理台调到三档"))
        assertEquals(DeviceIntentMatcher.Command.FanSpeed(DeviceIntentMatcher.Device.ZLT, 2), m("整理台风速调到2"))
    }

    // ---- 净化柜 ----

    @Test
    fun `jhg power on modes`() {
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.JHG, 0),
            m("打开净化柜"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.JHG, 2),
            m("净化柜强力净化"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.PowerOn(DeviceIntentMatcher.Device.JHG, 1),
            m("净化柜快速模式开机"),
        )
    }

    @Test
    fun `jhg power off`() {
        assertEquals(
            DeviceIntentMatcher.Command.PowerOff(DeviceIntentMatcher.Device.JHG),
            m("关闭净化柜"),
        )
    }

    // ---- 查询 ----

    @Test
    fun `query status by device`() {
        assertEquals(
            DeviceIntentMatcher.Command.QueryStatus(DeviceIntentMatcher.Device.ZLT),
            m("整理台什么状态"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.QueryStatus(DeviceIntentMatcher.Device.JHG),
            m("净化柜温度多少"),
        )
    }

    // ---- 底线：不劫持对话 ----

    @Test
    fun `no device noun means null`() {
        assertNull(m("今天天气怎么样"))
        assertNull(m("打开1号密集架")) // 密集架是平台 MCP 的事，不是本设备
        assertNull(m("把灯关了"))
        assertNull(m("风速调高一点")) // 有档位词但无设备名词——不猜
    }

    @Test
    fun `device noun without action intent is null`() {
        assertNull(m("整理台是什么东西"))
        assertNull(m("净化柜多少钱"))
    }

    @Test
    fun `empty text is null`() {
        assertNull(m(""))
        assertNull(m("   "))
    }

    // ---- FUNC-1：疑问句/否定句门（动作分支前拦截） ----

    /** 疑问句带查询语义 → 路由 QueryStatus，绝不发动作（「开机了吗」曾误发 PowerOn）。 */
    @Test
    fun `FUNC-1 question with query semantics routes to QueryStatus not action`() {
        assertEquals(
            DeviceIntentMatcher.Command.QueryStatus(DeviceIntentMatcher.Device.ZLT),
            m("整理台开机了吗"),
        )
        assertEquals(
            DeviceIntentMatcher.Command.QueryStatus(DeviceIntentMatcher.Device.ZLT),
            m("整理台风速怎么样"),
        )
    }

    /** 疑问句无查询语义 → null 交 LLM（「强力模式是什么」曾误切强力档）。 */
    @Test
    fun `FUNC-1 question without query semantics is null not action`() {
        assertNull(m("净化柜强力模式是什么"))
        assertNull(m("整理台风速是多少"))
        assertNull(m("整理台呢"))
    }

    /** 否定句 → null 交 LLM（「不要打开整理台」曾执行 PowerOn）。 */
    @Test
    fun `FUNC-1 negation is null not action`() {
        assertNull(m("不要打开整理台"))
        assertNull(m("别开净化柜"))
        assertNull(m("不用关整理台"))
    }

    /** fan-speed 分支解析失败不得吞掉查询回退（「整理台风速状态」曾返回 null）。 */
    @Test
    fun `FUNC-1 fan speed parse miss falls back to query`() {
        assertEquals(
            DeviceIntentMatcher.Command.QueryStatus(DeviceIntentMatcher.Device.ZLT),
            m("整理台风速状态"),
        )
    }
}
