package com.xiaoduo.deskpet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * KWS init 引导门（thread-discipline 件 2，ocr[205] 裸 Thread→service-scoped scope
 * 修复；设计定案 docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md）。
 *
 * 把「异步加载引擎 → 活性检查 → 交付」的骨架抽成纯 JVM 类：scope 由 service 注入
 * （onDestroy 单点 scope.cancel），销毁事件经结构化并发传播到在途 init。
 *
 * 语义（与退役的 OnceGate initGate + destroyed 收尾检查等价或更强）：
 * - 在途 bootstrap 拒绝重入（防双载 ~5.4MB onnx 模型，原 backlog #8a）；
 * - scope 已取消后再 bootstrap 立即拒绝；
 * - factory 结果在交付前查 [kotlinx.coroutines.isActive]——bootstrap 进行中
 *   cancel 则结果被丢弃，onReady 不回调（= engine 赋值/挂 sink 不发生）。
 *
 * 变异锚：删 onReady 前 isActive 检查 → KwsBootstrapTest.cancelWhileFactoryBlocked
 * 红；删 job?.isActive 在途拒绝 → reentryRejectedWhileInFlight 红。
 */
class KwsBootstrap(private val scope: CoroutineScope) {

    private val lock = Any()
    private var job: Job? = null

    /**
     * 启动一次引导；在途或 scope 已取消时拒绝（返 false，不执行 factory）。
     * factory 抛异常（取消异常除外）→ [onFailure] 回调、onReady 不调、job 正常
     * 结束（可重新 bootstrap = 原 initGate 失败放行语义）。onReady/onFailure 在
     * scope 协程上回调（勿在其中做主线程依赖操作；service 侧收尾段自带
     * synchronized(lock)）。
     */
    fun <T> bootstrap(
        engineFactory: suspend () -> T,
        onReady: (T) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        synchronized(lock) {
            if (job?.isActive == true) return false
            if (!scope.isActive) return false
            job = scope.launch {
                val e = try {
                    engineFactory()
                } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                    throw ce // 取消不是失败：透传拆解，不进 onFailure
                } catch (t: Throwable) {
                    onFailure(t)
                    return@launch
                }
                if (!coroutineContext.isActive) return@launch // 销毁传播：结果丢弃
                onReady(e)
            }
            return true
        }
    }
}
