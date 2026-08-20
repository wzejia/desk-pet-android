package com.xiaoduo.deskpet.voice

/**
 * 延迟取证探针总开关（纯逻辑，JVM 可测，零 Android import）——S11-6 诊断票，只探不修。
 *
 * 背景与结论（2026-08-20 真机取证）：唤醒词「第一声必漏」曾疑为音频管道秒级延迟，
 * 本探针证伪了该理论——cap 行 lag 全场 1-4ms、cap→feed 差 2-6ms，管道实时无积压。
 * 真凶由探针时间线钉死：能量门控「整块不喂不补静音」碎裂 KWS 流时间轴（字间气口被挖
 * 压分 + 冻结部分分不冲刷被下一声补完），修复见 S11-7（KwsCore 挂起窗+gap-reset）。
 * 探针保留供后续延迟类诊断复用：AudioCapture emit 侧打 `cap rms=… lag=…ms`，
 * KwsCore feed 侧打 `feed rms=…`，时间戳同域对照。
 *
 * 配对口径：过门块 cap/feed 两侧 1:1；**挂起窗内入队的低能块只打 feed 侧**（feed ⊋ cap，
 * 孤行属预期）；SPEAKING 等非 KWS 路由态只可能有 cap 侧（块被路由 DROP）。按 rms 值
 * 相同 + 时序近邻互配。
 *
 * 配对纪律：arm/disarm 仅 AecSpikeSection toggle 按钮与进程死亡（内存态无持久化，
 * 进程死自然归 false）；未 arm 时每块仅一次 volatile 读，零日志零 syscall。
 */
object AudioProbe {

    /** 调试总开关：true 时 cap/feed 两侧过门块各打一行（跨组件可读）。 */
    @Volatile
    var armed: Boolean = false

    /**
     * 过门判定：armed 且块能量过门才打——安静期零日志量。
     * 回归守卫：删 armed 判定（只剩 rms>=gate）→ 未 arm 也打 → AudioProbeTest 红。
     */
    fun shouldLog(rms: Float, gate: Float): Boolean = armed && rms >= gate

    /** 采集时刻→观察时刻的管道延迟（毫秒）；负值=时钟异常，原样透出供日志分析。 */
    fun lagMs(nowNanos: Long, captureNanos: Long): Long = (nowNanos - captureNanos) / 1_000_000
}
