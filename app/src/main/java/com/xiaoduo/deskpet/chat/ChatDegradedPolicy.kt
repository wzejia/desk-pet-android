package com.xiaoduo.deskpet.chat

/**
 * 磁盘降级期写守卫（P3-2 拍板 2026-10-10：静默降级 + 降级期禁写防数据损坏）。
 *
 * 拍板口径：索引持久化失败（degraded）时 VM init 降级运行（不崩溃）+ 显式日志 +
 * 降级期禁写（只读保护，兼顾 DATA-1 初意的数据完整性）。本策略集中「降级判定」与
 * 「哪类操作被拒」两处决策，纯 JVM 可测；ChatViewModel 薄接线。
 *
 * 变异锚：allowWrite 判定删 !degraded（恒放行）→ ChatDegradedPolicyTest 全拒用例红；
 * shouldMarkDegraded 判空删除 → VM 无法置降级位（用例红）。
 */
object ChatDegradedPolicy {

    /** 降级期被拒的写操作类别。读路径（浏览/搜索/加载）不在拒绝面。 */
    enum class Op { CREATE_SESSION, SEND_MESSAGE, UPLOAD_FILE }

    /** createSession 结果 → 是否置降级位（null = 索引不可写，进入只读降级态）。 */
    fun shouldMarkDegraded(createResult: ChatSession?): Boolean = createResult == null

    /** 降级期写操作守卫：degraded → 一律拒绝（只读保护）。 */
    fun allowWrite(op: Op, degraded: Boolean): Boolean = !degraded
}
