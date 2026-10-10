package com.xiaoduo.deskpet.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.VoiceForegroundService
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.audio.AmbientCalibrator
import com.xiaoduo.deskpet.voice.kws.EnergyGate
import kotlinx.coroutines.launch

@Composable
internal fun KwsSection(context: Context) {
    var enabled by remember { mutableStateOf(Prefs.kwsEnabled(context)) }
    var gate by remember { mutableFloatStateOf(Prefs.kwsGate(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    fun startKws() {
        Prefs.setKwsEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceForegroundService::class.java),
        )
        enabled = true
        PetLogger.i("Settings", "KWS always-on enabled by user toggle")
    }

    fun stopKws() {
        Prefs.setKwsEnabled(context, false)
        context.stopService(Intent(context, VoiceForegroundService::class.java))
        enabled = false
        PetLogger.i("Settings", "KWS always-on disabled by user toggle")
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 通知权限被拒服务照跑（通知栏不显示，任务管理器仍可见前台服务）
        PetLogger.i("Settings", "POST_NOTIFICATIONS for KWS granted=$granted")
        startKws()
    }

    fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startKws()
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        PetLogger.i("Settings", "RECORD_AUDIO for KWS granted=$granted")
        if (granted) {
            requestNotifThenStart()
        } else {
            status = "麦克风权限被拒，无法开启唤醒"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        RowSwitch(
            title = "「小朵小朵」语音唤醒",
            subtitle = "前台服务常驻，音频仅本地识别；唤醒后免按键对话",
            checked = enabled,
            enabled = true,
        ) { checked ->
            if (checked) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotifThenStart()
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                stopKws()
            }
        }
        Text(
            text = "能量门控阈值：%.3f（环境音量低于此值整块不识别，防静音空转耗电）".format(gate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        val micMode = Prefs.micSourceMode(context)
        val micModeLabel = when (micMode) {
            Prefs.MIC_SOURCE_RECOGNITION -> "识别（绕开通话链）"
            Prefs.MIC_SOURCE_UNPROCESSED -> "原始（UNPROCESSED）"
            else -> "通话（默认，硬件 AEC）"
        }
        // S57：采麦源锁定只读——采麦源决定整条模拟增益拓扑，被拨离 mode 0 后底噪抬
        // 30~50 倍致判停全废（2026-09-21 生产事故：0.19 vs 09-18 的 0.005）。如需变更
        // 联系开发走 adb：run-as sed prefs + 重启 App。
        Text(
            text = "采麦源：$micModeLabel（已锁定；本项决定整条增益拓扑，漂移会破坏判停。变更请联系开发）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        KwsCalibration(context) { written -> gate = written }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
            )
        }
    }
}

/**
 * 环境音校准：采集 5 秒环境 RMS 分布 → 建议阈值（p95×2.5，公式见 EnergyGate）→ 一键写入。
 * KWS 常开中复用会话采麦流（单流纪律，防双开抢麦）；未开则临时开一条采满即停。
 */

@Composable
internal fun KwsCalibration(context: Context, onGateWritten: (Float) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf<Float?>(null) }
    var suggestedVad by remember { mutableStateOf(0.05) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = !running,
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    report = "请先授予麦克风权限再校准"
                    return@Button
                }
                running = true
                report = "采集中…请保持环境安静 5 秒"
                scope.launch {
                    // ROB-13：校准任何异常收敛为失败报告复位 running（防按钮永久禁用）
                    val outcome = guardedUi {
                        val rmsValues = collectAmbientRms(context, CALIBRATION_SECONDS)
                        if (rmsValues.isEmpty()) {
                            report = "未采到音频（检查麦克风权限或占用）"
                        } else {
                            val stats = EnergyGate.statsOf(rmsValues)
                            val s = EnergyGate.suggestThreshold(stats)
                            suggested = s
                            // S18：VAD 说话阈值同步建议（板子噪声底骑 0.05 线致判停失效）
                            val vadLevel = EnergyGate.suggestVadSpeechLevel(stats)
                            suggestedVad = vadLevel
                            report = "样本 ${stats.count} 块：中位 %.4f / p95 %.4f / 峰值 %.4f → 建议 %.3f"
                                .format(stats.median, stats.p95, stats.max, s, vadLevel)
                            PetLogger.i(
                                "Settings",
                                "kws calibration: n=${stats.count} median=${stats.median} " +
                                    "p95=${stats.p95} max=${stats.max} suggest=$s vad=$vadLevel",
                            )
                        }
                    }
                    if (outcome is UiOutcome.Failed) {
                        PetLogger.e("Settings", "kws calibration crashed: ${outcome.error.message}", outcome.error)
                        report = "校准失败：${outcome.error.message ?: "未知错误"}"
                    }
                    running = false
                }
            },
        ) { Text(if (running) "校准采集中…" else "环境音校准（5 秒）") }
        report?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
            )
        }
        suggested?.let { s ->
            Button(onClick = {
                Prefs.setKwsGate(context, s)
                Prefs.setVadSpeechLevel(context, suggestedVad)
                Prefs.setAutoCalibDone(context, true) // S29：手动干预后自动校准永久让位
                val written = Prefs.kwsGate(context)
                val vadWritten = Prefs.vadSpeechLevel(context)
                onGateWritten(written)
                PetLogger.i("Settings", "kws gate written: $written, vad level written: $vadWritten")
                Toast.makeText(
                    context,
                    "已写入：能量门 %.3f / 说话阈值 %.3f".format(written, vadWritten),
                    Toast.LENGTH_SHORT,
                ).show()
            }) { Text("写入建议阈值（门 %.3f + 说话 %.3f）".format(s, suggestedVad)) }
        }
    }
}

internal const val CALIBRATION_SECONDS = 5

internal suspend fun collectAmbientRms(context: Context, seconds: Int): List<Float> =
    AmbientCalibrator.collectAmbient(context, seconds)
