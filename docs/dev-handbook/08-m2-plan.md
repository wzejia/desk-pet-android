# 08 · M2 计划书：对话链路（providers + 气泡 + 键盘输入）

> **【2026-08-03 注】本文已被 plan 002 + 助手化切片（S0-S6）取代并交付完毕**，
> 实际交付形态为 Compose 聊天页 + 抽屉导航 + 多会话（非本文的 Views 体系/调试页路径）。
> 仅历史参考，勿按本文施工。
>
> 估时 2-3 人周（知识包 §9）。前置：偏差 A-D 裁决完毕。
> 契约字段级细节以知识包 §2 + `docs/PLATFORM_INTEGRATION.md` 为准，此处只列实现要点与防坑铁律。

## 目标

用户在悬浮窗/App 内通过键盘输入与小朵文字对话：气泡打字机显示 LLM 流式回复，
宠物按 CONVERSATION_VISUAL 映射切换状态（processing→review 等）。

## 任务拆解（建议 ticket 级）

### T1. providers 抽象层（复刻 `pet/chat/providers/base.py:34-91`）
- `LLMProvider.stream(): Flow<String>` / `ASRProvider.transcribe(bytes): String` /
  `TTSProvider.synthesize(): Flow<ByteArray>`
- **按协议分不按部署位置分**（教训 #46）：ASR/TTS 平台与本地同引擎只换 base_url 各一类；
  LLM 两种协议（Ollama NDJSON / AG-UI SSE）两类。

### T2. Ollama provider（prefer=local 主路径，M2 先做这条）
- `POST {base}/api/chat`（剥 `/v1`），body `{model, messages, think:false,
  options:{num_ctx:2048, num_predict:100}, stream:true}`，system 置 messages 首条
- **NDJSON 解析**（非 SSE）：逐行 JSON 取 `message.content`，`done==true` 终止
- `think:false` 必带（否则首 token 50s，教训 #22）；超时 connect 10s / read 120s

### T3. AG-UI provider（prefer=platform）
- 登录：`POST {login_url}` → 信封 code==200 取 36 字符 token，缓存 25min
- **必须走 443 反代** `https://10.0.18.166/ai-server/agent/stream`（:8080 直连 401 假象，教训 #38）
- 自签证书：白名单 TrustManager，勿全局放行（教训 #39）
- **增量发消息**：只发最新一条用户消息，id 全局唯一，重试复用同 id（教训 #25，违反=空回复率 50%）
- **流式消费三件套**（第一天就写进去，知识包「最该记住 5 条」#1）：
  ① 校验 RUN_FINISHED，干净断流显式报错（教训 #26）
  ② 累计 0 字 = 平台随机空 run（~20%），自动重试共 3 次（教训 #23）
  ③ MESSAGES_SNAPSHOT 兜底：取最后 assistant 文本，比已产出长则补尾部
- thread 管理四接口（createThreadId / threadHistory / renameThread / deleteThread 全 POST），
  注意 threadHistory 不按 agentId 过滤 + 有字面量 "agentId" 脏行（教训 #29）

### T4. 气泡 UI
- 窗口扩高 192×288（updateLayout 加 80dp，若偏差 A 已改 208）
- 样式按 §4.2：max-height 74px 裁掉不滚动、圆角 10px、rgba(255,255,255,0.92) 半透白底、
  字号 11px × font-scale、pre-wrap
- 打字机 40ms/字，新文本重置、流式 append 续打；`show_bubble=false` 时一切弹泡 no-op 并隐藏

### T5. 键盘输入
- 入口：长按菜单加「对话」项（依赖偏差 B 补做）或单击气泡区
- overlay 窗口切 FLAG_NOT_FOCUSABLE 开关弹出 IME（注意：输入完恢复 NOT_FOCUSABLE）
- 输入框 UI：气泡位下方临时 EditText 或独立小窗口

### T6. 状态机接线
- 对话态 → CONVERSATION_VISUAL：processing→review、回复中→talking、完成回 idle
- 错误语义按教训 #48：LLM 中途失败已输出部分照显示，error 与 done(partial) 区分

### T7. 配置面扩展（Prefs 加项）
- PROVIDER_PREFER / LOCAL_LLM_BASE_URL / LOCAL_LLM_MODEL(qwen3.5-9b:latest) /
  PLATFORM_AGENT_URL / PLATFORM_LOGIN_URL / PLATFORM_USERNAME / PLATFORM_PASSWORD /
  PLATFORM_AGENT_ID(chatAgent) / ENABLE_CHAT（§6.2 全 15 项按需落地）
- 默认值写死 BuildConfig = 内网生产地址（教训 #45「部署机零配置」）

### T8. 网络层公共件
- OkHttp：内网地址 Proxy.NO_PROXY 直连（教训 #28，系统代理卡死流式实测过）
- 响应信封统一 `{code,msg,data}`，code=500 的 msg 透传日志（可当排障文档，教训 #15）
- 超时/重试公共策略；错误日志必带 base_url（教训 #12）

## 验收草案（M2 收口时逐条取证）

1. prefer=local：键盘输入→气泡流式出字→宠物 review→talking→idle，首 token < 3s
2. prefer=platform：登录→创建 thread→流式对话→多轮记忆生效（不重发历史）
3. 空 run 自动重试可见于日志；拔网线/断流显式报错不伪装成功
4. 气泡开关关闭后全程无泡；缩放对气泡同样生效
5. 单测：NDJSON 解析器、SSE 解析器（含三件套分支）、增量消息构造
6. 真机（de61cd8）验收 + 文件日志含全链路 URL 与错误透传

## 明确不做（M2 边界）

- 语音（VAD/KWS/TTS/半双工）→ M3
- ROM 保活加固 → M4
- 对话历史 UI 列表（thread 管理接口先落 provider 层，UI 后议）
