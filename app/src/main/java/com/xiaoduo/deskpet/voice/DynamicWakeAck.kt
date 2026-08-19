package com.xiaoduo.deskpet.voice

import java.io.IOException

/**
 * 动态唤醒应答播放器（S9-3）：只持 [resolve] 闭包（[WakeAckResolver.resolve] 全链解析，
 * 决策链：缓存→厂商资产→wozai.wav→null），播放路径**零网络**——本类不得 import 任何
 * HTTP/云客户端（结构性守卫，critic 审查点；网络只存在于 WakeAckPrefetcher daemon 线程）。
 *
 * cancel 语义自 VoiceClientFactory.AssetWakeAck 逐字迁移（类已删，逻辑归宿在此）：
 * - 入口早退：cancel 先于开播到达（wozaiJob 已 cancel）时不再 resolve/出声；
 * - 注册后复查（critic P1-①）：cancel 在调用方 pre-check 之后、播放器注册之前到达的
 *   窗口，由 [isCancelled] 补枪——stop() 必打到 null player，补枪后 VoicePlayer
 *   stopped 标志持久，playBlocking 首写即抛，零出声；
 * - resolve() 落空（全链 miss）抛 IOException → 走 [VoiceSessionManager] 既有
 *   catch 降级「fallback to listening」，与现状播放失败行为一致（不吞整轮对话）。
 */
class DynamicWakeAck(private val resolve: () -> ByteArray?) : VoiceSessionManager.WakeAck {

    @Volatile
    private var player: VoicePlayer? = null

    override fun playBlocking(isCancelled: () -> Boolean) {
        // S9-3 回归守卫：入口早退——cancel 先于开播到达时不得再 resolve/出声
        //（删此 → 取消后仍出声/仍做磁盘读 → barge-in 立停语义破 → 真机 Case E 挂）
        if (isCancelled()) return
        val pcm = resolve() ?: throw IOException("no wake-ack source")
        val p = VoicePlayer()
        player = p
        try {
            // 注册后复查（critic P1-①，语义自 AssetWakeAck 迁移）：cancel 先于注册到达时
            // stop() 必打到 null player——isCancelled 补枪；VoicePlayer stopped 标志持久，
            // playBlocking 首写即抛，零出声
            if (isCancelled()) p.stop()
            p.playBlocking(pcm)
        } finally {
            if (player === p) player = null
        }
    }

    override fun stop() {
        player?.stop()
    }
}
