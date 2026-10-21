# Spec：助手化 UI / 语音 / 输入 全面升级（S7 切片族）

> **【2026-08-06 注】S7 切片族已全部收口，S8 亦收口**。施工后两处拍板修订本 spec：
> ① H 组输入框视觉经 S8-1→08-06 定为「单圆角容器+内部横线」（结构分层不变，见 H 组后注）；
> ② E 组应答音重制为「哎,我在。」、唤醒后纯唤醒词重听（见 E 组后注）；
> ③ 多功能键停止档改 keyStopActive 状态源（见 H 组 L108 行内注）。其余 as-built 以代码为准。

## 来源

- **grilling 会话**：2026-08-03，A–H 组逐项确认（见对话 + `implementation-notes`）
- **领域共识**：`CONTEXT.md`（会话 / 消息 / 全屏语音模式 三术语）
- **架构决策**：`docs/adr/0002-session-sole-concept.md`（A 组）、`docs/adr/0003-voice-fullscreen-bargein.md`（D+E 组）
- **实证调研**：DeepSeek 手机版输入框（adb uiautomator）、平台上传端点（bb-browser 实测 `POST /ai-server/common/file/upload`）

## 范围

覆盖 9 项用户需求：#1 全屏语音模式 / #2 按住语音无反应（并入 H）/ #3 scrim / #4 设置位置 / #5 气泡 / #6 顶栏瘦身 / #7 barge-in / #8 图标 / #9 唤醒「我在」/ #10 聊天记录 vs 会话 / + 新增 H 输入框重构。

## 现状基线（grilling 查实，避免重复造轮子）

| 项 | 现状 | 证据 |
|---|---|---|
| #3 scrim 点关 | ✅ 已实现 | `ChatScreen` `gesturesEnabled = drawerState.isOpen`（已修 material3 1.2.1 短路坑） |
| #4 设置不在右上角 | ✅ 已满足 | 设置入口在 `SessionDrawer` 底部，PetStatusBar 无右上角设置 |
| #6 drawer+hamburger | ✅ 已有 | `ModalNavigationDrawer` + Menu 图标 |
| 会话删除 | ✅ 已实现 | `ChatViewModel.deleteSession`（busy 守卫+删数据+回落+异步删平台 thread） |
| 会话切换+关drawer | ✅ 已实现 | `onSwitchSession = { vm.switchSession(id); drawerState.close() }` |

> 以上项无需施工，spec 仅记录基线。

---

## 技术方案（按组）

### A. 会话管理（会话为唯一概念，废弃聊天记录页）

**改动点：**

1. **删除遗留**：`ChatHistoryScreen.kt`、`AppNavHost` 的 `CHAT_HISTORY` 路由、`SessionDrawerContent` 的「聊天记录」NavigationDrawerItem。
   - 保留 `ChatHistoryCodec`（它编解码 `List<ChatMessage>`，会话消息文件 `{id}.json` 仍用，仅命名历史遗留）。
2. **会话重命名（路线 C：本地权威 + best-effort 平台同步，对齐 `deleteSession` 模式）：**
   - `ChatSession` 加 `titleManual: Boolean = false`（手动命名标志）。
   - `ChatStore.saveMessages`：`if (!session.titleManual) session.copy(title = deriveTitle(...))` —— 手动命名不再被覆盖。
   - `ChatStore.renameSession(sessionId, title)`：改本地 title + 置 `titleManual = true` + 存索引。
   - `AguiClient.renameThreadForSession(sessionId, name)`：调平台 rename 端点（对称 `deleteThreadForSession`），fire-and-forget，失败只 log 不回滚。
   - `ChatViewModel.renameSession(sessionId, title)`：busy 守卫 + 本地改名 + 异步平台同步。
3. **入口范式 (b)**：`SessionDrawerContent` 会话项长按 → 弹**操作菜单**（重命名 / 删除），替代原「长按直接弹删除确认」。重命名用 `AlertDialog` + `OutlinedTextField`。

