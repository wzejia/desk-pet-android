package com.xiaoduo.deskpet

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 唤醒处理临界区门（thread-discipline 件 1，ocr[202] TOCTOU 修复；设计定案
 * docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md）。
 *
 * 防线目标：唤醒回调（KwsEngine 解码线程）的「进入唤醒处理」（含
 * VoiceSessionManager.startListening 的重新武装采麦）与 service teardown
 * （onDestroy，主线程：清 kwsSink + stopIdleCapture）互斥。TOCTOU 窗口内
 * startListening 落在已 teardown 的 session 上 = 孤儿 capture 无主清理。
 *
 * 语义：
 * - [runExclusive]：close 已调则 no-op 返 false；否则持读锁执行块
 *   （读锁 = 允许多个唤醒并发进入，与现状等价）。
 * - [close]：取写锁关门——写锁天然等待所有在途读锁，返回即 in-flight
 *   临界区全部完成；此后所有 runExclusive 拒绝。幂等。
 *
 * 死锁安全：临界区内容（startListening 链路）为全同步快速路径，无主线程
 * 同步依赖（cancelCurrentTurn 全同步、playAckThenListen 仅 launch 不等待）——
 * close 在主线程等待解码线程块完成不会成环。
 *
 * 变异锚：runExclusive 去锁（只查 volatile，check 与执行分离）→
 * WakeEntryGateTest.closeWaitsForInFlightBlock 必红。
 */
class WakeEntryGate {

    private val rw = ReentrantReadWriteLock()
    private val read = rw.readLock()
    private val write = rw.writeLock()

    @Volatile
    private var closed = false

    /** 唤醒处理临界区：close 已调则 no-op 返 false；否则持读锁执行块返 true。 */
    fun runExclusive(block: () -> Unit): Boolean {
        read.lock()
        try {
            if (closed) return false
            block()
            return true
        } finally {
            read.unlock()
        }
    }

    /** teardown 侧：关门并等待 in-flight 临界区完成。幂等。 */
    fun close() {
        write.lock()
        try {
            closed = true
        } finally {
            write.unlock()
        }
    }
}
