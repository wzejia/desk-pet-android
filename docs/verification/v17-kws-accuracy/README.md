# v17 唤醒词准确率排查与修复证据链（2026-08-19 ~ 08-20）

> 来源票：S11-1（阈值回归）/ S11-5（文字轮云端分支）/ S11-6（延迟探针）/ S11-7（门控时间轴修复）。
> 结论速览：**三层独立根因叠加**——①事故回归阈值 0.5（已修+锚）②事故丢失文字轮云端分支（已复原+锚）
> ③能量门控「整块不喂不补静音」碎裂流式 KWS 时间轴（真凶，已修：挂起窗+gap-reset）。
> 验收：**一声唤醒 10/10**（修复前第一声 0/10、第二声 10/10）。

## 1. 用户主诉与终态

- 主诉（08-19）：「唤醒词唤醒识别准确率不高」+「检查是否支持唤醒词打断」。
- 终态（08-20 真机 de61cd8，S11-7 构建）：10 轮单声唤醒（日常音量/距离、间隔 ≥10s、不补喊）**10/10 命中**，
  命中即时（日志见 §5）。

## 2. 根因①：阈值 0.5 事故回归（S11-1）

- 石锤：`KwsEngine.kt:101` 实测 `keywordsThreshold = 0.5f`；08-10 已验证的 0.3（IDLE 态真机 10/10）
  在 08-18 仓库事故中丢失——常量无测试锚，转录重放静默回退。`git diff 103a496..HEAD -- app/` 为空亲证。
- 同案对比：08-10 三改动中 strip 修复/USAGE_ASSISTANT 有测试锚/APK 仲裁被捞回，阈值无锚丢失。
- 修复：`voice/KwsDefaults.kt` 常量承载 + `KwsDefaultsTest` 精确锚（改 0.5→红亲验）；
  CONTEXT.md / spec :71 / 对账表 :45 三处 0.5 假事实修正（corrigendum 体例）。

## 3. 根因②：文字轮云端分支事故丢失（S11-5）

- 症状：08-19 重装后文字轮发送失败「账号或密码未配置」，语音轮正常。
- 石锤：`AguiClient.login()` 报错行（设备日志 21:05-21:09 连续 8 次）；`ChatViewModel` 无云端分支，
  而 `CloudClientFactory.kt:14` KDoc 仍写着 ChatViewModel 是其用户；语音侧路由锚（VSM:70 `llmIsCloud`）幸存。
- 取证复原：jadx 反编译 device-base.apk（S9）与 emulator-base.apk（S10）双源逐段一致
  （产物入库 `docs/verification/forensic-s11-cloud-branch/`），按寄存器级伪码复原：
  分支挂点（onDelta 构造之后）、历史构建（dropLast(1)+滤 SYSTEM/空文本+role.wire 映射）、
  systemPrompt=null、适配 attempts=1、fileRefs 仅平台路。
- 锚：`cloud/TextChatRouter.kt` 纯逻辑下沉 + `TextChatRouterTest` 3 测（双 MockWebServer，
  云端路平台 0 请求/平台路云端 0 请求/500 透传），变异锚（恒走平台=事故丢失态）双红亲验。

## 4. 根因③（真凶）：门控丢弃语义碎裂 KWS 时间轴（S11-6 探针 → S11-7 修复）

### 4.1 排查路径（证伪序列，全部有日志/账目证据）

1. **keywords.txt 拼音序列**：排除（08-10 同文件 10/10；序列错会稳定不中而非「第一声必漏」）。
2. **阈值打分**：排除（大声贴脸第一声仍 0/10）。
3. **能量门拦首声**：一度疑似（fed 账目显示首声零过门块），被大声实验推翻。
4. **路由层残留**（kwsPaused/状态）：排除（backToIdle 无条件复位，日志状态链干净）。
5. **音频管道秒级延迟**：单声等待实验「迟到但有我在」一度指向它——**S11-6 探针证伪**：
   `cap lag=1-4ms`（AudioRecord 采集时间戳）、cap→feed 2-6ms，管道实时无积压。

### 4.2 真机制（S11-6 探针时间线，2026-08-20 15:07-15:09）

用户三声（时刻 15:07:30/15:07:50/15:08:10，秒级手记）：

