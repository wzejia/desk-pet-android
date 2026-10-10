package com.xiaoduo.deskpet.voice.pipeline

import com.xiaoduo.deskpet.voice.audio.VadDetector

/**
 * S51 断句续听窗（2026-10-10 P3-T2 自 VoiceSessionManager 聚合搬移：续听窗状态与判定
 * 逻辑收口于此，参数化注入不改行为；vad 副作用与派发出口仍由 VSM 接续，测试直引的
 * [VoiceSessionManager.CONTINUATION_TICKS] 常量与 internal 缝保持 VSM 面原位）。
 *
 * 病灶（09-18 板端实锤）：固定静音窗判停天生吃不掉长句字组气口——用户说
 * 「盘点当前有哪些档案」在「有哪」后换气，气口>判停窗即切尾，且切尾改变
 * LLM 工具路由（盘点被当查询调 c_archive_query）。三轮对照实验（轻声/正常音量
 * ×600/800ms 窗）钉死切尾位置=气口位置，与音量无关（更响反而切得更早）。
 *
 * 机制（Siri/小爱同款）：判停片段不即派发——累积 + 重启 VAD + 续听窗倒计时；
 * 窗内出现语音级块（用户续说）则冻结倒计时交 VAD 判起，下一片段到达重置窗口；
 * 连续静音耗尽窗口才合并派发。代价：每轮派发延迟 +[windowTicks] 块。
 *
 * 直通例外：按住松开 stopListening（松开=明确说完）与单测直注 onUtterance
 * 不经此路径，保持即时派发语义（例外路由在 VSM 面）。
 */
internal class ContinuationWindow(
    /** 续听窗块数（VSM 面 CONTINUATION_TICKS 注入：测试锚不搬，判停后追加静音观察期）。 */
    private val windowTicks: Int,
    /** 派发合法性门：仅聆听态可派发（VSM state 快照注入，防 cancel 后残跑派发）。 */
    private val isListening: () -> Boolean,
    /**
     * 冻结判定（仅 windowTicks>1 时被调）：块电平 ≥ 当前判停线（end 线）或 VAD 判起中。
     * 冻结线必须与判停同域（end 线 0.03）而非判起线 0.05：轻声续说（0.03~0.05 频段）
     * 若被当静音倒数，会在用户开口说档案名时照常派发+停 VAD，整段丢弃（板端 17:00 实锤）。
     * S55（2026-09-21 双板实证）：噪声环境电平 0.19~0.29（判停线 0.03 的 7~10 倍）下
     * 冻结永不解除 → hold 无限累积（实测 parts=8/66s 零派发）=「说完不回答」根因。
     * 窗=1（暂关续听）时冻结短路——无条件倒数，判停后必派发；窗>1 恢复时冻结语义回归。
     */
    private val frozen: (lvl: Double) -> Boolean,
    private val logger: (String) -> Unit,
) {

    /** 续听片段累积（各片段均过 min_utterance 门，合并后交 ASR）。 */
    private val parts = ArrayList<ShortArray>()

    /** 续听窗剩余块数（0=无挂起；语音级块冻结，连续静音递减）。 */
    @Volatile
    private var ticksLeft = 0

    /** 各片段 maxLevel 最大值（合并事件诊断用，「一值定层」口径）。 */
    @Volatile
    private var maxLevel = 0.0

    /** 续听状态锁（routeBlock 消费线程 vs cancel 跨线程清理竞态收口）。 */
    private val lock = Any()

    /** 判停片段不即派发：累积 + 重置续听窗（vad 重启/判起降敏由 VSM 侧接续，原时序不变）。 */
    fun hold(ev: VadDetector.Event.Utterance) {
        synchronized(lock) {
            parts.add(ev.pcm)
            maxLevel = maxOf(maxLevel, ev.maxLevel)
            ticksLeft = windowTicks
        }
        logger(
            "S51 continuation hold: parts=${parts.size} " +
                "samples=${parts.sumOf { it.size }} window=$windowTicks blocks",
        )
    }

    /** 续听窗倒计时（仅聆听活块驱动，每块一次）：真静音耗尽→true（VSM 派发）；冻结/无挂起→false。 */
    fun tick(lvl: Double): Boolean {
        if (ticksLeft <= 0) return false
        if (windowTicks > 1 && frozen(lvl)) return false
        return synchronized(lock) {
            if (ticksLeft > 0) ticksLeft-- == 1 else false
        }
    }

    /**
     * 窗口耗尽：拼接片段成单条 utterance 交 VSM 派发（走 onUtterance 原派发语义，防双控
     * 不重复）。合法性门不过（窗未耗尽/无片段/已离聆听）即清账返 null。
     */
    fun takeForDispatch(): VadDetector.Event.Utterance? = synchronized(lock) {
        if (ticksLeft != 0 || parts.isEmpty() || !isListening()) {
            parts.clear()
            ticksLeft = 0
            maxLevel = 0.0
            null
        } else {
            val pcm = ShortArray(parts.sumOf { it.size })
            var off = 0
            for (p in parts) {
                p.copyInto(pcm, off)
                off += p.size
            }
            val ev = VadDetector.Event.Utterance(pcm, maxLevel)
            parts.clear()
            ticksLeft = 0
            maxLevel = 0.0
            ev
        }
    }

    /** 续听挂起全清（backToIdle 单点复位纪律，同 kwsPaused；新唤醒轮也走此清）。 */
    fun clear() = synchronized(lock) {
        parts.clear()
        ticksLeft = 0
        maxLevel = 0.0
    }
}
