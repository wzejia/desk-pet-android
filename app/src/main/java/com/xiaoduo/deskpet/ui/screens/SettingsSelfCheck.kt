package com.xiaoduo.deskpet.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 链路自检（S1-tail，兼 U6 调试页）：一键跑「登录 → 首聊『你好』」全链路。
 * UI 实时显示 AguiClient 进度行与终态（首 token 延迟 / 回复字数 / 错误 msg），
 * 全程经 PetLogger 落文件日志（凭据与 token 不进日志）。
 */
@Composable
internal fun LinkSelfCheck(context: Context) {
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未自检") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = !running,
            onClick = {
                if (NetConfig.password(context).isBlank()) {
                    status = "请先在上方填写密码并保存端点配置"
                    return@Button
                }
                running = true
                status = "自检开始…"
                PetLogger.i("SelfCheck", "link self-check start")
                scope.launch {
                    // ROB-13：自检任何异常收敛为失败终态复位 running（防按钮永久禁用）
                    val outcome = guardedUi {
                        withContext(Dispatchers.IO) {
                            AguiClientFactory.create(
                                context,
                                logger = { msg ->
                                    PetLogger.i("AguiClient", msg)
                                    status = msg // Compose snapshot state 跨线程写安全
                                },
                            ).chat("你好")
                        }
                    }
                    status = when (outcome) {
                        is UiOutcome.Ok -> when (val result = outcome.value) {
                            is AguiClient.ChatResult.Success -> {
                                val latency = result.firstTokenLatencyMs?.let { "${it}ms" } ?: "未知"
                                PetLogger.i(
                                    "SelfCheck",
                                    "self-check ok, firstToken=$latency, reply=${result.text.length} chars, attempts=${result.attempts}",
                                )
                                PetLogger.i("SelfCheck", "reply full text: ${result.text}")
                                "成功：首 token $latency，回复 ${result.text.length} 字（尝试 ${result.attempts} 次）"
                            }
                            is AguiClient.ChatResult.Error -> {
                                PetLogger.e("SelfCheck", "self-check failed: ${result.message}")
                                "失败：${result.message}"
                            }
                        }
                        is UiOutcome.Failed -> {
                            PetLogger.e("SelfCheck", "self-check crashed: ${outcome.error.message}", outcome.error)
                            "失败：${outcome.error.message ?: "未知错误"}"
                        }
                    }
                    running = false
                }
            },
        ) { Text("发送『你好』测试链路") }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
    }
}

/**
 * 语音自检（S4）：按住说话跑「采麦→VAD→ASR→AG-UI→分句→TTS→播放+气泡 reveal」全链路。
 * RECORD_AUDIO 运行时权限走真实申请路径（ColorOS 禁 shell 授权）；进度经
 * VoiceSessionManager.statusListener 实时上屏，全程 PetLogger 落文件。
 */

@Composable
internal fun VoiceSelfCheck(context: Context) {
    var status by remember { mutableStateOf("按住说话进行语音自检") }
    var holding by remember { mutableStateOf(false) }
    val session = remember { VoiceClientFactory.session(context) }
    DisposableEffect(session) {
        session.statusListener = { status = it }
        onDispose { session.statusListener = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        PetLogger.i("VoiceCheck", "RECORD_AUDIO runtime permission granted=$granted")
        status = if (granted) "麦克风已授权，请再次按住说话" else "麦克风权限被拒，无法语音自检"
    }

    fun pressDown() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            PetLogger.i("VoiceCheck", "request RECORD_AUDIO runtime permission")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        holding = true
        PetLogger.i("VoiceCheck", "push-to-talk start")
        session.startListening()
    }

    fun pressUp() {
        if (!holding) return
        holding = false
        PetLogger.i("VoiceCheck", "push-to-talk release")
        session.stopListening()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { /* 点击无操作，语义在 onPress 按住/松开 */ },
            // Button 内建 clickable 会先消费 down 事件，detectTapGestures 默认
            // requireUnconsumed=true 将永远等不到——必须显式 false（真机实证 2026-07-30）
            modifier = Modifier.pointerInput(session) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressDown()
                    waitForUpOrCancellation()
                    pressUp()
                }
            },
        ) { Text(if (holding) "松开结束说话" else "按住说话") }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
    }
}

/**
 * 语音唤醒（S5）：KWS 常开开关（唯一拉起路径，禁止自启）+ 能量门控阈值显示 + 校准入口。
 * 权限走真实用户申请路径：RECORD_AUDIO 必须，POST_NOTIFICATIONS（API 33+）尽量要
 * （被拒服务照跑，通知栏不显示，任务管理器仍可见前台服务）。
 */
