> 自 fork rk3288@7bef28c 搬运（2026-10-20），fork 侧 evidence 未带 JDK 前缀为原档债务，本仓口径以 docs/DEV_LOG.md:28 为准。

# 验收证据：AGC 远场 + Tap-to-Talk 交互（17.242，2026-10-19）

> 板：10.0.17.242，包 e0e2eb2（md5 e9a57e55），采麦源 MIC 档（pref mic_source_mode=3），
> KWS pref=false。日志 `files/logs/xiaoduo-2026-10-19.log`（本地存档 .scratch/tap-to-talk/log-1019-final.txt）。
> 验收方式：用户现场发声 + 主会话远程切档重启 + 日志金线核对（accesswindow 盖层致 UI
> 自动化不可用，交互走真人路径）。

## 一、关键结论

**远场语音链路全通**（AGC 票核心目标达成）：MIC 档 + 软件 AGC 下，远场说话被听见并完成
「采麦→VAD 判停→ASR→LLM」全链路。

## 二、时间线与金线

| 时刻 | 事件 | 判定 |
|---|---|---|
| 13:27~13:28 | source=1（MIC 档），2 轮 `VAD utterance` 成功 | 基线可通 |
| 13:32 起 | **漂回 source=7**（VOICE_COMMUNICATION），无漂移 WARN | pref 被切回 0（构造时固化，需重启生效） |
| 13:41~13:43 | 12 轮全 `manual release, no utterance`，maxLevel 0.12~**1.00 爆表** | AGC 在 source=7（自带硬件增益）上增益叠加过量削波 |
| 13:52 | 用户保存配置（net config saved）但**进程未重启**（PID 2005 不变），采麦源仍 7 | 档位「重启生效」语义未达成 |
| 13:54~13:55 | 主会话远程 sed pref=3 + force-stop 重启 | **capture start, source=1** 生效 |
| **13:57:03** | **`VAD utterance, 56000 samples, maxLevel=0.1732`** → ASR ok 11 字「123123你好你好」→ LLM 582ms | ✅ **远场全链路通** |
| 13:57:20 | `agc nf=0.0037 g=3.60`（远场轮后从 8.00 顶限回落） | ✅ AGC 噪声底自适应生效 |
| 13:57:47 | `manual release, no utterance, maxLevel=0.6220` | 说话偏短未判起（使用节奏项，非代码缺陷） |

## 三、金线核对

- ✅ `capture start, source=1`（MIC 档生效，3 次落盘）
- ✅ AGC 状态行 `agc nf=` 周期落盘；g 值随场景回落（8.00 顶限 → 3.60）
- ✅ 远场轮 `VAD utterance` 出活 + ASR 出字 + LLM 链路
- ⚠️ `manual release, no utterance` MIC 档后仍有 2 例（13:57:47 等）——均为说话时长偏短
  （VAD 判起需连续 3 块过线 ~300ms），属使用节奏项；Tap-to-Talk 吞语音守卫（病 B 竞态
  ~92ms 窗）本次未触发到（需「判停进 hold 的 100ms 窗内点结束」才命中，正常说完等一拍
  即不落入）。
- ⏳ Tap-to-Talk 5 项交互走查（多轮续听/再点退出/播报中打断）日志未完整覆盖，留待下轮。

## 四、暴露的非代码问题（记账）

1. **采麦源档位「重启 App 生效」语义**：设置页保存配置只重建 Activity 不杀进程，采麦源
   构造时固化——用户保存后未杀进程重启，档位不生效（13:52 实证）。**处方**：设置页采麦源
   切换后提示「需重启 App」并给一键重启入口，或切档即 force-stop 自拉（P3 候选）。
2. **AGC 对采麦源档位敏感**：参数按 MIC 档低电平（nf~0.003）调校；在 VOICE_COMMUNICATION
   （自带硬件增益）上增益叠加过量削波（maxLevel 爆表 1.00）。**处方**：AGC 噪声底 EMA 已
   自适应（g 会回落），但削波段仍存在——板验观察项，必要时按档位分参数（P3 观察）。
