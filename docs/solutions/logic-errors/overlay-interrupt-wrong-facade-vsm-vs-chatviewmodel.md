---
title: 悬浮窗双击打断调错门——文字轮不在 VSM 管辖，vsm.cancelCurrentTurn 对文字轮是静默 no-op
date: 2026-09-09
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt
problem_type: logic_error
component: interrupt
symptoms:
  - 双击判定日志触发（double tap -> cancelCurrentTurn）但回复播报/流式继续，打断无效
  - 打断时日志零反应（无 cancel voice turn 也无 cancel text turn）
  - 只有键盘 barge-in 能停文字轮（barge-in: cancel text turn）
root_cause: wrong_facade_call
resolution_type: code_fix
severity: high
tags: [cancel-current-turn, voice-session-manager, chat-viewmodel, text-turn, voice-turn, no-op-silent, facade-mismatch]
---

# 现象

S28 双击打断：手势判定层全部正常（日志 `overlay pet double tap -> cancelCurrentTurn`
触发多次），但小朵的文字轮回复（聊天页发消息→CloudLLM 流式→气泡+播报）继续跑完。
模拟器鼠标双击复测同样失败——排除手势问题后日志铁证：

```
12:42:17 chat send: 30 chars → CloudLLM POST stream ← 文字轮开始
12:42:19-23 double tap -> cancelCurrentTurn × 8      ← 双击全触发，无任何打断效果日志
12:42:30 barge-in: cancel text turn                  ← 用户打字才停（另一条链）
```

# 根因（调错门/Facade 不匹配）

打断有**两个门**：

| 门 | 管辖 | 文字轮播报期状态 |
|---|---|---|
| `VoiceSessionManager.cancelCurrentTurn` | 仅语音轮（停播放器/管线/回 IDLE） | vsm.state=**IDLE → when 分支 no-op，静默** |
| `ChatViewModel.cancelCurrentTurn` | 统一门控：语音轮转 VSM；文字轮 turnEpoch++/chatJob.cancel/停播/标已取消 | 正确处理 |

悬浮窗双击（S28）与长按菜单「打断」都调了**前者**（注释还声称「与菜单同通道」——两入口
确实同通道，但都接错了门）。文字轮不在 VSM 管辖，调用返回无异常但什么都没做。

# 修复

PetOverlayService companion 加静态桥（与既有 `PetOverlayService.instance` 反向引用模式
对称）：

- `@Volatile var chatInterrupt: (() -> Unit)?` + `fun requestInterrupt(context)`：
  有桥走桥（统一门控），无桥（VM 未建）回退 VSM（历史行为）；
- `ChatViewModel.init` 把自己的 `cancelCurrentTurn` 挂上桥；
- 双击与长按菜单 onInterrupt 改调 `requestInterrupt`。

# 教训

1. **「调用返回无异常」≠「生效」**——no-op 静默路径（IDLE 分支 `-> Unit`）要有日志，
   否则排查时表现为"调用过但世界没变化"，极难与"没调到"区分。
2. **同类动词 ≠ 同一扇门**：`cancelCurrentTurn` 在两个类里重名，语义只差一个字
   （"当前轮"的归属域不同）。接跨界入口时必须核对 Facade 的管辖域，不能凭方法名。
3. **验证打断必须用与用户相同的轮次类型**（语音轮 vs 文字轮）——语音轮验通过不蕴含
   文字轮通过。
