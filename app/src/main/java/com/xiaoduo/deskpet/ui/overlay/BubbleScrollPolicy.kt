package com.xiaoduo.deskpet.ui.overlay

/**
 * S39 气泡滚动跟随策略（纯 JVM，BubbleScrollPolicyTest 5 测）：打字机自动滚底与
 * 用户手动滚动的仲裁状态机。
 *
 * 语义：
 * - 初始贴底跟随：新内容（打字机 tick）到达即滚到底；
 * - 用户按下（拖动开始）：挂起自动跟随——否则 40ms 后下一 tick 立即拽底，用户拉不动；
 * - 松手：贴底（视口最后一行可见）→ 恢复跟随；未贴底 → 保持视口，新内容继续打字
 *   但不拽底，用户看完下滑回贴底即自动恢复跟随（下一次 dragEnd(atBottom=true)）。
 *
 * View 侧职责（BubbleView）：onTouchEvent DOWN→[onDragStart]、UP/CANCEL→[onDragEnd]；
 * 拖动滚动本体交给 ScrollingMovementMethod（窗口可触摸后系统级可用）。
 */
class BubbleScrollPolicy {

    private var dragging = false
    private var pinnedToBottom = true

    /** 用户按下：进入手动接管（不跟随）。 */
    fun onDragStart() {
        dragging = true
        pinnedToBottom = false
    }

    /** 用户松手：按视口是否贴底裁决是否恢复跟随。 */
    fun onDragEnd(atBottom: Boolean) {
        dragging = false
        pinnedToBottom = atBottom
    }

    /** 打字机 tick 到达时是否自动滚底。 */
    fun shouldAutoFollow(): Boolean = !dragging && pinnedToBottom
}
