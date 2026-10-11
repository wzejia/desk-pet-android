package com.xiaoduo.deskpet.voice.wakeack

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 预取单飞门（ocr[394] 收口，2026-10-11）：caller 线程 [tryEnter] CAS 原子占位，
 * daemon body finally [leave]——「shouldSkip→预取→记账」序列因此全程单飞，
 * PrefetchBackoff 的 check-then-act 竞态（ocr[394]）在结构上不可达。
 *
 * 裁定（T2 上报两案择一）：PrefetchBackoff 内原子化（failures.compute 预留协议）
 * 不做——并发同 key 预取当前结构上不可能（本门全局串行），原子化是为不可达竞态叠
 * 第二套机制，且预留协议自带「占位后放弃」新失效面。独立成类仅为 JVM 可测
 * （maybePrefetch 绑 Context+真线程）；并发契约由 PrefetchSingleFlightTest 钉死。
 */
internal class PrefetchSingleFlight {

    private val inflight = AtomicBoolean(false)

    /** CAS 占位：false=已在飞，调用方直接退（零等待零排队）。 */
    fun tryEnter(): Boolean = inflight.compareAndSet(false, true)

    /** 释放：daemon body finally 必调（异常路径也不留死门，对齐 WakeAckPrefetcher 契约）。 */
    fun leave() {
        inflight.set(false)
    }
}
