package com.xiaoduo.deskpet

/** 进程内记录悬浮窗服务运行态（M1 单进程足够）。
 *  活性记账（spec §4.3，2026-10-10）：running 只能经 markStarted/markCleared 变更；
 *  service 周期喂 markHeartbeat 续期，isAlive = 已启动且心跳未过期——
 *  防 service 死于 onDestroy 之外（异常路径未清位）时设置页 stale-true 误判。 */
object PetOverlayState {
    /** service 心跳周期。 */
    const val HEARTBEAT_INTERVAL_MS: Long = 30_000L

    /** 心跳超过该时长未续视为 stale（3 个周期，容忍单次调度抖动）。 */
    const val STALE_AFTER_MS: Long = 90_000L

    @Volatile private var startedFlag = false
    @Volatile private var lastMarkUptimeMs = 0L

    val running: Boolean get() = startedFlag

    /** 起算活性戳。主线程收编契约：全部写者（onStartCommand 链/主线程 handler/onDestroy）
     *  与唯一产线读者（SettingsScreen）同在主线程——双字段写序对并发读者不构成承诺。
     *  先写 lastMark 再写 startedFlag：volatile 序使 startedFlag=true 可见时 lastMark 必已可见。 */
    fun markStarted(nowUptimeMs: Long) {
        lastMarkUptimeMs = nowUptimeMs
        startedFlag = true
    }

    /** 续活性戳。不写 startedFlag（复活防线，PetOverlayStateTest 锁）——
     *  startedFlag 短路在 isAlive 兜底，此处无需重复守卫。 */
    fun markHeartbeat(nowUptimeMs: Long) {
        lastMarkUptimeMs = nowUptimeMs
    }

    fun markCleared() {
        startedFlag = false
        lastMarkUptimeMs = 0L
    }

    /** 悬浮窗在跑且心跳未过期（读点唯一判定面）。
     *  时钟契约：nowUptimeMs 须与 markStarted/markHeartbeat 同源（SystemClock.elapsedRealtime，
     *  单机单调）——注入其他时钟（如 currentTimeMillis）则双向静默失真；负增量（now<lastMark）
     *  视为存活（单调源下不可达，语义上倒拨偏保守）。 */
    fun isAlive(nowUptimeMs: Long): Boolean =
        startedFlag && nowUptimeMs - lastMarkUptimeMs <= STALE_AFTER_MS
}
