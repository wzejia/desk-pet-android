package com.xiaoduo.deskpet.voice.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * 预取失败退避台账（S9-2，进程内内存，零持久化零 Android import）。
 *
 * 同 key 失败后 10min 窗内 shouldSkip=true（预取 daemon 不再打同一失败合成）；
 * 窗过放行（下次触发重试）；成功清账；异 key 互不影响。
 * 时间全部由调用方注入（nowMs 参数），不碰系统时钟 → JVM 可测。
 */
class PrefetchBackoff(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val failures = ConcurrentHashMap<String, Long>()

    fun shouldSkip(key: String, nowMs: Long): Boolean {
        val last = failures[key] ?: return false
        // S9-2 回归守卫：失败 10min 窗内跳过、窗过放行（删窗比较恒 false/恒 true → 窗内拒/过期放行用例 → 红）
        return nowMs - last < windowMs
    }

    fun recordFailure(key: String, nowMs: Long) {
        failures[key] = nowMs
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 10L * 60 * 1000
    }
}
