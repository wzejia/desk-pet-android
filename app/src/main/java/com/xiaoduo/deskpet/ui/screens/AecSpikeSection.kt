package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.AudioProbe
import com.xiaoduo.deskpet.voice.VoiceClientFactory

/**
 * S11-3 AEC spike 重做（#7 阶段2「唤醒词打断」前置实验，ADR 0003：可靠→做，不可靠→降级）。
 *
 * 08-04 旧版为何是无效实验：旧版只每 200ms 强制 kwsPaused=false，但 VoiceStateRouter
 * 对 SPEAKING/PROCESSING 麦块恒 DROP——TTS 播放全程 KWS 根本没收到音频，「5.8min
 * 零误触发」是空证据，硬件 AEC 可靠性从未被证明（kwsPaused 只门 IDLE 分支，旁路它
 * 对 SPEAKING 是 no-op）。
 *
 * 本版才是真放行：开关直写 `session.spikeFeedKws`（调试会话级标志），SPEAKING 态麦块
 * 经 VoiceStateRouter 真实进 KWS decode，人工数日志判定：
 * - ① 误触发采集：播放全程不喊唤醒词，数 `keyword hit:`（带冒号）行（tag KwsEngine）
 *   条数 = TTS 回声误触发次数（注意排除 `keyword hit dropped (release in progress)` 行，
 *   naive grep 会误计；VOICE_COMMUNICATION 硬件 AEC 若可靠应为 0，尤其回复含
 *   「小朵」时是高危样本）；
 * - ② 真打断检出：播放中喊「小朵小朵」10 次，数 `keyword hit` 命中数（命中仅记日志，
 *   startListening 非 IDLE 早退守卫下不会真打断——本 spike 只取检出率，不打断是预期行为）。
 *
 * 有效性自检（08-04 空证据的直接对策）：观测期必须见 `kws stats` 行（tag KwsEngine）
 * 的 fed 在 SPEAKING 期间真实增长——fed 不涨 = spike 无效，结论作废重做。
 *
 * arm/disarm 语义：UI 初值读真态（remember 初值 = session.spikeFeedKws，再进设置页
 * 显示与 router 一致）；离开设置页组合退出 ≠ disarm——spike 流程要求跨导航保持 arm，
 * disarm 配对点只有两个：按钮 toggle / 进程死亡（内存态无持久化，自然归 false）。
 *
 * 门控：ApplicationInfo.FLAG_DEBUGGABLE（release 不可见）——项目 gradle 未开
 * buildFeatures.buildConfig，BuildConfig 不生成，先例见 DebugBubbleReceiver。
 */
@Composable
fun AecSpikeSection(context: Context) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val session = remember { VoiceClientFactory.session(context) }
    var bypass by remember { mutableStateOf(session.spikeFeedKws) }
    var probeArmed by remember { mutableStateOf(AudioProbe.armed) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
            bypass = !bypass
            session.spikeFeedKws = bypass
            PetLogger.i("AecSpike", "AEC spike feedKws -> $bypass")
        }) {
            Text(if (bypass) "停止 AEC 旁路（spike 进行中…）" else "① 误触发采集：开 KWS 旁路")
        }
        Text(
            text = "旁路期间 speaking 态麦块真实进 KWS 解码。发起一轮长回复语音对话（唤醒或按住说话），" +
                "TTS 播放全程勿喊唤醒词，事后数日志「keyword hit:」（带冒号，tag KwsEngine）条数 = 误触发次数" +
                "（排除「keyword hit dropped」行）。有效性自检：观测期「kws stats」的 fed 须在 SPEAKING 期间增长，否则 spike 无效。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
        Text(
            text = "② 真打断检出：旁路开启 + TTS 播放中喊「小朵小朵」10 次，数「keyword hit:」（带冒号）" +
                "命中数（命中只记日志不会真打断，属预期）。取完证请关闭旁路。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // S11-6 延迟取证探针：arm/disarm 配对点 = 本按钮 toggle / 进程死亡（内存态无持久化）
        Button(onClick = {
            probeArmed = !probeArmed
            AudioProbe.armed = probeArmed
            PetLogger.i("AudioProbe", "latency probe armed -> $probeArmed")
        }) {
            Text(if (probeArmed) "③ 延迟取证探针：关（armed，取证中…）" else "③ 延迟取证探针：开")
        }
        Text(
            text = "诊断「唤醒第一声迟到数秒」的延迟座位（只探不修）。开启后：安静期零日志；" +
                "喊「小朵小朵」3 声、每声间隔 10 秒。事后取日志 grep「rms=」：采集侧「cap rms=… lag=Nms」" +
                "（tag AudioProbe，lag=系统音频通路积压）与 KWS 侧「feed rms=…」（tag KwsEngine）" +
                "按时间戳对照——lag 大=系统通路积压；cap→feed 时差大=app 中段积压；" +
                "feed→「keyword hit:」时差大=decode 侧。取完证请关闭。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
    }
}
