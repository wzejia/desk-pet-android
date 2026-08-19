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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.VoiceClientFactory
import kotlinx.coroutines.delay

/**
 * S7-e AEC spike（#7 阶段2「唤醒词打断」前置实验，ADR 0003：可靠→做，不可靠→降级）。
 *
 * 实验原理：speaking 期 VSM 会 kwsPaused=true 暂停 KWS decode（防 TTS 自激）；本区块
 * 强制旁路该 flag（公开 var，零改动 VSM/服务），让 TTS 播放全程 KWS 解码，人工数日志判定：
 * - ① 误触发采集：播放全程不喊唤醒词，数「wake word hit」条数 = TTS 回声误触发次数
 *   （VOICE_COMMUNICATION 硬件 AEC 若可靠应为 0，尤其回复含「小朵」时是高危样本）；
 * - ② 真打断检出：播放中喊「小朵小朵」10 次，数命中（命中仅记日志，IDLE 守卫下不会
 *   真打断——本 spike 只取检出率，不打断是预期行为）。
 * 结论人工记入 BLOCKED.md 或阶段2 施工票，本区块只做旁路开关与操作说明。
 *
 * 门控：ApplicationInfo.FLAG_DEBUGGABLE（release 不可见）——项目 gradle 未开
 * buildFeatures.buildConfig，BuildConfig 不生成，先例见 DebugBubbleReceiver。
 */
@Composable
fun AecSpikeSection(context: Context) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val session = remember { VoiceClientFactory.session(context) }
    var bypass by remember { mutableStateOf(false) }

    // 旁路循环：VSM 对话期会反复把 kwsPaused 拉回 true（startListening/backToIdle），
    // spike 期间每 200ms 强制 false，保证 speaking 全程 KWS decode
    LaunchedEffect(bypass) {
        while (bypass) {
            if (session.kwsPaused) {
                session.kwsPaused = false
                PetLogger.i("AecSpike", "kwsPaused force-bypassed (AEC spike)")
            }
            delay(200)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
            bypass = !bypass
            PetLogger.i("AecSpike", "AEC spike bypass -> $bypass")
        }) {
            Text(if (bypass) "停止 AEC 旁路（spike 进行中…）" else "① 误触发采集：开 KWS 旁路")
        }
        Text(
            text = "旁路期间 speaking 也保持 KWS 解码。发起一轮长回复语音对话（唤醒或按住说话），" +
                "TTS 播放全程勿喊唤醒词，事后数日志「wake word hit」条数 = 误触发次数。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
        Text(
            text = "② 真打断检出：旁路开启 + TTS 播放中喊「小朵小朵」10 次，数「wake word hit」" +
                "命中数（命中只记日志不会真打断，属预期）。取完证请关闭旁路。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
