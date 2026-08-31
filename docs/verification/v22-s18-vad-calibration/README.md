# v22 - S18 VAD 阈值校准联动 + 板子声学诊断（2026-08-31，板子终验待回收）

## 起因

用户报「唤醒词打断变不灵敏」（板子 10.0.14.18）。三层排查（OPPO 对照 + 板子日志取证）：
**打断检测本身完全正常**（4 轮 keyword hit 全中、TTS 即停、kws stats fed/gated 健康），
病灶在 VAD 判停--每轮聆听都拖满 8.3s 强制上限（`132800 samples` 一模一样）。

## 诊断链（证据见 board-logs.md）

1. **受控实验实锤**：喊唤醒后只说「你好」（ASR text=你好。），VAD 仍收 8.3s--
   其余 7.8s 全是高于阈值的噪声；另有纯噪声轮 maxLevel=0.3403、ASR 空文本。
2. **AGC 钉死铁证**：校准分布 median=0.0487 / p95=0.0496 / max=0.0509（三值几乎相同，
   真实环境噪声不可能这么稳）= 底层 AGC 把输入归一化到固定电平。
3. **换 VOICE_RECOGNITION 源无效**：校准仍钉死 0.046-0.049 -> AGC 不在 Android
   预处理链，在 codec/HAL 更深层。
4. **AEC 缺失实锤**：`dumpsys audio` 显示采麦会话 `effects client=, dev=`（零音效挂载，
   无 AEC/AGC/NS）--TTS 外放声浪裸进麦克风，打爆输入（削波 maxLevel=1.0）后
   底层 AGC 猛压、播放结束后慢释放（8s+），噪声底抬高顶住 VAD 判停。
5. 对照：同一版包（VAD 400ms）在 OPPO 上完全正常--排除版本/400ms 因素。

## 交付（S18-1/2，已收口）

1. **VAD 说话阈值校准联动**：`VadDetector.speechLevelProvider` 动态注入（每块现读，
   与 KWS 能量门同款即时生效范式）；`EnergyGate.suggestVadSpeechLevel` =
   `clamp(p95×1.6, 0.05, 0.15)`（手机安静环境回默认 0.05，**行为零变化**）；
   「环境音校准」一次写入双值（KWS 门 + VAD 阈值），报告与日志同步双值。
2. **采麦源模式**（板子诊断基建）：设置页「采麦源」三档循环（通话/识别/原始
   UNPROCESSED），重启生效；`AudioCapture.sourceMode` 注入，构造失败均 fallback
   DEFAULT。旧 bool 键自动迁移。
3. **聆听期电平轨迹诊断**：LISTENING 态每 10 块（1s）打一行 `vad diag level=…`，
   为自适应阈值方案定量 AGC 释放曲线。

## 测试

- 472 -> **475 绿 skipped=0**：VadDetectorTest +2（阈值注入抬杠/默认 0.05 语义回归）、
  EnergyGateTest +1（建议值公式：手机/板子/钳位三档）。
- `assembleDebug` 绿。
- 板子实测：校准写入生效（`vad level written: 0.08008`）、KWS 门同步生效
  （gated 10 -> 87/168/880），但 **AEC 缺失致聆听期噪声底仍高于校准值**
  （校准=安静态，聆听=播放后 AGC 慢释态）-> 阈值联动是必要非充分。

## 待终验（用户今晚）

1. 板子切「采麦源：原始（UNPROCESSED）」-> 强杀重启 -> 重新校准（看分布是否分散）
   -> 受控测试（喊唤醒只说「你好」），看 utterance 采样数是否 ~1 万级（不再 132800）
   + `vad diag level` 轨迹形态。
2. UNPROCESSED 若无效（HAL 不支持 raw）：上**自适应 VAD 阈值**终方案
   （聆听期 EMA 跟踪噪声底、阈值随动）--电平轨迹日志即为此铺的数据。

## 已知边界

- OPPO 手机默认路径零变化（阈值下限 0.05 = 原硬编码；源默认 VOICE_COMMUNICATION）。
- AguiClientTest.blankExternalMessageIdFallsBackToGenerated 出现过一次偶发失败
  （单跑 27/27 绿，与 S18 零关联），观察中。