### B. UI 信息架构（顶栏瘦身）

**改动点：** `PetStatusBar`（`ChatScreen.kt`）：
- 去掉「小朵」`Text`（头像已代表身份）。
- 宠物头像 `AndroidView` 40dp → **32dp**。
- 保留状态文案（`待命/思考中…/回复中…/出错可重发`，有信息价值）。
- 整体 ~56dp → **~48dp**（vertical padding SpaceS 收紧）。
- **右上角预留**：D 组全屏语音模式入口将放在此（见 D）。

### C. 气泡改进（字号 / 高度 / 宽度 / 自动滚动 / markdown 渲染）

**改动点：** `BubbleView.kt` + `BubbleTextStrip.kt` + `PetOverlayService`（窗口宽度）：
- `textSize = 11f` → **13f**。
- `MAX_HEIGHT_DP = 74` → **100**。
- 显示层窗口宽 2×宠物(~384dp) → **~260dp**（`PetOverlayService` 显示层 LayoutParams 宽度参数）。
- **自动滚动**：`BubbleView` 设 `movementMethod = ScrollingMovementMethod.getInstance()`（或套 `ScrollView`），打字机 `typeTick` 每次 `text=` 后滚到底。程序控制，不受 `FLAG_NOT_TOUCHABLE` 约束。
- **markdown 渲染（反转当前剥离设计）**：`BubbleTextStrip` 从「剥离标记输出纯文本」改为「输出 `Spannable`」：
  - 基础集：**粗体**（`StyleSpan.BOLD`）/ *斜体* / `行内代码`（`TypefaceSpan.MONOSPACE` + 背景色）/ 列表项（`•` 前缀）。
  - **不做**：表格 / 代码块 / 多级标题（窄气泡装不下，去聊天页看完整 `MarkdownRenderer`）。
  - 流式 holdback 逻辑保留（处理半截 `**`/反引号），输出从纯文本改为 spanned。

### D. 全屏语音模式（复用语音链路，新 Composable）

**改动点：** 新增 `FullscreenVoiceScreen` Composable + 接线：
- **界面**：全屏深色背景（`#0D0D0D` 或微渐变）+ 居中 `PetView` **~3×**（帧高 ~520-576dp，保清晰，spritesheet 192×208 不放大到满屏）+ 相位驱动四态（listening→waiting / processing→review / speaking→talking）。
- **入口**：`PetStatusBar` 右上角加 mic 图标按钮（B 瘦身后空位）。
- **进入逻辑**：隐藏悬浮窗（`PetOverlayService` 隐藏双窗口）+ 订阅 `VoiceForegroundService` 唤醒事件（**共享 KWS，不另开**）+ `VoiceStateRouter` 路由到全屏载体。
- **回复呈现**：全屏模式 = TTS 语音 + 小朵 talking 动画（**无文字默认**）；设置项「全屏转写」可开 → 订阅对话流显示文字。
- **退出**：长按小朵 → 菜单（退出全屏 / 打开设置 / 隐藏）。
- **默认无转写**，对话状态反馈**全靠小朵精灵四态**（须验 review vs talking 用户可分辨）。

### E. 语音行为（唤醒「我在」+ barge-in 分阶段）

**#9 唤醒「我在」（顺序式）：**
- 预录短音频 `wozai.wav`（不走每次 TTS 合成，零延迟），放 `assets/` 或 `res/raw/`。
- `VoiceSessionManager` 唤醒流程插入确认步骤：唤醒 → 播「我在」(speaking，期间丢麦块) → 播完 → listening。
- **范围**：全屏 + 悬浮窗**两模式**都播（一致唤醒反馈）。

> **【2026-08-06 后注】应答音内容修订**：经小米/小布/小艺调研（原厂应答=语气词「哎」非陈述句朗读）
> + 两轮 13 候选真机试听，用户选定 e3「哎,我在。」(speed 0.95，去静音+峰值归一)；`wozai.wav` 已重制，
> 重制管线脚本在 `.scratch/wozai/`。另：唤醒/strip-empty 重听共用入口 `playAckThenListen()`（改应答音注意两个调用点）。

