# v23 — S32 采麦零馈入诊断：10.0.18.47 板子语音链路全断根因链

> 日期：2026-09-11 ｜ 会话焦点：语音链路优化（唤醒率+打断率）开工侦察
> 设备：`10.0.18.47:5555`（rk3568_s，整理台网关板）｜ 对照：`10.0.14.18:5555`（rk3568_r，健康）
> 结论：**ROM/固件层缺陷（USB audio HAL fd 泄漏 + 板载 input 未登记 audio policy），App 层无解，需设备侧修复**

## 一、背景

Handoff（2026-09-10）指定本会话焦点=优化语音链路（唤醒识别率+打断成功率）。开工无侵入侦察
发现：整理台网关板 10.0.18.47 的**采麦流零馈入**——唤醒率/打断率无从谈起，先行诊断。

## 二、现象（证据按时间序，logcat 原文）

| # | 时间 | 证据 |
|---|---|---|
| 1 | 11:12/11:31 | `VoiceSession: manual release, no utterance, vad maxLevel=0.0000 (≈0.0001 未馈入/系统层…)` ——两次手动聆听零电平 |
| 2 | 11:23:05 | `AutoCalib: ambient unstable (n=0 median=0.0 p95=0.0), keep defaults` + `auto calibration: skipped` ——S29 自动校准 5 秒**一块 RMS 都没采到**（n=0） |
| 3 | 11:23:04 | `AudioRecord: restoreRecord_l: dead IAudioRecord, creating a new one` ×20+/350ms（双线程交替）——**audio server 侧 recording track 反复死亡** |
| 4 | 11:44 | `modules.usbaudio.audio_hal: adev_open_input_stream fail - address card:2 device:0 doesn't match existing profile` + `APM_AudioPolicyManager: getInputForAttr() failed opening input` ——USB audio HAL profile 失配，input 打不开 |
| 5 | 11:47 | `alsa_device_proxy: proxy_open() pcm_is_ready() failed: cannot open device 0 for card 2: **Device or resource busy`** ——audioserver 重启后 HAL 重枚举成功，但 open 又全部 EBUSY（每秒 ~200 行刷屏） |
| 6 | 11:46 | `lsof`: `android.hardware.audio.service 11u CHR 116,8 /dev/snd/pcmC2D0c` ——**USB audio HAL 进程自己持有 card2 capture 节点不释放** |
| 7 | 11:46 | kill HAL 进程（init 自动拉起）→ **新 HAL 进程一启动立即再持 pcmC2D0c** ——泄漏发生在 HAL 构造/profile 探测期（open 后不 close），**USB 声卡插着=USB input 永久自锁** |
| 8 | 12:03:33 | `APM_AudioPolicyManager: getInputForAttr() could not find device for source 9 (UNPROCESSED) / source 1 (DEFAULT)` ——**USB 移除后 audio policy 找不到任何 input 设备：板载 rk809 capture 未登记在 policy** |

## 三、硬件层排除（逐层切分，App 无辜）

```
tinycap -D 0 (HDMI)      → pcmC0D0c 不存在（HDMI playback-only，正常）
tinycap -D 1 (rk809 板载) → Captured 49152 frames，wav RMS=0.0425 ✔ 板载麦硬件完好
tinycap -D 2 (USB 16k/2ch)→ Invalid argument
tinycap -D 2 (USB 48k/1ch)→ Captured 98304 frames ✔ USB 麦硬件完好（仅支持 48k）
```

对照板 10.0.14.18（rk3568_r，不同 ROM）：同 App、默认 VOICE_COMMUNICATION 源，
`kws stats: fed=1407200 gated=552 hits=39 / 39h` 全程健康——**排除 App 代码回归**。

## 四、根因结论（三层叠加，全在 ROM/固件层）

1. **rk USB audio HAL fd 泄漏**：input stream open 失败路径不 close；audioserver/HAL 重启后
   构造期 probe 即泄漏持锁 → `Device or busy` 死循环 → App 全部 AudioRecord `dead IAudioRecord`
   → 采麦零馈入、S29 校准 n=0 循环失败。
2. **板载 input 不在 audio policy 设备列表**：USB 移除后 `could not find device for source 9/1`
   ——rk3568_s 的 primary audio 配置缺 input profile，板载麦只在内核层活着（tinycap 可录），
   Android 框架层从来拿不到。
3. **两层叠加**：USB 麦插着 = HAL 自锁死；USB 麦拔了 = policy 无 input。**两头堵死**。

时间线旁证：USB 声卡（UGREEN CM564）**2026-09-11 10:39 热插**（/dev/snd 节点时间戳），
此后所有采麦零馈入；且该板自 S26 起「语音现场验收」始终待回收——**其语音链路此前从未真正
通过框架层工作过**（插 USB 麦疑为现场试图救拾音，恰撞 HAL 病灶）。

唯一一次歪打正着：11:49 切 UNPROCESSED 后某 restore 成功窗采到数据，11:50:33 SP 写入
`done=true + vad_speech_level=0.05 + kws_gate=0.003`（全为钳位下限值，保守无害）。

## 五、本会话已做的动作与现场状态变更（含回滚点）

| 动作 | 状态变更 | 回滚方式 |
|---|---|---|
| `killall audioserver` ×2 | 无持久副作用（init 自动拉起） | 无需 |
| `echo 1 > /sys/bus/usb/devices/1-1.5/remove`（软拔 USB 麦） | 重启后 USB 已重新枚举，现场硬件原状 | 无需 |
| 整机 `reboot` ×1 | 无持久副作用 | 无需 |
| SP `mic_source_mode: 0 → 2`（UNPROCESSED） | **保留**（UNPROCESSED 是 S18 终选方向，且是唯一能 `capture start` 成功的源） | sed 写回 value="0" |
| SP `kws_gate=0.003 / vad=0.05 / done=true`（校准自写） | 保留（钳位下限，保守无害；与 14.18 工作形态一致） | 删除对应 SP 键 |

最终状态（12:16）：`capture start, source=9` 首次成功；但 `kws stats` 仍零——**USB 重新枚举
回 card0 后 read 通路再死**（病灶 ① 复发）。板子现状：语音不可用，但日志/校准防线工作正常，
比来时多了完整的故障自解释能力。

## 六、待办/待用户决策

1. **设备供应商反馈**：rk3568_s ROM 的 USB audio HAL fd 泄漏 + primary audio input profile
   缺失（证据见本文）。可能修复：固件更新 / 换 ROM / audio_policy_configuration.xml 补 input
   mixPort（需 vendor 分区可写）。
2. **现场试验**（需人在场）：物理拔插 USB 麦 + 整机重启的组合（不同枚举顺序 card0/card2）——
   本次远程重启后 USB 回 card0 仍死，物理重插能否命中存活窗口待验；亦可试换 USB 口/带供电
   HUB/换 USB 声卡型号。
3. **App 层防御票（S32，已立 CONSTRUCTION.md）**：采麦零馈入自检告警（详见票据）。
4. `mic_source_mode=2` 保留观察；若后续板载 input 被固件修复，UNPROCESSED/RECOGNITION/COMM
   三档板子终验（S18 遗留）顺势完成。
5. 14.18 主力板未受任何影响（本会话仅 root 读取，零写入），语音优化主线（唤醒率+打断率）
   可在该板继续。
