package com.xiaoduo.deskpet.chat

/**
 * 多功能键状态机（纯逻辑零 Android import，JVM 可测）。
 * 真值表（spec H 节 + 2026-08-04 用户增补停止档，对齐 DeepSeek 生成中停止方块范式，
 * 优先级自上而下）：
 * 1. 输出中（busy=SENDING/STREAMING 或 voiceActive）→ STOP（方块）→ vm.cancelCurrentTurn()
 * 2. 上传中 → DISABLED（DeepSeek「文件上传中请稍候」式禁发/禁切换）
 * 3. 空闲 + 有内容 → SEND
 * 4. 空闲 + 无内容 + 文字模式 → TO_VOICE
 * 5. 空闲 + 无内容 + 语音模式 → TO_KEYBOARD
 * 停止后键回退原状态：输出为输入快照的纯函数，busy/voiceActive 消失自然回退，无独立状态。
 */
object FunctionKeyLogic {

    enum class Action { STOP, SEND, TO_VOICE, TO_KEYBOARD, DISABLED }

    fun resolve(
        busy: Boolean,
        voiceActive: Boolean,
        hasContent: Boolean,
        voiceMode: Boolean,
        uploadInFlight: Boolean,
    ): Action = when {
        busy || voiceActive -> Action.STOP
        uploadInFlight -> Action.DISABLED
        hasContent -> Action.SEND
        !voiceMode -> Action.TO_VOICE
        else -> Action.TO_KEYBOARD
    }
}
