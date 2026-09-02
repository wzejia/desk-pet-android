package com.xiaoduo.deskpet.voice

/**
 * KWS 调参默认值（纯 Kotlin 常量承载，零 Android import，JVM 可测）。
 *
 * 本 object 存在的唯一理由 = 给调参值上测试锚。教训（2026-08-18 仓库事故）：
 * keywords_threshold 08-10 真机验证定为 0.3（IDLE 态 10/10 唤醒），但该值此前
 * 内联在 KwsEngine.buildConfig 里、无任何测试锚——08-18 仓库清空事故后转录重放
 * 重建时静默回退到 0.5（对账表旧值），真机随之回归唤不醒，直到 08-19 调研石锤。
 *
 * 改本文件任何值 = 改真机唤醒行为，必须同步改 KwsDefaultsTest 并在 KDoc 记理由。
 */
object KwsDefaults {

    /**
     * sherpa-onnx keywords_threshold：0.3 = 2026-08-10 真机验证值（IDLE 态 10/10）。
     * 0.5 是对账表出厂默认值，真机实测有概率唤不醒（08-19 用户报障石锤）。
     */
    const val KEYWORDS_THRESHOLD = 0.3f

    /**
     * KWS 能量门挂起窗（块数，100ms/块 → 8 = 800ms）：过门块之后窗内低于门的块
     * 仍喂真实音频（字间气口的弱音对 transducer 打分有意义，挖掉会压缩时间轴、
     * 把打分压到阈值下——真机「第一声必漏」机制①）；连续低于门超窗才停喂并对
     * KWS 流 gap-reset 一次（冲刷冻结尾分——「第二声秒中」假象机制②）。
     *
     * 取值依据：块长 100ms 锚知识包 §5 与桌面 sidecar.py _KWS_BLOCK_MS=100。
     * 原与 VadDetector.SILENCE_END_TICKS 同源（8/800ms）--2026-08-29 起 VAD 端点提速
     * 为 4 块/400ms（用户拍板，对账表补注 3），但 KWS 挂起窗独立保持 8：其保护对象
     * 是唤醒词字间气口（真机「第一声必漏」修复实证参数），与说话端点判定语义不同，
     * 不随 VAD 联动。
     */
    const val GATE_HANGOVER_BLOCKS = 8

    /**
     * S22 影子通道阈值（播放期第二 spotter）：0.15 = 初始探针值（主阈值 0.3 的一半）。
     * 影子模式命中只记日志不打断——板子一晚数据（真打断漏检补给率 vs 回声自误触率）
     * 定转正阈值后再调；改本值 = 改影子数据口径，必须同步 KwsDefaultsTest 并记 KDoc。
     */
    const val SHADOW_KEYWORDS_THRESHOLD = 0.15f
}
