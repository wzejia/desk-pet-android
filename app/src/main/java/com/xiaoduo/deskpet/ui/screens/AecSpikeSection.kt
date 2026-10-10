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
import com.xiaoduo.deskpet.voice.kws.AudioProbe
import com.xiaoduo.deskpet.voice.kws.KwsEngine
import com.xiaoduo.deskpet.util.PetLogger

/**
 * 设置页诊断段（原 S11-3 AEC spike 段）。spike ①②（SPEAKING 麦块旁路进 KWS + 误触发/
 * 检出率人工取证）已随 S12 唤醒词打断转正退役——对话两态麦块进 KWS 是生产默认行为
 * （VoiceStateRouter 恒 KWS），调试旁路失去存在意义。spike 史（08-04 空证据教训→
 * S11-3 重做双合格）见 docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md。
 *
 * 现存 ③ S11-6 延迟取证探针：诊断「唤醒第一声迟到数秒」的延迟座位（只探不修）。
 * arm/disarm 配对点 = 本按钮 toggle / 进程死亡（内存态无持久化，自然归 false）。
 *
 * 门控：ApplicationInfo.FLAG_DEBUGGABLE（release 不可见）——项目 gradle 未开
 * buildFeatures.buildConfig，BuildConfig 不生成，先例见 DebugBubbleReceiver。
 */
@Composable
fun AecSpikeSection(context: Context) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    var probeArmed by remember { mutableStateOf(AudioProbe.armed) }

    Column(modifier = Modifier.fillMaxWidth()) {
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
