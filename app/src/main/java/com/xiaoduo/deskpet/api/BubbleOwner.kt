package com.xiaoduo.deskpet.api

/** 气泡通道所有者：文字（S2 聊天）/ 语音（S4 会话）/ 调试（DebugBubbleReceiver）。
 *  （spec §4.2 三刀：自 ui.overlay.BubbleOwnership.kt 原样下沉，2026-10-10；
 *  判定逻辑 BubbleOwnership 留 ui.overlay——UI 归属，本枚举是其共同消费契约。） */
enum class BubbleOwner { CHAT, VOICE, DEBUG }
