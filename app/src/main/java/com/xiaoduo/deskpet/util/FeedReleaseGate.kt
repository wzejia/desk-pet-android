package com.xiaoduo.deskpet.util

/**
 * ROB-4：feed/release 单监视器串行门（纯 Kotlin，JVM 可测）。
 *
 * 采集线程 feed 与销毁路径 release 此前无共锁（VoiceForegroundService.kwsSink）：
 * sink null-check-then-invoke 窗口内旧闭包仍可喂已 release 的原生引擎
 * （use-after-release → SIGSEGV 无 Java 栈）。本门保证：
 * - close() 之后 feed 主体绝不执行（close 持锁置 closed，feed 持锁复查，同监视器串行）；
 * - teardown（引擎 release）与残余 feed 互斥且恰好执行一次。
 */
class FeedReleaseGate {

    private val lock = Any()

    @Volatile
    private var closed = false // lock 内写；@Volatile 供观测，判定一律在锁内

    /** 采集线程喂入：已关闭则跳过（与 close 串行，喂入绝不落在 release 之后）。 */
    fun feed(block: () -> Unit) {
        synchronized(lock) {
            if (!closed) block()
        }
    }

    /** 销毁路径关闭：teardown 与在途 feed 互斥，且只执行一次。 */
    fun close(teardown: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            closed = true
            teardown()
        }
    }
}
