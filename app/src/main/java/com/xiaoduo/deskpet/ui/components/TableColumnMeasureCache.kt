package com.xiaoduo.deskpet.ui.components

/**
 * 表格列宽测量缓存判定（审计 ocr[310]）：
 * MarkdownRenderer.Table.colMaxUnits 是计算属性，每次访问全量重扫 H 列×(R+1) 行并分配连接串；
 * 原渲染每格访问一次 → 每次重组 O(H·(R+1)) 次重测，流式逐 token 重组放大为卡顿源。
 * 判定：同测量键（block 内容，data class equals）→ 复用缓存；键变化/首测 → 重测一次并记键。
 */
internal class TableColumnMeasureCache {
    private var cachedKey: Any? = null
    private var cachedUnits: List<Int>? = null

    /** 键相同且有缓存 → 返回缓存列宽（[measure] 不调用）；否则重测一次并更新缓存。 */
    fun unitsFor(key: Any, measure: () -> List<Int>): List<Int> {
        val cached = cachedUnits
        if (cached != null && cachedKey == key) return cached
        val fresh = measure()
        cachedKey = key
        cachedUnits = fresh
        return fresh
    }
}
