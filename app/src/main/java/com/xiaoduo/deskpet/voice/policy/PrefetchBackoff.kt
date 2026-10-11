package com.xiaoduo.deskpet.voice.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * 预取失败退避台账（S9-2，进程内内存，零持久化零 Android import）。
 *
 * 同 key 失败后 10min 窗内 shouldSkip=true（预取 daemon 不再打同一失败合成）；
 * 窗过放行（下次触发重试）；成功清账；异 key 互不影响。
 * 时间全部由调用方注入（nowMs 参数），不碰系统时钟 → JVM 可测。
 *
 * 并发/边界守卫（ocr[392]/[393]/[394]/[395]，2026-10-10 P3-T2）：
 * - ocr[393] 负差值=时钟回拨：按过期放行（旧式 `nowMs - last < windowMs` 对回拨恒
 *   true，且抑制期 daemon 早退永不 recordSuccess——该 key 被无限期跳过，卡死超过
 *   10min 契约；唯一调用方注入 System.currentTimeMillis 墙钟，NTP/手动对时为真实场景）。
 *   变异锚：删负差值放行门 → PrefetchBackoffGuardTest 时钟回拨用例红。
 * - ocr[392] 过期条目顺手清理：两参 remove（值不同=并发新失败则不覆盖），防 key 空间
 *   动态时台账无界慢泄漏。变异锚：删清理 → 过期清理用例红（sizeForTest 不归零）。
 * - ocr[394] 单飞保证（2026-10-11 wakeack 票收口）：shouldSkip→预取→record 非原子，
 *   结构前提=唯一调用方 WakeAckPrefetcher 经 PrefetchSingleFlight 全局单飞（生产路径
 *   唯一入口 maybePrefetch；并发契约由 PrefetchSingleFlightTest 变异锚钉死）。本类只保
 *   map 完整性。原子化 gate（failures.compute 预留协议）裁定不做——并发同 key 预取
 *   结构上不可达，为不可达竞态叠第二套机制不值，裁定理由见 PrefetchSingleFlight KDoc。
 * - ocr[395] 边界 '<' 语义钉死：恰满 windowMs 放行重试（S9-2「10min 窗内」口径=严格
 *   小于；PinTest 边界用例锁，变异 '<'→'<=' → 红）。
 */
class PrefetchBackoff(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val failures = ConcurrentHashMap<String, Long>()

    fun shouldSkip(key: String, nowMs: Long): Boolean {
        val last = failures[key] ?: return false
        val elapsed = nowMs - last
        if (elapsed < 0) return false // ocr[393]：时钟回拨按过期放行（变异锚：删门 → 回拨用例红）
        // S9-2 回归守卫：失败 10min 窗内跳过、窗过放行（删窗比较恒 false/恒 true → 窗内拒/过期放行用例 → 红）
        val inWindow = elapsed < windowMs
        if (!inWindow) failures.remove(key, last) // ocr[392]：过期顺手清理（两参 remove 防覆盖并发新失败）
        return inWindow
    }

    fun recordFailure(key: String, nowMs: Long) {
        failures[key] = nowMs
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    /** 测试缝：台账占用（ocr[392] 过期清理断言用）。 */
    internal fun sizeForTest(): Int = failures.size

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 10L * 60 * 1000
    }
}