**#7 barge-in（分两阶段）：**
- **阶段 1 键盘打断（立即做）**：`ChatScreen` 输入框 `onValueChange` 首字符（非空）→ `cancel` 当前 `runChat` + 停 TTS + 停气泡打字机（音频+文字全停）。UI 事件，不依赖 AEC。
- **阶段 2 唤醒词打断（待 AEC spike）**：`/prototype` 真机验 speaking 时开 KWS + `VOICE_COMMUNICATION` 硬件 AEC 能否滤 TTS 回声（能打断 + 不误唤醒）。可靠→做；不可靠→降级（不做，或用「按住说话打断」替代）。**本 spec 不锁定阶段 2 实现，标注为 spike 后定。**

### H. 输入框重构（两层 + 声波纹 + 多功能键 + 上传，含 #2 修复）

**改动点：** 重构 `ChatScreen` 的 `InputBar`（当前单层 voiceMode pill / TextField）为**两层结构**（参照 DeepSeek 实测）：

> **【2026-08-06 后注】视觉形态两次修订**：S8-1 落地为两个独立圆角矩形 → 用户拍板观感不佳 →
> 定稿**单圆角矩形容器 + 内部横线分隔**（InputArea/FunctionBar 分层不变，仅视觉合一）。
> 证据 `docs/verification/c-inputbar-voice/text.png`，R3/S8-1 功能修复全保留。

```
┌─ 上层 InputArea ─────────────────────┐
│  语音模式：「点击说话」满宽触摸区      │  ← 点按开始/结束，结束时机归状态机
│  文字模式：输入框 placeholder          │
├─ 下层 FunctionBar ────────────────────┤
│  [上传文件]    ............  [多功能键]│  ← 多功能键：有内容=发送 / 无内容=切换语音/键盘
└───────────────────────────────────────┘
```

- **上层（InputArea）**：
  - 语音模式：**免按住模式（Tap-to-Talk，2026-10-18 拍板替换按住说话）**——`awaitEachGesture` +
    `waitForUpOrCancellation` 满宽触摸区，分流走 `VoiceTapRouter` 真值表（LISTENING→END / 其余→START），
    点一下开始、再点一下结束；结束时机归状态机（VAD 判停 / S51 续听 / 30s 超时），不绑人手松手，
    上滑取消语义退役。
  - **点按反馈（原按住反馈 #2 沿革）**：
    - **实时声波纹动画**：`Canvas` 绘制，由 `AudioCapture` 的 RMS 能量（`EnergyGate` 已有 RMS）驱动柱状/波形高度（聆听态显示）。
    - **文案**：IDLE 态「点击说话」/ 聆听态「正在聆听，点击结束」。
    - **haptic 震动**：点按触发 `Vibrator.vibrate`（短促）。
  - 文字模式：`TextField`，placeholder「请输入内容」。
- **下层（FunctionBar）**：
  - 右下角**多功能键**（**2026-08-04 用户增补停止档**，对齐 DeepSeek 生成中停止方块范式）：
    输出中（**keyStopActive = voiceActive ∪ VSM.state≠IDLE**，状态源派生——2026-08-06 修：原 voiceActive 边沿事件在 VM 晚建时错过 → 停止键有概率不显示）→ **停止键（方块）**，点击 = `vm.cancelCurrentTurn()`
    （停流式+停 TTS+停气泡+部分回复标 DONE，与 barge-in 阶段1 键盘首字符打断同源）；
    空闲+有内容 → 发送键；空闲+无内容+文字模式 → 语音切换键；空闲+语音模式 → 键盘切换键；
    上传中 → 禁用。停止后键回退原状态。
  - 多功能键**左侧**：**上传文件键**（图标按钮）。
