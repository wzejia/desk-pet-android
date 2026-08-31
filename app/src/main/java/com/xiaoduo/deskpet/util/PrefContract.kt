package com.xiaoduo.deskpet.util

/**
 * SharedPreferences 键/默认值契约（纯 Kotlin，零 Android import，JVM 可测）。
 * 防升级丢键：键集变更必须同步改 PrefContractTest。
 */
object PrefContract {
    const val FILE_SETTINGS = "xiaoduo_settings"

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

    // ---- S5 新增键（KWS 常开唤醒：总开关 / 能量门控阈值）----
    const val KEY_KWS_ENABLED = "kws_enabled"      // Boolean, 「小朵小朵」唤醒前台服务开关
    const val KEY_KWS_GATE = "kws_energy_gate"     // Float, RMS 能量门控阈值

    /** 对账表 KWS 节：默认 0.015（真麦静音 ~0.001-0.006 / 说话 ~0.036，居中）。 */
    const val DEFAULT_KWS_ENABLED = false
    const val DEFAULT_KWS_GATE = 0.015f

    /** 门控可调范围（校准建议值同域钳位，EnergyGate 引用）。 */
    const val MIN_KWS_GATE = 0.003f
    const val MAX_KWS_GATE = 0.05f

    // ---- S7 新增键（全屏语音模式：回复转写开关）----
    const val KEY_FULLSCREEN_TRANSCRIPT = "fullscreen_transcript" // Boolean, 全屏模式底部显示回复文字
    const val DEFAULT_FULLSCREEN_TRANSCRIPT = false // 默认关：纯 TTS+小朵动画（ADR 0003）

    /** S7 交付的全部设置键，升级后必须原样保留。 */
    val S7_KEYS: Set<String> = setOf(KEY_FULLSCREEN_TRANSCRIPT)

    // ---- S16 平台智能体选择 ----
    const val KEY_AGENT_ID = "platform_agent_id"          // String, 请求头 agentId（默认 chatAgent）
    const val KEY_AGENT_DISPLAY_NAME = "platform_agent_name" // String, 离线显示名缓存（默认空 -> 显 id）

    const val DEFAULT_AGENT_ID = "chatAgent"

    val S16_KEYS: Set<String> = setOf(KEY_AGENT_ID, KEY_AGENT_DISPLAY_NAME)

    // ---- S18 VAD 说话阈值（环境校准联动）----
    const val KEY_VAD_SPEECH_LEVEL = "vad_speech_level"   // Float，默认 0.05（对账表）

    const val KEY_MIC_RAW_SOURCE = "mic_raw_source"       // Boolean（S18 旧键，迁移源）
    const val KEY_MIC_SOURCE_MODE = "mic_source_mode"     // Int：0=通话 1=识别 2=原始

    val S18_KEYS: Set<String> = setOf(KEY_VAD_SPEECH_LEVEL, KEY_MIC_SOURCE_MODE)
}
