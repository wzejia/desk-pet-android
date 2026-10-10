package com.xiaoduo.deskpet.util


/**
 * SharedPreferences 键/默认值契约（纯 Kotlin，零 Android import，JVM 可测）。
 * 防升级丢键：键集变更必须同步改 PrefContractTest。
 *
 * 结构纪律（P3-T4，ocr[370]/[373]/[374]）：
 * - 每个 Sprint 的 KEY_* + DEFAULT_* + S*_KEYS 收敛在同一分组头下（组间漂移=对账测试红）；
 * - [ALL_KEYS] = 全部登记键（完备性由测试反射扫描兜底：新 KEY_ 常量漏登记即红）；
 * - [LEGACY_KEYS] = 迁移源旧键：升级清理/键集对账不得删（Prefs 仍在读它兜底），
 *   直到确认全部用户已迁移；处置动向前必须先立甄别记录，不删实际数据键。
 */
object PrefContract {
    const val FILE_SETTINGS = "xiaoduo_settings"

    // SEC-3 迁移后的加密新库（旧明文库 FILE_SETTINGS 只读回落+搬迁写回，永不删除）
    const val SECURE_FILE_SETTINGS = "xiaoduo_settings_secure"

    // ---- M1 既有键（不可改名，改名=丢用户配置）----
    const val KEY_PET_SCALE = "pet_scale"      // Float, 0.5 - 2.0
    const val KEY_SHOW_BUBBLE = "show_bubble"  // Boolean

    // 盲测 v3「遮挡克制」批评：1.0 下宠物 ~650px 是图标(~220px)的 3 倍、压 dock 电话图标；
    // 0.5 下 208dp×0.5×4≈416px≈1.9×图标高，落 1.5~2× 锚点区间（有意变更默认值，键名不动）
    const val DEFAULT_SCALE = 0.5f
    const val DEFAULT_SHOW_BUBBLE = true
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.0f

    /** M1 交付的全部设置键，升级后必须原样保留。 */
    val M1_KEYS: Set<String> = setOf(KEY_PET_SCALE, KEY_SHOW_BUBBLE)

    // ---- S3 悬浮窗双窗口（键此前内联 Prefs.kt；本次入约，消费方引用保持不动）----
    const val KEY_OVERLAY_X = "overlay_x"             // Int, px；POSITION_UNSET = 未存
    const val KEY_OVERLAY_Y = "overlay_y"             // Int, px
    const val KEY_OVERLAY_ENABLED = "overlay_enabled" // Boolean, 悬浮窗总开关
    const val KEY_VOICE_ENABLED = "voice_enabled"     // Boolean, 语音开关（S4 接线）

    const val POSITION_UNSET = Int.MIN_VALUE
    const val DEFAULT_OVERLAY_ENABLED = false
    const val DEFAULT_VOICE_ENABLED = true

    val S3_KEYS: Set<String> =
        setOf(KEY_OVERLAY_X, KEY_OVERLAY_Y, KEY_OVERLAY_ENABLED, KEY_VOICE_ENABLED)

    // ---- S5 KWS 常开唤醒（总开关 / 能量门控阈值）----
    const val KEY_KWS_ENABLED = "kws_enabled"      // Boolean, 「小朵小朵」唤醒前台服务开关
    const val KEY_KWS_GATE = "kws_energy_gate"     // Float, RMS 能量门控阈值

    /** 对账表 KWS 节：默认 0.015（真麦静音 ~0.001-0.006 / 说话 ~0.036，居中）。 */
    const val DEFAULT_KWS_ENABLED = false
    const val DEFAULT_KWS_GATE = 0.015f

    /** 门控可调范围（校准建议值同域钳位，EnergyGate 引用）。 */
    const val MIN_KWS_GATE = 0.003f
    const val MAX_KWS_GATE = 0.05f

    val S5_KEYS: Set<String> = setOf(KEY_KWS_ENABLED, KEY_KWS_GATE)

    // ---- S7 全屏语音模式：回复转写开关 ----
    const val KEY_FULLSCREEN_TRANSCRIPT = "fullscreen_transcript" // Boolean
    const val DEFAULT_FULLSCREEN_TRANSCRIPT = false // 默认关：纯 TTS+小朵动画（ADR 0003）

    /** S7 交付的全部设置键，升级后必须原样保留。 */
    val S7_KEYS: Set<String> = setOf(KEY_FULLSCREEN_TRANSCRIPT)

    // ---- S16 平台智能体选择 ----
    const val KEY_AGENT_ID = "platform_agent_id"          // String, 请求头 agentId
    const val KEY_AGENT_DISPLAY_NAME = "platform_agent_name" // String, 离线显示名缓存

    const val DEFAULT_AGENT_ID = "chatAgent"
    const val DEFAULT_AGENT_DISPLAY_NAME = "" // 空 → 运行时回退 agentId 本身

