package com.xiaoduo.deskpet.util

import android.content.Context
import android.content.SharedPreferences

/** 设置持久化（M1 两项：宠物缩放 / 气泡开关）。键名契约见 [PrefContract]。 */
object Prefs {
    const val KEY_PET_SCALE = PrefContract.KEY_PET_SCALE
    const val KEY_SHOW_BUBBLE = PrefContract.KEY_SHOW_BUBBLE

    const val DEFAULT_SCALE = PrefContract.DEFAULT_SCALE
    const val MIN_SCALE = PrefContract.MIN_SCALE
    const val MAX_SCALE = PrefContract.MAX_SCALE

    @Volatile
    private var settingsStore: SharedPreferences? = null

    /** SEC-3：settings 库迁加密存储（迁移收敛在本类内部，调用点签名零变化）。 */
    private fun sp(context: Context): SharedPreferences {
        settingsStore?.let { return it }
        synchronized(this) {
            settingsStore?.let { return it }
            return SecurePrefStore.migrating(
                context,
                PrefContract.SECURE_FILE_SETTINGS,
                PrefContract.FILE_SETTINGS,
            ).also { settingsStore = it }
        }
    }

    fun petScale(context: Context): Float =
        sp(context).getFloat(KEY_PET_SCALE, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)

    fun setPetScale(context: Context, scale: Float) {
        sp(context).edit().putFloat(KEY_PET_SCALE, scale.coerceIn(MIN_SCALE, MAX_SCALE)).apply()
    }

    fun showBubble(context: Context): Boolean =
        sp(context).getBoolean(KEY_SHOW_BUBBLE, PrefContract.DEFAULT_SHOW_BUBBLE)

    fun setShowBubble(context: Context, show: Boolean) {
        sp(context).edit().putBoolean(KEY_SHOW_BUBBLE, show).apply()
    }

    // ---- S3 新增键（悬浮窗双窗口：位置 / 总开关 / 语音开关）。M1 旧键不动。 ----
    const val KEY_OVERLAY_X = "overlay_x"            // Int, px；POSITION_UNSET = 未存
    const val KEY_OVERLAY_Y = "overlay_y"            // Int, px
    const val KEY_OVERLAY_ENABLED = "overlay_enabled" // Boolean, 悬浮窗总开关
    const val KEY_VOICE_ENABLED = "voice_enabled"     // Boolean, 语音开关（S4 接线）

    const val POSITION_UNSET = Int.MIN_VALUE
    const val DEFAULT_OVERLAY_ENABLED = false
    const val DEFAULT_VOICE_ENABLED = true

