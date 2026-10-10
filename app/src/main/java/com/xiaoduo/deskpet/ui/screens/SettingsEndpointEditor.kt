package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.wakeack.WakeAckPrefetcher

@Composable
internal fun EndpointEditor(context: Context) {
    var platformBase by remember { mutableStateOf(NetConfig.platformBase(context)) }
    var asrBase by remember { mutableStateOf(NetConfig.asrBase(context)) }
    var ttsBase by remember { mutableStateOf(NetConfig.ttsBase(context)) }
    var account by remember { mutableStateOf(NetConfig.account(context)) }
    var password by remember { mutableStateOf(NetConfig.password(context)) }
    var mode by remember { mutableStateOf(NetConfig.mode(context)) } // 兼容旧
    var llmMode by remember { mutableStateOf(NetConfig.llmMode(context)) }
    var asrMode by remember { mutableStateOf(NetConfig.asrMode(context)) }
    var ttsMode by remember { mutableStateOf(NetConfig.ttsMode(context)) }
    var cloudBase by remember { mutableStateOf(NetConfig.cloudBase(context)) }
    var cloudKey by remember { mutableStateOf(NetConfig.cloudKey(context)) }
    var cloudLlm by remember { mutableStateOf(NetConfig.cloudLlmModel(context)) }
    var cloudAsr by remember { mutableStateOf(NetConfig.cloudAsrModel(context)) }
    var cloudTts by remember { mutableStateOf(NetConfig.cloudTtsModel(context)) }
    var cloudVoice by remember { mutableStateOf(NetConfig.cloudTtsVoice(context)) }
    var asrProv by remember { mutableStateOf(NetConfig.asrCloudProvider(context)) }
    var ttsProv by remember { mutableStateOf(NetConfig.ttsCloudProvider(context)) }
    var volcKey by remember { mutableStateOf(NetConfig.volcSpeechKey(context)) }
    var volcSpeaker by remember { mutableStateOf(NetConfig.volcTtsSpeaker(context)) }
    var aliyunKey by remember { mutableStateOf(NetConfig.aliyunSpeechKey(context)) }
    var aliyunVoice by remember { mutableStateOf(NetConfig.aliyunTtsVoice(context)) }
    var aliyunTtsModel by remember { mutableStateOf(NetConfig.aliyunTtsModel(context)) }
    var aliyunAsrModel by remember { mutableStateOf(NetConfig.aliyunAsrModel(context)) }
    var volcAsrModel by remember { mutableStateOf(NetConfig.volcAsrModel(context)) }
    var volcTtsModel by remember { mutableStateOf(NetConfig.volcTtsModel(context)) }
    var volcTtsResource by remember { mutableStateOf(NetConfig.volcTtsResource(context)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("各模态独立选择：平台（内网）/ 云端 API（OpenAI 兼容）", style = MaterialTheme.typography.labelLarge)
        @Composable
        fun modalityRow(label: String, get: () -> String, set: (String) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f))
                RadioButton(selected = get() == NetConfig.MODE_PLATFORM, onClick = { set(NetConfig.MODE_PLATFORM) })
                Text("平台")
                Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                RadioButton(selected = get() == NetConfig.MODE_CLOUD, onClick = { set(NetConfig.MODE_CLOUD) })
                Text("云端")
            }
        }
        modalityRow("LLM 对话", { llmMode }) { llmMode = it }
        modalityRow("ASR 识别", { asrMode }) { asrMode = it }
        modalityRow("TTS 合成", { ttsMode }) { ttsMode = it }
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        if (llmMode == NetConfig.MODE_CLOUD || asrMode == NetConfig.MODE_CLOUD || ttsMode == NetConfig.MODE_CLOUD) {
            OutlinedTextField(
                value = cloudBase, onValueChange = { cloudBase = it },
                label = { Text("云端 Base URL（如 https://dashscope.aliyuncs.com/compatible-mode/v1）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudKey, onValueChange = { cloudKey = it },
                label = { Text("API Key（sk-xxx）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudLlm, onValueChange = { cloudLlm = it },
                label = { Text("LLM 模型（如 qwen-plus / gpt-4o-mini）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudAsr, onValueChange = { cloudAsr = it },
                label = { Text("ASR 模型（如 whisper-1）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudTts, onValueChange = { cloudTts = it },
                label = { Text("TTS 模型（如 gpt-4o-mini-tts）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudVoice, onValueChange = { cloudVoice = it },
                label = { Text("TTS 音色（如 alloy）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("ASR/TTS 云厂商（openai 用上方 base/key；volcengine/aliyun 用下方凭证）", style = MaterialTheme.typography.labelMedium)
            @Composable
            fun providerRow(label: String, get: () -> String, set: (String) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    RadioButton(selected = get() == "openai", onClick = { set("openai") }); Text("OpenAI")
                    Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                    RadioButton(selected = get() == "volcengine", onClick = { set("volcengine") }); Text("火山")
                    Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                    RadioButton(selected = get() == "aliyun", onClick = { set("aliyun") }); Text("阿里")
                }
            }
            providerRow("ASR 厂商", { asrProv }) { asrProv = it }
            providerRow("TTS 厂商", { ttsProv }) { ttsProv = it }
            if (asrProv == "volcengine" || ttsProv == "volcengine") {
                OutlinedTextField(
                    value = volcKey, onValueChange = { volcKey = it },
                    label = { Text("火山语音 X-Api-Key（UUID）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcSpeaker, onValueChange = { volcSpeaker = it },
                    label = { Text("火山 TTS 音色（如 zh_female_qingxinnvsheng_uranus_bigtts）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcAsrModel, onValueChange = { volcAsrModel = it },
                    label = { Text("火山 ASR 模型名（默认 bigmodel，与端点耦合）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcTtsModel, onValueChange = { volcTtsModel = it },
                    label = { Text("火山 TTS 模型（默认 seed-tts-2.0-standard）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcTtsResource, onValueChange = { volcTtsResource = it },
                    label = { Text("火山 TTS resource_id（默认 seed-tts-2.0，与模型耦合）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (asrProv == "aliyun" || ttsProv == "aliyun") {
                OutlinedTextField(
                    value = aliyunKey, onValueChange = { aliyunKey = it },
                    label = { Text("阿里 dashscope key（sk-...，与 LLM 同一个）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunTtsModel, onValueChange = { aliyunTtsModel = it },
                    label = { Text("阿里 TTS 模型（cosyvoice-v2 / qwen-audio-3.0-tts-flash）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunAsrModel, onValueChange = { aliyunAsrModel = it },
                    label = { Text("阿里 ASR 模型（qwen-audio-3.0-asr-flash-streaming / paraformer-realtime-v2）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunVoice, onValueChange = { aliyunVoice = it },
                    label = { Text("阿里 TTS 音色（cosyvoice: longxiaochun_v2；qwen-audio: longanfengyue）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        }
        if (mode == NetConfig.MODE_PLATFORM || llmMode == NetConfig.MODE_PLATFORM || asrMode == NetConfig.MODE_PLATFORM || ttsMode == NetConfig.MODE_PLATFORM) {
        OutlinedTextField(
            value = platformBase, onValueChange = { platformBase = it },
            label = { Text("平台地址（登录 :8080 / Agent 走 443 反代）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = asrBase, onValueChange = { asrBase = it },
            label = { Text("ASR 地址") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ttsBase, onValueChange = { ttsBase = it },
            label = { Text("TTS 地址") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = account, onValueChange = { account = it },
            label = { Text("账号") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码（无默认值，仅存本机）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        } // end if (MODE_PLATFORM)
        Button(onClick = {
            NetConfig.saveAll(
                context, platformBase, asrBase, ttsBase, account, password,
                mode = mode, llmMode = llmMode, asrMode = asrMode, ttsMode = ttsMode,
                cloudBase = cloudBase, cloudKey = cloudKey,
                cloudLlmModel = cloudLlm, cloudAsrModel = cloudAsr,
                cloudTtsModel = cloudTts, cloudTtsVoice = cloudVoice,
                asrCloudProvider = asrProv, ttsCloudProvider = ttsProv,
                volcSpeechKey = volcKey, volcTtsSpeaker = volcSpeaker,
                volcAsrModel = volcAsrModel, volcTtsModel = volcTtsModel, volcTtsResource = volcTtsResource,
                aliyunSpeechKey = aliyunKey, aliyunTtsVoice = aliyunVoice, aliyunTtsModel = aliyunTtsModel,
                aliyunAsrModel = aliyunAsrModel,
            )
            // S9-3：TTS 配置改存后预取唤醒应答音（「保存即合成」；caller 线程仅 CAS
            // 占位零 IO，合成在 daemon 线程，失败静默走兜底链）
            WakeAckPrefetcher.maybePrefetch(context.applicationContext)
            // 日志严禁打凭据（account/password/token 均属凭据，评审抽查点）
            PetLogger.i("Settings", "net config saved, platform=$platformBase asr=$asrBase tts=$ttsBase")
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        }) { Text("保存端点配置") }
    }
}

