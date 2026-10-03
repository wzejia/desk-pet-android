package com.xiaoduo.deskpet.device

import com.xiaoduo.deskpet.device.DeviceIntentMatcher.Command
import com.xiaoduo.deskpet.device.DeviceIntentMatcher.Device
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DeviceClient 单测（S26-2）：MockWebServer 模拟设备，断言命令路径 + 回读验证语义。
 * sleeper 注入零等待（真实延时 2.5s 不进单测）。
 */
class DeviceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DeviceClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DeviceClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            sleeper = { /* 零等待 */ },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun zltStatus(runFlag: Int, funSpeed: Int = 0, temp: Int = 30, humd: Int = 48) =
        MockResponse().setBody("""{"RunFlag":$runFlag,"FunSpeed":$funSpeed,"temp":$temp,"humd":$humd,"pm25":0}""")

    private fun jhgStatus(runFlag: Int) =
        MockResponse().setBody("""{"RunFlag":$runFlag,"temp":29,"humd":53,"pm25":0}""")

    @Test
    fun `zlt power on sends mode endpoint and verifies RunFlag`() {
        server.enqueue(MockResponse().setBody("true")) // /btRunMode0
        server.enqueue(zltStatus(1))                    // /getZLTData
        val r = client.execute(Command.PowerOn(Device.ZLT, 0))
        assertTrue(r is DeviceClient.SkillResult.Ok)
        assertTrue((r as DeviceClient.SkillResult.Ok).speech.contains("整理台已打开"))
        assertTrue(r.speech.contains("温度30度"))
        val paths = drain()
        assertTrue("应打模式接口: $paths", paths.any { it.startsWith("/btRunMode0") })
        assertTrue(paths.any { it.startsWith("/getZLTData") })
    }

    @Test
    fun `jhg power on with mode param`() {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(jhgStatus(1))
        val r = client.execute(Command.PowerOn(Device.JHG, 2))
        assertTrue(r is DeviceClient.SkillResult.Ok)
        val paths = drain()
        assertTrue("净化柜开机应带 mode: $paths", paths.any { it.startsWith("/level1BtRunMode4?mode=2") })
    }

    @Test
    fun `power on but RunFlag stays 0 fails with hint`() {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(zltStatus(0)) // 设备没收下命令
        val r = client.execute(Command.PowerOn(Device.ZLT, 0))
        assertTrue(r is DeviceClient.SkillResult.Fail)
        assertTrue((r as DeviceClient.SkillResult.Fail).speech.contains("未确认"))
    }

    @Test
    fun `power off verifies RunFlag zero`() {
        server.enqueue(MockResponse().setBody("true")) // /btZlg?runFlag=0
        server.enqueue(jhgStatus(0))
        val r = client.execute(Command.PowerOff(Device.JHG))
        assertTrue(r is DeviceClient.SkillResult.Ok)
        val paths = drain()
        assertTrue(paths.any { it.startsWith("/btZlg?runFlag=0") })
    }

    /** FUNC-2：状态体缺 RunFlag 键（空/畸形回读）不得按缺省 0 误判「已关闭」。 */
    @Test
    fun `FUNC-2 power off with missing RunFlag key fails instead of false success`() {
        server.enqueue(MockResponse().setBody("true")) // /btZlg?runFlag=0
        server.enqueue(MockResponse().setBody("""{"err":""}""")) // 无 RunFlag 键的状态体
        val r = client.execute(Command.PowerOff(Device.JHG))
        assertTrue("缺 RunFlag 键应判未确认关机: $r", r is DeviceClient.SkillResult.Fail)
    }

    @Test
    fun `fan speed verifies FunSpeed`() {
        server.enqueue(MockResponse().setBody("true")) // /windSpeed?mode=2
        server.enqueue(zltStatus(1, funSpeed = 2))
        val r = client.execute(Command.FanSpeed(Device.ZLT, 2))
        assertTrue(r is DeviceClient.SkillResult.Ok)
        assertTrue((r as DeviceClient.SkillResult.Ok).speech.contains("中速档"))
        assertTrue(drain().any { it.startsWith("/windSpeed?mode=2") })
    }

    /** FUNC-3：回读校验跟随 cmd.device，不硬编码 ZLT（JHG 指令不得打整理台状态口）。 */
    @Test
    fun `FUNC-3 fan speed readback queries command device not hardcoded ZLT`() {
        server.enqueue(MockResponse().setBody("true")) // /windSpeed?mode=2
        server.enqueue(jhgStatus(0)) // JHG 状态体
        client.execute(Command.FanSpeed(Device.JHG, 2))
        val paths = drain()
        assertTrue("JHG 风速指令应回读 /getJHGData: $paths", paths.any { it.startsWith("/getJHGData") })
        assertTrue("不得硬编码回读整理台: $paths", paths.none { it.startsWith("/getZLTData") })
    }

    @Test
    fun `query status speech contains running state`() {
        server.enqueue(zltStatus(1, 1))
        val r = client.execute(Command.QueryStatus(Device.ZLT))
        assertTrue(r is DeviceClient.SkillResult.Ok)
        assertTrue((r as DeviceClient.SkillResult.Ok).speech.contains("运行中"))
    }

    @Test
    fun `network error degrades to fail speech without exception`() {
        server.shutdown() // 连接即拒
        val r = client.execute(Command.QueryStatus(Device.ZLT))
        assertTrue(r is DeviceClient.SkillResult.Fail)
        assertTrue((r as DeviceClient.SkillResult.Fail).speech.contains("连接失败"))
    }

    private fun drain(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val req = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            paths.add(req.path ?: "")
        }
        return paths
    }
}
