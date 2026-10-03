package com.xiaoduo.deskpet.device

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 整理台+净化柜设备客户端（S26-2）。
 *
 * 接口约束（实测文档 2026-09-04，10.0.18.47:8080，HTTP GET 无鉴权）：
 * 1. 控制接口返回 true 只代表「已接收」——**必须延时回读 RunFlag/FunSpeed 验证**；
 * 2. 开机只用模式接口（runFlag=1 无效，勿用）；
 * 3. 局域网直连**绕过系统代理**（OkHttp 显式 NO_PROXY）；
 * 4. 命令间隔建议 ≥3s（本客户端单轮只发一条，天然满足；回读延时注入可测）。
 *
 * 输出统一为 [SkillResult]：speech=给用户的一句话（供 TTS/字幕，设备轮按工具轮
 * 待遇通常静音只上屏，见 VSM 接线）；失败也有 speech（降级播报，不抛异常）。
 */
class DeviceClient(
    private val http: OkHttpClient = defaultHttp(),
    private val baseUrl: String = "http://10.0.18.47:8080",
    /** 命令→回读的等待（文档建议 3-5s；语音体感取 2.5s 折中）。测试注入空实现。 */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val verifyDelayMs: Long = 2_500,
    private val logger: (String) -> Unit = {},
) {

    sealed interface SkillResult {
        val speech: String
        data class Ok(override val speech: String) : SkillResult
        data class Fail(override val speech: String) : SkillResult
    }

    fun execute(cmd: DeviceIntentMatcher.Command): SkillResult {
        val device = cmd.device
        return try {
            when (cmd) {
                is DeviceIntentMatcher.Command.PowerOn -> {
                    when (device) {
                        DeviceIntentMatcher.Device.ZLT -> get("/btRunMode${cmd.mode}")
                        DeviceIntentMatcher.Device.JHG -> get("/level1BtRunMode4?mode=${cmd.mode}")
                    }
                    sleeper(verifyDelayMs)
                    val st = status(device)
                    if (st.optString("err").isNotEmpty() || st.optInt("RunFlag") != 1) {
                        SkillResult.Fail("${nameOf(device)}未确认开机，请看一眼面板")
                    } else {
                        SkillResult.Ok("${nameOf(device)}已打开。${envSpeech(st)}")
                    }
                }
                is DeviceIntentMatcher.Command.PowerOff -> {
                    when (device) {
                        DeviceIntentMatcher.Device.ZLT -> get("/btZlt?runFlag=0")
                        DeviceIntentMatcher.Device.JHG -> get("/btZlg?runFlag=0")
                    }
                    sleeper(verifyDelayMs)
                    val st = status(device)
                    // FUNC-2：缺省哨兵 -1——状态体缺 RunFlag 键（空/畸形回读）不得按 0 误判「已关闭」
                    if (st.optString("err").isNotEmpty() || st.optInt("RunFlag", -1) != 0) {
                        SkillResult.Fail("${nameOf(device)}未确认关机，请看一眼面板")
                    } else {
                        SkillResult.Ok("${nameOf(device)}已关闭")
                    }
                }
                is DeviceIntentMatcher.Command.FanSpeed -> {
                    get("/windSpeed?mode=${cmd.speed}")
                    sleeper(verifyDelayMs)
                    val st = status(DeviceIntentMatcher.Device.ZLT)
                    when {
                        st.optString("err").isNotEmpty() -> SkillResult.Fail("整理台状态读取失败")
                        st.optInt("FunSpeed") == cmd.speed -> SkillResult.Ok("风速已调到${gearName(cmd.speed)}")
                        else -> SkillResult.Fail("风速未确认（自动模式下由设备调节），请看一眼面板")
                    }
                }
                is DeviceIntentMatcher.Command.QueryStatus -> {
                    val st = status(device)
                    if (st.optString("err").isNotEmpty()) SkillResult.Fail("${nameOf(device)}状态读取失败")
                    else SkillResult.Ok(
                        "${nameOf(device)}${if (st.optInt("RunFlag") == 1) "运行中" else "已停止"}。${envSpeech(st)}",
                    )
                }
            }
        } catch (e: Exception) {
            logger("device skill error: ${e.message}")
            SkillResult.Fail("设备连接失败，请检查网络后重试")
        }
    }

    private fun status(device: DeviceIntentMatcher.Device): JSONObject {
        val path = when (device) {
            DeviceIntentMatcher.Device.ZLT -> "/getZLTData"
            DeviceIntentMatcher.Device.JHG -> "/getJHGData"
        }
        return JSONObject(get(path))
    }

    private fun get(path: String): String {
        val req = Request.Builder().url(baseUrl + path).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun nameOf(d: DeviceIntentMatcher.Device) = when (d) {
        DeviceIntentMatcher.Device.ZLT -> "整理台"
        DeviceIntentMatcher.Device.JHG -> "净化柜"
    }

    private fun gearName(n: Int) = when (n) {
        1 -> "低速档"
        2 -> "中速档"
        else -> "高速档"
    }

    private fun envSpeech(st: JSONObject): String {
        val parts = mutableListOf<String>()
        parts.add("温度${st.optInt("temp")}度")
        parts.add("湿度${st.optInt("humd")}%")
        val pm = st.optInt("pm25", -1)
        if (pm >= 0) parts.add("PM2.5为$pm")
        return parts.joinToString("，") + "。"
    }

    companion object {
        /** 显式 NO_PROXY：局域网直连（设备文档约束——系统代理会 502）。 */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