```
15:07:29.633-30.336  cap/feed rms=0.04~0.13 lag=1ms   ← 第 1 声全程实时进 KWS，未中
15:07:49.635  cap rms=0.1100 lag=1ms                  ← 第 2 声开口
15:07:49.772  keyword hit: 小朵小朵 (#9)              ← 开口仅 ~140ms 即中：第 1 声冻结尾分被补完
15:08:09.2-10.0      第 3 声全程实时进 KWS，未中
15:09:37.658  keyword hit (#10)（前一馈块 rms=0.0185） ← 后续轻声的残留补完命中
```

机制：能量门「rms<gate 整块不喂且不补静音」→ ①喊词字间气口（100-300ms 低能块）被挖，
时间轴压缩拼接，transducer 打分被压到阈值 0.3 下；②深静默块不进流，波束里差一口气的
部分分永不被尾随静音冲刷（numTrailingBlanks 无帧可消费），冻结到下一声音事件补完——
表现为「第二声秒中」/「我在莫名迟到」。桌面 sidecar.py:184-197 取证：桌面同样只丢不补、
无 gap-reset——本修复是超越桌面 parity 的真机证据驱动增强。

### 4.3 修复（S11-7）

- **挂起窗**：过门后 8 块=800ms（与 VAD 判停 800ms 同源）内低于门仍喂真实块——字间气口弱音保留。
- **gap-reset**：连续低于门超窗 → 不喂（省电初衷保住）+ 对 KWS 流 reset 恰好一次（幂等沿，
  sentinel 入队保序，reset 仅 decode 消费线程执行）——冲刷冻结尾分。
- 常量锚：`KwsDefaults.GATE_HANGOVER_BLOCKS=8` + 锚点测试（8→7 红亲验）。
- 测试：KwsCoreHangoverTest 4 测 + 锚 1 测；双机制独立变异锚（删挂起窗 4 红 / 删 gap-reset 3 红）。

## 5. 验收证据（2026-08-20 16:02，S11-7 构建，真机 de61cd8）

用户报告 10 轮单声唤醒 10/10。日志侧：

```
16:02:20.500  keyword hit: 小朵小朵 (#7, fed=559, gated=1884)
16:02:21.893  kws gap reset: silence streak flushed     ← 每轮命中后 1-2s 准点冲刷
16:02:29.213  keyword hit: 小朵小朵 (#8, fed=575, ...)
16:02:30.476  kws gap reset: silence streak flushed
16:02:39.101  keyword hit (#9) / 16:02:48.801 (#10) / 16:02:58.201 (#11)——间隔 ~9-10s 一声一响
```

- 口径备注：日志总命中 11 次 vs 正式 10 轮——多出的 1 次为正式开测前的交互唤醒，非误触发。
- 误触发旁证：挂起窗+gap-reset 上线后观测窗内无异常命中（安静期 gap reset 即冻结尾分冲刷日志，无 hit）。

## 6. 唤醒词打断核查结论（任务②）

- **当前结构不支持**（三重屏障逐行钉死）：`VoiceStateRouter.kt` SPEAKING/PROCESSING 恒 DROP（S11-3 前有）；
  `kwsPaused` 对话期置位；`VoiceSessionManager.startListening` 非 IDLE 早退。
- **08-04 AEC spike「5.8min 零误触发」证据无效**：旧 spike 旁路（每 200ms 强制 kwsPaused=false）对
  SPEAKING 是 no-op——KWS 全程没收到音频。BLOCKED.md 已更正。S11-3 已重做真放行 spike
  （`spikeFeedKws`，fed 增长为有效性闸），真机误触发/检出率观测待做。
- sherpa-onnx 1.13.4 无 AEC 模块（AAR 类全集已枚举，仅降噪器）；VOICE_COMMUNICATION 硬件 AEC 已在用。

## 7. 附：探针用法（S11-6 保留设施）

设置页（debug 包）→ AEC spike 区块 →「③ 延迟取证探针：开」→ 过门块双侧打日志：
`cap rms=… lag=Nms`（tag AudioProbe，lag=系统通路积压）/ `feed rms=…`（tag KwsEngine，KWS 入队）。
配对口径：过门块 1:1；挂起窗内低能块只打 feed 侧（孤行属预期）。取完证关闭。
