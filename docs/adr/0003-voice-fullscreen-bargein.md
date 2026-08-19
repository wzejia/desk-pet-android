# ADR 0003: 全屏语音对话模式 + 唤醒「我在」+ barge-in 分阶段（grilling 2026-08-03）

## 状态

已接受（D/E 组逐项 grill 确认）

## 背景

新增全屏语音对话模式（#1）、唤醒后说「我在」（#9）、barge-in 打断（#7）。现有已有完整唤醒词语音链路
（`VoiceForegroundService` KWS 常开 + `VoiceSessionManager` 四态半双工 + 悬浮窗气泡回复）。新需求须与
现有链路共存，避免双 KWS 抢麦、双小朵同屏、状态混乱。半双工现有 speaking 时丢麦块 + KWS pause
（防 TTS 自激），barge-in 的唤醒词打断与此冲突，须评估 AEC。

## 决策

1. **全屏语音模式复用现有链路**（#1）：复用 `VoiceSessionManager`/KWS/VAD/ASR/TTS（S4/S5 已验证不动）；
   进入全屏**隐藏悬浮窗**（避免双小朵）；**共享 KWS**（`VoiceForegroundService` 已常开，全屏订阅唤醒事件）；
   回复按活跃载体路由（扩 `VoiceStateRouter`）：全屏＝纯TTS+小朵talking（无文字默认，设置可开转写），
   悬浮窗＝气泡文字+TTS（不变）。入口＝ChatScreen 顶栏右上角（顶栏瘦身后空位）；退出＝长按小朵弹菜单。
   界面：全屏深色背景 + 居中小朵 ~3×（保清晰，spritesheet 帧 192×208 不放大到满屏）。
2. **唤醒「我在」顺序式**（#9）：范围＝全屏+悬浮窗两模式（一致唤醒反馈）；顺序式 唤醒→播「我在」完→
   listening（对齐半双工 speaking 丢麦块，不赌 AEC）；「我在」用预录短音频（不走每次 TTS 合成）。
3. **barge-in 分两阶段**（#7）：阶段1 **键盘打断（立即做）**——输入框首字符→`cancel runChat`+停TTS+停气泡
   （音频+文字全停），UI 事件不依赖 AEC；阶段2 **唤醒词打断（待 AEC spike）**——speaking 时解除 KWS
   pause+开麦检测唤醒词需 AEC 滤 TTS 回声，先真机 spike 验证（能打断+不误唤醒），可靠→做，不可靠→
   降级（不做或用「按住说话打断」替代）。

## 备选与取舍

- 全屏模式独立一套 KWS/语音：双 KWS 抢麦+双小朵，否决。
- 「我在」并行式（边播边听）：需 AEC，效果不确定，否决（顺序式）。
- 唤醒词打断硬上：TTS 回声误触发 KWS（尤其回复含「小朵」），否决，改 AEC spike 后定。
- 小朵占满全屏：spritesheet 192×208 放大 4-5× 模糊，否决（~3× 保清晰）。

## 后果

- **新增**：全屏语音模式 Composable + 顶栏入口 + 长按退出菜单；`VoiceStateRouter` 扩展多载体路由；
  唤醒「我在」预录音频 + `VoiceSessionManager` 插入确认步骤；键盘打断（cancel+停输出）。
- **待办**：AEC spike（`/prototype` 真机验 speaking 时 KWS+AEC），决定唤醒词打断去留。
- 全屏与悬浮窗模式**互斥**（进全屏隐悬浮窗），KWS **共享**不重复。
