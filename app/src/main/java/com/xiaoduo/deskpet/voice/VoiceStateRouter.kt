package com.xiaoduo.deskpet.voice

/**
 * 半双工四态麦块路由 + 唤醒词前缀剥除（纯逻辑，JVM 可测）。
 *
 * 依据：对账表状态机节（= 知识包 §3.5）+ S12 唤醒词打断（2026-08-20 转正）：
 * - 麦块分发：IDLE→KWS / LISTENING→VAD / SPEAKING+PROCESSING→KWS
 *   （S11-3 spike 真机双合格后转正式产品行为：TTS 播放 4min 误触发 0——硬件 AEC
 *   把回声压到能量门以下；播放中真声检出 8/10 达标。spikeFeedKws 调试开关随本票
 *   退役，空证据史见 docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md）
 * - KWS pause flag：只门 IDLE 分支（对话期回 IDLE 不即解 KWS）；SPEAKING/PROCESSING
 *   不受其影响——唤醒词打断正是靠这两态持续进 KWS 才可达
 * - 唤醒词前缀兜底：ASR 文本若以 小朵小朵/小度小度/你好小朵/小朵/我在 开头则剥除
 * - S54 多唤醒/打断词：KWS 命中后按词组路由（[decide]）——唤醒组全态动作、打断组
 *   仅对话两态、spike 词恒观测丢弃；动作与唤醒同构（Q3=b 打断并续听）
 */
class VoiceStateRouter {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    /** 麦块去向。 */
    enum class Sink { KWS, VAD, DROP }

    /** S5 挂点：对话期间置 true（只门 IDLE 分支；SPEAKING/PROCESSING 恒进 KWS 不受其门）。 */
    @Volatile
    var kwsPaused: Boolean = false

    /** 当前态麦块去向（四态分发表）。 */
    fun route(state: State): Sink = when (state) {
        State.IDLE -> if (kwsPaused) Sink.DROP else Sink.KWS
        State.LISTENING -> Sink.VAD
        // S12 回归守卫：SPEAKING/PROCESSING 恒 KWS（唤醒词打断的声学前提，spike 双合格
        // 转正——删此回退恒 DROP → 对话期唤醒词不可达 → VoiceStateRouterTest 红）
        State.PROCESSING -> Sink.KWS
        State.SPEAKING -> Sink.KWS
    }

    /** KWS 命中动作。ACT=走现有 startListening(playWakeAck=true) 链路（Q3=b：打断并
     *  续听，与唤醒动作同构）；DROP=只落日志不动作。类级嵌套（companion 内跨文件
     *  VoiceStateRouter.KwsHitAction 解析失败，S54 编译实证）。 */
    enum class KwsHitAction { ACT, DROP }

    companion object {
        /** 对账表：["小朵小朵", "小朵", "小度小度"]，长前缀优先（小朵小朵 先于 小朵 命中）。
         *  S40 补「我在」（2026-09-14 用户拍板）：应答回声残余经回灌后 ASR 常转出「我在。」
         *  ——剥空后走 onlyWakeWord 回 IDLE（幽灵轮消失）；「我在，查询档案」→「，查询档案」
         *  话头更净。已知代价：指令恰以「我在…」开头会被误剥（命令式对话极少见，对账表补注 5）。
         *  S54 补「你好小朵」（2026-09-21 grilling 拍板 Q1=①）：升级为声学唤醒词
         *  （keywords.txt 同步加行），ASR 文本话头同步剥除，与声学行为对称。 */
        val WAKE_PREFIXES = listOf("小朵小朵", "小度小度", "你好小朵", "小朵", "我在")

        // ---- S54 KWS 命中词路由（grilling 拍板 2026-09-21，用户逐项确认）----

        /** 唤醒组：全态动作（IDLE 唤醒 / SPEAKING+PROCESSING 打断并续听=S12 链路 /
         *  LISTENING 重唤醒=S33）。 */
        val WAKE_WORDS = listOf("小朵小朵", "你好小朵", "小度小度")

        /** 打断组：仅对话两态动作（Q5=b），IDLE/LISTENING 命中丢弃——防闲聊「好了」
         *  把小朵喊醒（Q6=a）。
         *  S64（2026-09-23 用户拍板）：打断词「好了/停下」已从 keywords.txt 禁用
         *  （「好了」阈值抬到 0.5 后仍误报体质，干脆全组禁用）——引擎不再识别，
         *  本表保留为防御性墓碑（若日后加回引擎行，路由语义照旧待复活拍板）。
         *  注意：打断能力本身未消失——唤醒组在 SPEAKING/PROCESSING 命中即打断
         *  （S12 链路），说「小朵小朵」仍可打断播报；双击打断亦在。 */
        val INTERRUPT_WORDS = listOf("好了", "停下")

        /** Spike 观测词（Q7）：恒丢弃只留日志。
         *  S63（2026-09-21 用户拍板）：「停」已从 keywords.txt 禁用（单音节误报体质，
         *  噪声/连续语流易越线）——引擎不再识别，本表保留为防御性墓碑（若日后再加回
         *  引擎行，路由仍安全丢弃待转正）。转正/复活流程同 Q7：真机双合格 + 用户拍板。 */
        val SPIKE_WORDS = listOf("停")

        /** S54 命中词路由决策（纯逻辑 JVM 可测；调用点 VoiceForegroundService.onWake）。
         *  未登记词默认 ACT=保持 S12 起的既有语义（keywords.txt 与组表漂移时不静默失效）。 */
        fun decide(keyword: String, state: State): KwsHitAction = when {
            keyword in SPIKE_WORDS -> KwsHitAction.DROP
            keyword in INTERRUPT_WORDS -> when (state) {
                State.SPEAKING, State.PROCESSING -> KwsHitAction.ACT
                else -> KwsHitAction.DROP
            }
            else -> KwsHitAction.ACT
        }

        /** 剥除 ASR 文本开头的唤醒词（可叠加出现则循环剥），剥后 trim 首尾空白。 */
        fun stripWakePrefix(text: String): String {
            var t = text.trim()
            var again = true
            while (again) {
                again = false
                for (p in WAKE_PREFIXES) {
                    if (t.startsWith(p)) {
                        t = t.removePrefix(p).trim()
                        again = true
                        break
                    }
                }
            }
            return t
        }
    }
}
