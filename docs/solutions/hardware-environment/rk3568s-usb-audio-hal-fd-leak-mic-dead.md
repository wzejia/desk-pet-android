---
title: rk3568_s 板子语音全断——USB audio HAL fd 泄漏自锁 + 板载 input 未登记 audio policy（双层堵死，App 层无解）
date: 2026-09-11
category: hardware-environment
module: 环境诊断（非 App 代码缺陷；App 防线 AutoCalib/AudioCapture/VadDetector 日志全部按设计工作）
problem_type: environment_defect
component: audio-capture
symptoms:
  - vad maxLevel=0.0000（≈0.0001 未馈入/系统层）——聆听期零电平
  - AutoCalib ambient unstable (n=0 median=0.0 p95=0.0)——自动校准永远采不到块
  - logcat 刷 AudioRecord: dead IAudioRecord / alsa proxy_open: Device or resource busy
  - APM_AudioPolicyManager: getInputForAttr() could not find device for source 9/1
root_cause: rom_audio_stack_defect
resolution_type: vendor_escalation
severity: high
tags: [rk3568, usb-audio-hal, fd-leak, ebusy, audio-policy, input-profile, tinycap, mic-zero-feed, 10.0.18.47]
---

# 现象

10.0.18.47（rk3568_s，整理台网关板）语音链路全断：KWS 常开但零喂入（`kws stats` 不落行）、
唤醒永不命中、VAD 聆听零电平、S29 自动校准永远 n=0。同 APK 在 10.0.14.18（rk3568_r）健康
（39h / hits=39）。

# 根因（两层 ROM 缺陷叠加，两头堵死）

```
USB 声卡插着：
  USB audio HAL（modules.usbaudio.audio_hal）fd 泄漏
  = input open 失败路径不 close + HAL 构造期 probe open 不 close
  → /dev/snd/pcmC?D?c 被 HAL 自己持有 → 一切后续 open 报 EBUSY 死循环
  → APM getInputForAttr() failed → App 层 dead IAudioRecord 风暴 → 采麦零馈入

USB 声卡拔掉：
  APM: could not find device for source 9/1
  → 板载 rk809 capture 只在内核层活着（tinycap 可录），未登记 audio policy
  → 框架层没有任何 input 设备 → 同样零馈入
```

# 诊断套路（可复用：采麦零馈入五步切分）

1. **App 侧锚点**：`vad maxLevel≈0.0001` 标注 + `AutoCalib n=0` + `dead IAudioRecord` 风暴
   ——三个都指向「框架层拿不到数据」而非「唤醒/VAD 调参问题」。
2. **内核层验硬件**：`tinycap -D <card> ...` 逐卡试录（注意 USB 声卡只支持特定采样率/声道，
   16k/2ch Invalid argument ≠ 硬件死，48k/1ch 可能活着）。
3. **找泄漏者**：`lsof | grep pcmC`——fd 持有者是 HAL 自己 = 自锁；kill HAL 进程后新进程
   **启动瞬间即再持 fd** = 构造期泄漏铁证（不是运行期偶发）。
4. **验 policy 设备表**：USB 拔掉后看 `getInputForAttr() could not find device` ——
   板载 input 是否登记在 policy 决定「拔 USB」是不是出路。
5. **对照同 APK 健康板**：排除 App 回归，坐实环境差异。

# 教训

- **「唤醒率低」先验「采麦是否馈入」**：kws stats 零落行（KwsCore 只在有块时落 stats）
  = 连音频都没有，调阈值/NLMS/模型全是空转。声学排障第一律（先测能量再调算法）之前
  还有一律：**先确认音频真的从框架层进来了**。
- USB 声卡热插时间戳（/dev/snd 节点 mtime）是黄金线索——「昨天还好好的今天全死」先查
  现场插拔了什么。
- App 层对 ROM 缺陷的正确姿势：把故障做成**自解释的**（n=0 拒写校准、零馈入电平标注、
  初始化失败分级日志都已具备），剩下的上报 vendor，不要在 App 层盲修。
- 18.47 自 S26 起「语音现场验收」一直待回收不是巧合：**验收一直没做的地方可能一直就是坏的**。

# 关联

- 证据链全文：`docs/verification/v23-s32-mic-zero-feed/README.md`
- App 层防御增强：CONSTRUCTION.md S32 票（采麦零馈入自检告警）
- S18 采麦源三档（PrefContract KEY_MIC_SOURCE_MODE）——UNPROCESSED(2) 是本次唯一能
  `capture start` 成功的源
