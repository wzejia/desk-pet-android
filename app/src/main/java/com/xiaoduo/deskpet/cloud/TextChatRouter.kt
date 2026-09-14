package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.chat.ChatMessage

/**
 * 文字轮 LLM 路由（2026-08-14 云端分支；08-18 仓库事故丢失后按 jadx 寄存器级伪码复原）：
 * llmIsCloud=true → OpenAI 兼容云端（本地历史拼上下文，无服务端会话）；
 * false → 平台 AguiClient（线程记忆）。两路统一产 [AguiClient.ChatResult]，调用侧下游零分叉。
 *
 * 取证（device-base.apk S9 与 emulator-base.apk S10 的 ChatViewModel$runChat$1 双源一致）：
 * - 分支挂点：runChat 协程内、onDelta 构造之后；onDelta 两路共用（epoch 防线内嵌）；
 * - 云端历史 = 消息列表 dropLast(1)（去本轮 assistant 占位）→ 滤 SYSTEM 与空文本 →
 *   Message(role.wire, text)；本轮 user 消息在列（倒数第二位）；systemPrompt=null；
 * - 适配：Success(text, firstTokenLatencyMs, attempts=1) / Error(message)——与语音侧
 *   VoiceSessionManager.voiceChat 同型（voice 包本票禁碰未收拢，两处镜像并存，后续票可提取共享）；
 * - fileRefs 仅平台路使用（云端模式附件不注入提示词；发送成功的挂载清场在调用侧统一下游照常执行；
 *   云端路 fileRefs 非空时经 [logger] 留痕一行——只打数量，禁打文件名/内容/密钥）。
 *
 * 纯 JVM 可测：模式读取与云端客户端构造经 lambda 注入（每轮调用时现取，设置改动下轮即生效），
 * Android 侧由 ChatViewModel 装配（logger 接 PetLogger）。
 */
class TextChatRouter(
    private val llmIsCloud: () -> Boolean,
    private val cloudClient: () -> OpenAIChatClient,
    private val platformClient: AguiClient,
    private val logger: (String) -> Unit = {},
) {

    /**
     * 执行一轮文字对话。[messages] 为当前会话完整本地流水（含本轮 user 与末尾 assistant 占位），
     * 仅云端路使用；平台路按 userText/wireId/threadKey/threadName/fileRefs 走 AG-UI 线程协议。
     */
    fun chat(
        messages: List<ChatMessage>,
        userText: String,
        wireId: String,
        threadKey: String,
        threadName: String?,
        fileRefs: List<Pair<String, String>>,
        onDelta: (String) -> Unit,
        /** S41 文字轮补接：TOOL_CALL_START 透传 toolCallName（密集架动画联动桥；
         *  云端路无 MCP，恒不触发）。 */
        onToolCallEvent: (String) -> Unit = {},
    ): AguiClient.ChatResult {
        if (!llmIsCloud()) {
            return platformClient.chat(
                userText,
                messageId = wireId,
                threadKey = threadKey,
                threadName = threadName,
                fileRefs = fileRefs,
                onDelta = onDelta,
                onToolCallEvent = onToolCallEvent,
            )
        }
        if (fileRefs.isNotEmpty()) {
            // 云端路附件不注入提示词（原实现行为，S9/S10 jadx 双源一致）——留痕防「附件没生效」困惑
            logger("cloud llm: ${fileRefs.size} attachment(s) not injected into prompt (text-only path)")
        }
        val history = messages
            .dropLast(1)
            .filter { it.role != ChatMessage.Role.SYSTEM && it.text.isNotBlank() }
            .map { Message(it.role.wire, it.text) }
        return when (val r = cloudClient().chat(history, null, onDelta)) {
            is OpenAIChatClient.Result.Success -> AguiClient.ChatResult.Success(r.text, r.firstTokenLatencyMs, 1)
            is OpenAIChatClient.Result.Error -> AguiClient.ChatResult.Error(r.message)
        }
    }
}