    fun overlayEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_OVERLAY_ENABLED, DEFAULT_OVERLAY_ENABLED)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun voiceEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_VOICE_ENABLED, DEFAULT_VOICE_ENABLED)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    /** 悬浮窗交互层左上角坐标；未存返回 (POSITION_UNSET, POSITION_UNSET)。 */
    fun overlayPosition(context: Context): Pair<Int, Int> {
        val prefs = sp(context)
        return prefs.getInt(KEY_OVERLAY_X, POSITION_UNSET) to
            prefs.getInt(KEY_OVERLAY_Y, POSITION_UNSET)
    }

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        sp(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    // ---- S5 新增键（KWS 常开唤醒）。M1/S3 旧键不动。 ----
    const val KEY_KWS_ENABLED = PrefContract.KEY_KWS_ENABLED
    const val KEY_KWS_GATE = PrefContract.KEY_KWS_GATE

    fun kwsEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_KWS_ENABLED, PrefContract.DEFAULT_KWS_ENABLED)

    fun setKwsEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_KWS_ENABLED, enabled).apply()
    }

    /** KWS 能量门控阈值（钳位在契约范围，防手滑/校准异常值写死唤醒）。 */
    fun kwsGate(context: Context): Float =
        sp(context).getFloat(KEY_KWS_GATE, PrefContract.DEFAULT_KWS_GATE)
            .coerceIn(PrefContract.MIN_KWS_GATE, PrefContract.MAX_KWS_GATE)

    fun setKwsGate(context: Context, gate: Float) {
        sp(context).edit().putFloat(
            KEY_KWS_GATE,
            gate.coerceIn(PrefContract.MIN_KWS_GATE, PrefContract.MAX_KWS_GATE),
        ).apply()
    }

    // ---- S7 新增键（全屏语音模式转写开关）。M1/S3/S5 旧键不动。 ----
    const val KEY_FULLSCREEN_TRANSCRIPT = PrefContract.KEY_FULLSCREEN_TRANSCRIPT

    fun fullscreenTranscript(context: Context): Boolean =
        sp(context).getBoolean(KEY_FULLSCREEN_TRANSCRIPT, PrefContract.DEFAULT_FULLSCREEN_TRANSCRIPT)

    fun setFullscreenTranscript(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_FULLSCREEN_TRANSCRIPT, enabled).apply()
    }

    // ---- S16 平台智能体选择（设置页列表单选；agentId=请求头路由，thread 连续性不动） ----
    const val KEY_AGENT_ID = PrefContract.KEY_AGENT_ID
    const val KEY_AGENT_DISPLAY_NAME = PrefContract.KEY_AGENT_DISPLAY_NAME

    /** 请求头 agentId（AguiClientFactory 动态 provider 读此）。默认 chatAgent=桌面端同默认。 */
    fun agentId(context: Context): String =
        sp(context).getString(KEY_AGENT_ID, null)?.takeIf { it.isNotBlank() }
            ?: PrefContract.DEFAULT_AGENT_ID

    /** 设置行离线显示名（未缓存时回退 agentId 本身）。 */
    fun agentDisplayName(context: Context): String =
        sp(context).getString(KEY_AGENT_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: agentId(context)

    /** 选择即存：id（协议路由）+ 显示名（离线显名）一起写。 */
    fun setAgent(context: Context, agentId: String, displayName: String) {
        sp(context).edit()
            .putString(KEY_AGENT_ID, agentId.trim())
            .putString(KEY_AGENT_DISPLAY_NAME, displayName.trim())
            .apply()
    }

    // ---- S18 VAD 说话阈值（环境校准联动；rk3568 噪声底骑线致判停失效而立） ----
    const val KEY_VAD_SPEECH_LEVEL = PrefContract.KEY_VAD_SPEECH_LEVEL
    const val KEY_AUTO_CALIB_DONE = PrefContract.KEY_AUTO_CALIB_DONE
    const val KEY_JUMP_PACKAGE = PrefContract.KEY_JUMP_PACKAGE
    const val DEFAULT_VAD_SPEECH_LEVEL = 0.05

    fun vadSpeechLevel(context: Context): Double =
        sp(context).getFloat(KEY_VAD_SPEECH_LEVEL, DEFAULT_VAD_SPEECH_LEVEL.toFloat())
            .toDouble()

    fun setVadSpeechLevel(context: Context, level: Double) {
        sp(context).edit()
            .putFloat(KEY_VAD_SPEECH_LEVEL, level.toFloat())
            .apply()
    }

    /** S29：首次自动校准标记（自动或手动写入过即 true，自动校准不再触发）。 */
    fun autoCalibDone(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_CALIB_DONE, false)

    fun setAutoCalibDone(context: Context, done: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_CALIB_DONE, done).apply()
    }

    /** S31：跳转 APP 目标包名（空 = 用默认密集架包）。 */
    fun jumpTargetPackage(context: Context): String =
        sp(context).getString(KEY_JUMP_PACKAGE, "") ?: ""

    fun setJumpTargetPackage(context: Context, pkg: String) {
        sp(context).edit().putString(KEY_JUMP_PACKAGE, pkg.trim()).apply()
    }

    /** S18：采麦源模式。0=VOICE_COMMUNICATION（手机默认，硬件 AEC）/ 1=VOICE_RECOGNITION
     *  （绕开通话链）/ 2=UNPROCESSED（板子 HAL/codec AGC 释放慢的最后一试）。 */
    const val KEY_MIC_SOURCE_MODE = PrefContract.KEY_MIC_SOURCE_MODE
    const val MIC_SOURCE_COMMUNICATION = 0
    const val MIC_SOURCE_RECOGNITION = 1
    const val MIC_SOURCE_UNPROCESSED = 2

    fun micSourceMode(context: Context): Int {
        val sp = sp(context)
        if (sp.contains(KEY_MIC_SOURCE_MODE)) return sp.getInt(KEY_MIC_SOURCE_MODE, MIC_SOURCE_COMMUNICATION)
        // 旧 bool 键迁移（S18 早期版本写入）
        return if (sp.getBoolean(PrefContract.KEY_MIC_RAW_SOURCE, false)) MIC_SOURCE_RECOGNITION
        else MIC_SOURCE_COMMUNICATION
    }

    fun setMicSourceMode(context: Context, mode: Int) {
        sp(context).edit()
            .putInt(KEY_MIC_SOURCE_MODE, mode.coerceIn(0, 2))
            .putBoolean(PrefContract.KEY_MIC_RAW_SOURCE, false) // 迁移源清零
            .apply()
    }

    /** S20：MCP 调用结果播报开关（默认开；关=含工具调用轮手机静默，设备自带播报场景）。 */
    const val KEY_SPEAK_MCP_RESULT = PrefContract.KEY_SPEAK_MCP_RESULT

    fun speakMcpResult(context: Context): Boolean =
        sp(context).getBoolean(KEY_SPEAK_MCP_RESULT, true)

    fun setSpeakMcpResult(context: Context, speak: Boolean) {
        sp(context).edit().putBoolean(KEY_SPEAK_MCP_RESULT, speak).apply()
    }

    /** S21：工具调用后需重新唤醒（默认开；工具轮结束回 IDLE，不走多轮续听）。 */
    const val KEY_REWAKE_AFTER_TOOL = PrefContract.KEY_REWAKE_AFTER_TOOL

    fun rewakeAfterToolCall(context: Context): Boolean =
        sp(context).getBoolean(KEY_REWAKE_AFTER_TOOL, true)

    fun setRewakeAfterToolCall(context: Context, rewake: Boolean) {
        sp(context).edit().putBoolean(KEY_REWAKE_AFTER_TOOL, rewake).apply()
    }

    /** S23：播放期回声消除（NLMS 参考消回声喂 KWS；默认开，A/B 对照可关）。 */
    const val KEY_ECHO_CANCEL = PrefContract.KEY_ECHO_CANCEL

    fun echoCancelEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_ECHO_CANCEL, true)

    fun setEchoCancelEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ECHO_CANCEL, enabled).apply()
    }

    /** S24：小朵播报音量（App 内，不动系统音量）。默认 0.45≈板子实测工作点
     *  （系统 15→9 档约 -7dB，麦克风退出削波区，播报期唤醒恢复）。 */
    const val KEY_TTS_VOLUME = PrefContract.KEY_TTS_VOLUME

    fun ttsVolume(context: Context): Float =
        sp(context).getFloat(KEY_TTS_VOLUME, 0.45f)

    fun setTtsVolume(context: Context, volume: Float) {
        sp(context).edit().putFloat(KEY_TTS_VOLUME, volume.coerceIn(0.1f, 1f)).apply()
    }

    /** S26：设备技能地址（整理台+净化柜一体机）。空串=禁用设备技能。 */
    const val KEY_DEVICE_BASE_URL = PrefContract.KEY_DEVICE_BASE_URL

    const val DEFAULT_DEVICE_BASE_URL = "http://10.0.18.47:8080"

    fun deviceBaseUrl(context: Context): String =
        sp(context).getString(KEY_DEVICE_BASE_URL, DEFAULT_DEVICE_BASE_URL).orEmpty()

    fun setDeviceBaseUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_DEVICE_BASE_URL, url.trim()).apply()
    }

    /** S41：密集架同步地址（MCP 动画联动桥 POST 目标）。未存=默认同机 127.0.0.1:8848；
     *  显式存空串=禁用联动（对齐 S26 deviceBaseUrl 形态）；分机部署改密集架 IP。 */
    const val KEY_SHELF_BASE_URL = PrefContract.KEY_SHELF_BASE_URL

    fun shelfBaseUrl(context: Context): String =
        sp(context).getString(KEY_SHELF_BASE_URL, PrefContract.DEFAULT_SHELF_BASE_URL).orEmpty()

    fun setShelfBaseUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_SHELF_BASE_URL, url.trim()).apply()
    }
}