- **上传文件（平台已实测支持）**：
  - 端点：`POST /ai-server/common/file/upload`，字段 `file`（multipart），类型**不限**（doc/docx/pdf/png/jpg/xlsx/mp3/zip/pptx 全接受）。
  - 响应：`{fileId, fileName, filePath, fileSize, fileSuffix}`。
  - 前端 `filesList` 管理（每文件 status: uploading/done）；上传后文件进列表，可删除。
  - ⚠️ **接入对话待查**：fileId 如何进 AG-UI 请求体（发送消息时带 `fileIds`/`attachments`？）——前端代码未加载到，**施工时发个真带文件消息抓包确认**。

### G. 应用图标

- 替换 `mipmap-*` launcher icon（设计稿待定，施工时换）。

---

## 数据模型变更

### ChatSession（加手动命名标志）

```kotlin
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleManual: Boolean = false,  // 新增：手动命名标志，true 时 saveMessages 跳过 deriveTitle
)
```
> `SessionIndexCodec` 同步加 `titleManual` 字段（向后兼容：缺省 false）。

### FileMeta（上传文件元数据，新增）

```kotlin
data class FileMeta(
    val fileId: String,      // 平台返回
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val fileSuffix: String,  // txt/docx/pdf/png...
    val status: UploadStatus, // UPLOADING / DONE / ERROR
)
```

---

## API / 端点

### 平台文件上传（已实测）

```
POST /ai-server/common/file/upload
Content-Type: multipart/form-data
Authorization: Bearer <token>   (复用 AG-UI token)
agentId: <agentId>

字段：file (multipart file)
响应 200: {code:200, data:{fileId, fileName, filePath, fileSize, fileSuffix}, msg:"操作成功"}
类型限制：无（全接受）
```

### AguiClient.renameThreadForSession（新增，对称 deleteThreadForSession）

```
平台 rename 端点（施工时实测确认路径，参照 Windows 版 /renameThread）
fire-and-forget：失败只 log，不回滚本地
```

---

## 业务逻辑（关键流程）

### 重命名流程
`长按会话 → 菜单「重命名」→ AlertDialog 输入 → VM.renameSession → 本地改 title+titleManual=true → 异步 renameThreadForSession(best-effort)`

### 全屏模式状态路由
`进全屏 → 隐悬浮窗 → VoiceStateRouter 切载体=FULLSCREEN → 唤醒事件 → VoiceSessionManager → 回复路由到全屏 PetView talking（非气泡）→ 退出 → 恢复悬浮窗载体`

### 唤醒「我在」顺序式
`唤醒词 → VoiceSessionManager 进 speaking(WOZAI) → 播 wozai.wav → 完 → listening → VAD → ASR → 对话`

### 键盘 barge-in
`输入框 onValueChange(非空) → VM.cancelCurrentTurn() → cancel runChat + 停 TTS + 停气泡 → 用户继续输入新内容`

### 上传流程
`点上传键 → 系统文件选择 → 选文件 → POST /ai-server/common/file/upload → fileId 进 filesList(DONE) → 发消息时带 fileId（接入方式待查）`

---

## 边界条件

| 场景 | 处理 |
|---|---|
| 重命名时 busy | 忽略（对齐 delete/new/switch 的 busy 守卫） |
| 手动命名后发新消息 | `saveMessages` 跳过 deriveTitle（titleManual=true），title 不被覆盖 |
| 平台 rename 失败 | 本地已改，只 log，不回滚（best-effort，对齐 delete 的 fire-and-forget） |
| 全屏模式时收到唤醒 | 共享 KWS，正常触发；回复路由到全屏载体 |
| 全屏模式 speaking 时精灵态 | talking；状态反馈全靠精灵（须验可辨） |
| 气泡内容超 100dp | 自动滚动跟随（程序控制，不要触摸） |
| markdown 半截标记 | BubbleTextStrip holdback 保留（闭合后再产出 spanned） |
| 上传大文件 | fileSize 无实测上限，施工时验；建议前端限大小（如 20MB）提示 |
| 上传中发消息 | filesList 有 uploading 时禁发或提示（参照 DeepSeek「文件上传中请稍候」） |
| barge-in 唤醒词 | 阶段 2 待 AEC spike；AEC 不可靠则降级不做（2026-08-04 拍板挂起） |
| 唤醒后 ASR 纯唤醒词（剥离后无有效字，如「小朵小朵。」） | 重播「我在」继续聆听（playAckThenListen），不静默回 IDLE——2026-08-06 修，原死路即「唤不醒」主诉元凶 |

