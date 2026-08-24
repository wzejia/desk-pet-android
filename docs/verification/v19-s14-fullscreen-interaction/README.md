# v19 S14 全屏语音页交互改版 +「我在」可视化（2026-08-24）

> 起因（用户报）：应声词「我在」无气泡无动作；全屏页 caption/文字区位置差、三个点不明显。
> 流程：问题盘点（代码实证四层根因）→ 真机调研豆包/通义实拍（`.scratch/research-voice-ui/`）→
> 逐项拍板 → HTML 原型 v1-v6 迭代验收（`designs/xiaoduo-motion-preview/小朵全屏语音页改版预览.html`）→
> TDD 施工（CONSTRUCTION.md S14-1~4）。

## 拍板约束

- 「我在」纯现场反馈，**不进消息流**
- 只改全屏页文字区（悬浮窗气泡本体不动）
- 打断+退出双键**抽屉式**（默认只露把手，点把手/上滑展开）
- **双退出入口保留**（左上 ✕ + 抽屉红叉）
- 抽屉键无文字说明、灰底统一、图标加大（豆包风）

## 改动面

| 文件 | 改动 |
|---|---|
| `voice/VoiceSessionManager.kt` | playAckThenListen：`bubble.show("我在")` + onBubbleShown 兜底；wozai 正常播完进聆听前 `bubble.hide()`（收泡三保险：正常收/cancel 收/策略 12s 兜底） |
| `ui/screens/FullscreenVoiceScreen.kt` | 三段式重排（顶部栏左 ✕+右字幕开关进场写回 Prefs / 宠物中心 42% / 状态区下 1/4）；caption 17sp + wakeAck 分支「我在」19sp 青色；聆听 7 柱声波 / 思考三点跳动；底部抽屉（把手一体移动+双键）；文案更新 |

## 证据

### 1. 单测 457 → **460 全绿 skipped=0**（+3 新增零删除）；assembleDebug 绿

- 新增（VoiceSessionManagerWakeAckTest）：`wozaiBubbleShownOnWakeAndHiddenOnCompletion` /
  `wozaiBubbleHiddenOnPlayFailureFallback` / `wozaiBubbleNotDoubleHiddenOnCancel`
- 适配（VoiceSessionManagerWakeInterruptTest ×2）：气泡快照点后移至 wozai 正常收尾后——
  S14-1 应声泡完成收 hide 属预期新行为，「残跑零追加」不变量保持锁定
- 第一轮红→绿实证：3 新测先红（无实现）→ 实现后绿；cancel 用例暴露 hideLater 策略
  尾事件属正常（断言收窄为 hide 恰一次）

### 2. 模拟器截图（emulator-5554）

- `fs-idle-drawer-closed.png`：三段式——左上 ✕ / 右上「字幕」/ 宠物中心 ~42% /
  caption「说『小朵小朵』开始对话」17sp 位于下 1/4（dump 实测 y 1942-1998/2400 ≈ 81%）/
  底部仅露抽屉把手
- `fs-drawer-open.png`：抽屉展开——把手在面板顶缘、灰底打断键（IDLE 置灰 alpha 0.35 ✓
  门控正确）+ 红叉退出键

- `fs-idle-pet-062.png`：真机首轮反馈修复后——宠物宽改 0.62×屏宽（原 440dp 钳制在 360dp 真机
  占屏高 49% 压字幕区）；字幕改尾部跟随（takeLast 200 + …，原 maxLines+Ellipsis 只露开头）+ 限 4 行
- `chat-markdown-shared.png`：字幕升级为富文本+滑动查看（用户 08-24 提需）——MarkdownContent
  抽取共享（ChatScreen −231 行 → ui/components/），聊天页回归实证（粗体/列表渲染原样）；
  全屏字幕=共享富文本 + 112dp 限高滚动 + 贴底跟随（上滑 48dp 阈值外不打断）

### 3. 真机语音验收 ✅（2026-08-24 用户现场全过）

唤醒 → 悬浮窗「我在」气泡（播完即收）+ 全屏「我在」青色大字 / 聆听声波跟手（卡顿已修）/
思考三点 / 说话期打断键可点 / 字幕富文本+滑动查看 / 白屏修复（旋转·键盘·通知回拉不闪白）。

### 4. 概率性白屏销案（08-18 观察票，2026-08-24 第一性原理排查）

- **根因**（三层拆解→两机制实证）：A. Manifest 无 configChanges → 配置变化（旋转/IME/深色/字体）
  即销毁重建 Activity，Compose 树全拆 = 白闪（真机落盘：08-18 当天 10 次 onCreate 无进程重启、
  多次夹在两次进全屏之间；08-24 进全屏 4s 后重建）；B. standard launchMode + 三处 NEW_TASK
  拉起点（悬浮窗菜单×2/KWS 通知）可叠加重复实例，配对断裂（08-24 双 enter 无 exit）。
- **修复**：`launchMode="singleTask"` + configChanges 13 项；MainActivity.onNewIntent 加取证日志。
- **模拟器对照实验**：修复前全屏中强制旋转 → 必现「exit→onCreate→enter」churn；修复后同操作
  **零 churn**（日志干净，横屏截图内容完好）；NEW_TASK 重复拉起 → onNewIntent 复用实例无 onCreate。
- 遗留 edge：横屏全屏布局未优化（宠物过大压 caption）；ColorOS 杀后台冷启白屏属系统行为。
