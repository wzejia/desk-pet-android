package com.xiaoduo.deskpet.voice.pipeline

/**
 * 语音 round 令牌原子化登记处（ocr[49] 定案：claim-then-use 原子化抽纯 JVM 守卫，
 * 2026-10-10 P3-T2）。原 OverlayBubbleSink/OverlayIndicatorSink 共用 @Volatile voiceRound
 * 裸读写：show 侧「svc.showBubble 返回→写登记」与 append/hide 侧「读登记」之间无原子性，
 * 交错 show（唤醒指示泡 show 竞回复泡 show）可把迟到的旧 show 写盖到后完成的 show 之上
 * ——后续 append/hide 盖错令牌，被服务侧 judgePublish 判 STALE 静默丢弃（合法 UI 更新
 * 丢失，PetOverlayService.kt:545 丢弃日志实证）。
 *
 * 守卫不变式：current() 恒等于**最后完成的 claim** 的返回值——claim（含 show 副作用）
 * 与 read 串行化在同一监视器上。变异锚：删 synchronized → VoiceRoundRegistryTest 用例 1 红。
 */
class VoiceRoundRegistry {

    private val lock = Any()

    @Volatile
    private var round = 0

    /**
     * claim：show 副作用在锁内执行并登记返回的 round（与 read 串行化，消除
     * claim-then-use 窗口）。[performShow] = svc.showBubble / svc.showVoiceIndicator 调用。
     */
    fun claim(performShow: () -> Int): Int = synchronized(lock) {
        round = performShow()
        round
    }

    /** 读当前 round（append/hide/hideLater 带同一令牌）。 */
    fun current(): Int = synchronized(lock) { round }
}