---

## 待查项（施工时确认）

1. **上传 fileId 接入对话**：发消息请求体如何带 fileId（发个真带文件消息抓 `/ai-server/.../chat` 请求体确认）。
2. **平台 rename 端点路径**：参照 Windows版 `pet/chat/providers/agui_sessions.py` 的 `/renameThread`，施工时实测确认 Android 侧路径（可能 `/ai-server/.../renameThread`）。
3. **AEC 可靠性**：阶段 2 唤醒词 barge-in 的前置 spike。
4. **全屏转写设置项**：默认关，设置页加开关 + 全屏模式订阅对话流渲染文字。

---

## 验收标准

### A 会话
- 聊天记录页/路由/入口删除；侧栏无「聊天记录」项。
- 长按会话弹菜单（重命名/删除）；重命名后发新消息 title 不被覆盖。
- 重命名后平台 thread 同步（best-effort，查日志）。

### B UI
- 顶栏 ~48dp，无「小朵」文字，头像 32dp，状态文案在。

### C 气泡
- 字号 13sp / 高度 100dp / 宽度 ~260dp；内容超出自动滚到底。
- 粗体/斜体/行内代码/列表正确渲染（Spannable）；表格/代码块不渲染（去聊天页）。

### D 全屏语音
- 顶栏右上角入口进入全屏；悬浮窗隐藏；唤醒词触发对话；回复=小朵 talking+TTS 无文字（默认）。
- 长按弹菜单退出；退出后悬浮窗恢复。
- 小朵 ~3× 居中保清晰。

### E 语音
- 唤醒后播「我在」再 listening（两模式）；「我在」为预录音频（2026-08-06 起内容=「哎,我在。」e3）。
- 键盘输入首字符打断当前输出（音频+文字停）。

### H 输入框
- 单圆角矩形容器+内部横线（2026-08-06 定稿，原「两层独立矩形」观感被拍板否决）；按住=声波纹（RMS 驱动）+「松手发送 上移取消」+ 震动；上移取消生效。
- 多功能键：有内容=发送，无内容=切换。
- 上传文件：选文件→上传成功→fileId 进列表；类型不限。
- #2 修复：按住有震动+声波纹+视觉反馈。

### G 图标
- launcher icon 替换。

---

## 单测基线

- `ChatSession.deriveTitle` + titleManual 跳过：新增用例。
- `ChatStore.renameSession`：本地改名 + titleManual + 不覆盖。
- `BubbleTextStrip`：从剥离断言改为 spanned 输出断言（粗体/代码 span 存在）。
- 多功能键状态机：有内容/无内容切换逻辑。
- 上传响应解析：FileMeta from JSON。
- 单测只增不减（基线 274，skipped=0）。

---

## 下一步

生成施工清单：`/docs-construction docs/spec/assistant-ui-voice-input.spec.md`

> 建议切片：S7-a 会话重命名（A）/ S7-b 顶栏瘦身（B）/ S7-c 气泡（C）/ S7-d 全屏语音（D，最重）/ S7-e 语音行为（E）/ S7-f 输入框重构+上传（H，含 #2）/ S7-g 图标（G）。依赖序：A/B/C 独立可并行；D 依赖语音链路（S4/S5 已就绪）；H 依赖 A（输入框在 ChatScreen）；E#9 依赖 D（全屏模式）。