    val S16_KEYS: Set<String> = setOf(KEY_AGENT_ID, KEY_AGENT_DISPLAY_NAME)

    // ---- S18 VAD 说话阈值 / 采麦源 ----
    const val KEY_VAD_SPEECH_LEVEL = "vad_speech_level"   // Float
    const val DEFAULT_VAD_SPEECH_LEVEL = 0.05f            // 对账表（Prefs 以 Double 同值双写）

    const val KEY_MIC_SOURCE_MODE = "mic_source_mode"     // Int：0=通话 1=识别 2=原始
    const val DEFAULT_MIC_SOURCE_MODE = 0                 // 0=VOICE_COMMUNICATION

    val S18_KEYS: Set<String> = setOf(KEY_VAD_SPEECH_LEVEL, KEY_MIC_SOURCE_MODE)

    // ---- S20 MCP 调用结果播报开关 ----
    const val KEY_SPEAK_MCP_RESULT = "speak_mcp_result"   // Boolean
    const val DEFAULT_SPEAK_MCP_RESULT = true             // 默认开

    val S20_KEYS: Set<String> = setOf(KEY_SPEAK_MCP_RESULT)

    // ---- S21 工具调用后需重新唤醒 ----
    const val KEY_REWAKE_AFTER_TOOL = "rewake_after_tool" // Boolean
    const val DEFAULT_REWAKE_AFTER_TOOL = true            // 默认开：工具轮结束回 IDLE

    val S21_KEYS: Set<String> = setOf(KEY_REWAKE_AFTER_TOOL)

    // ---- S23 播放期回声消除（NLMS 实验）----
    const val KEY_ECHO_CANCEL = "echo_cancel"             // Boolean
    const val DEFAULT_ECHO_CANCEL = true                  // 默认开，A/B 对照可关

    val S23_KEYS: Set<String> = setOf(KEY_ECHO_CANCEL)

    // ---- S24 小朵播报音量 ----
    const val KEY_TTS_VOLUME = "tts_volume"               // Float 0..1
    const val DEFAULT_TTS_VOLUME = 0.45f                  // 板子实测工作点（系统 15→9 档约 -7dB）

    val S24_KEYS: Set<String> = setOf(KEY_TTS_VOLUME)

    // ---- S26 设备技能地址（空=禁用设备技能）----
    const val KEY_DEVICE_BASE_URL = "device_base_url"     // String
    const val DEFAULT_DEVICE_BASE_URL = "http://10.0.18.47:8080" // 与 Prefs 运行时默认同值

    val S26_KEYS: Set<String> = setOf(KEY_DEVICE_BASE_URL)

    // ---- S29 首次自动校准标记 ----
    const val KEY_AUTO_CALIB_DONE = "kws_auto_calib_done" // Boolean, true=已校准过，不再自动校
    const val DEFAULT_AUTO_CALIB_DONE = false

    val S29_KEYS: Set<String> = setOf(KEY_AUTO_CALIB_DONE)

    // ---- S31 跳转 APP 目标可配置 ----
    const val KEY_JUMP_PACKAGE = "jump_target_package"    // String，空=默认密集架
    const val DEFAULT_JUMP_PACKAGE = "com.yinduo.compactShelf" // 唯一事实源；AppLauncher 同值双写（对账测试钉住）

    val S31_KEYS: Set<String> = setOf(KEY_JUMP_PACKAGE)

    // ---- S41 密集架 MCP 动画联动（同步地址；显式存空串=禁用）----
    const val KEY_SHELF_BASE_URL = "shelf_base_url"     // String，默认 http://127.0.0.1:8848
    const val DEFAULT_SHELF_BASE_URL = "http://127.0.0.1:8848"

    val S41_KEYS: Set<String> = setOf(KEY_SHELF_BASE_URL)

    // ---- 迁移源旧键（ocr[374]：升级清理/键集对账不得删，Prefs.micSourceMode 仍在读）----
    const val KEY_MIC_RAW_SOURCE = "mic_raw_source"       // Boolean（S18 旧键，迁移源）

    /** 迁移专用旧键：必须原样保留，直到确认全部用户已迁移到 mic_source_mode。 */
    val LEGACY_KEYS: Set<String> = setOf(KEY_MIC_RAW_SOURCE)

    /** 全量键集合（ocr[370]）：供 PrefContractTest 做「无未登记键」兜底断言；升级清理白名单。 */
    val ALL_KEYS: Set<String> = M1_KEYS + S3_KEYS + S5_KEYS + S7_KEYS + S16_KEYS +
        S18_KEYS + S20_KEYS + S21_KEYS + S23_KEYS + S24_KEYS +
        S26_KEYS + S29_KEYS + S31_KEYS + S41_KEYS
}
