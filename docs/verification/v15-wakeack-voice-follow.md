# v15 S9 唤醒应答音色跟随 TTS 配置 — 验收证据（2026-08-18）

> 票据：CONSTRUCTION.md S9（方案 C 混合）。设备：真机 de61cd8（声学/日志）+ 模拟器 xiaoduo_emu（冷路径预检）。
> 基线：`./gradlew.bat testDebugUnitTest --rerun-tasks` → **tests=427 failures=0 errors=0 skipped=0**（编排者亲算 XML，50 套件；372 基线 + S9-1 契约 5 + S9-2 纯逻辑 46 + S9-3 resolver 4）。

## 一、模拟器冷路径预检（2026-08-17 晚）

| 项 | 证据 |
|---|---|
| 默认配置零发包 | App 启动（默认 cloud+aliyun 默认元组）logcat 零 WakeAckPrefetch 行（needsPrefetch=false 秒退） |
| Case B 机器半场 | 设置改 cosyvoice-v2/longxiaochun_v2 → 保存 → `I/WakeAckPrefetch: prefetch saved: ack-6_aliyun12_cosyvoice-v215_longxiaochun_v2-6ad30e16b4.pcm (19724B wav, pcm 19680B)`（保存后 1.0s）；缓存拉回核验：PCM=1/mono/24000Hz/16bit/0.410s/**峰值恰 26000**/头呼吸 30ms |
| Case C 机器半场 | 火山+自定义音色 → 保存 → `W/WakeAckPrefetch: prefetch failed: 火山 TTS 未配 X-Api-Key (backoff 10min)` 恰一条；二连保存被退避窗吞（无第二条）；缓存目录零污染；进程存活无 Toast |

## 二、真机六 Case（2026-08-18 上午，用户在场试听拍板）

### Case A 默认 cloud+aliyun（开箱态，SP 无 xiaoduo_net.xml）✅
用户听感：**「已同声线 ✓」**（应答=对话 TTS 同族龙安凤悦声，非平台 cosyvoice）。
```
09:58:45.534 wake word hit (小朵小朵), -> startListening        # VoiceFgService
09:58:45.535 state -> SPEAKING (wake-ack wozai)                 # VoiceSession
09:58:45.549 WakeAck: source=asset:wozai-aliyun.wav provider=aliyun model=qwen-audio-3.0-tts-flash voice=longanfengyue
09:58:46.170 state -> LISTENING (wozai done, VAD start)         # SPEAKING→解析 14ms，播 636ms
```

### Case B 自定义音色 → 动态缓存命中 ✅
设置改 阿里TTS模型=cosyvoice-v2 / 阿里TTS音色=longxiaochun_v2 → 保存：
```
10:03:53.162 Settings: net config saved …
10:03:53.939 WakeAckPrefetch: prefetch saved: ack-6_aliyun12_cosyvoice-v215_longxiaochun_v2-6ad30e16b4.pcm (19724B wav, pcm 19680B)
```
（与模拟器字节同大——同参数确定性输出。缓存已拉回：`docs/verification/s9-caseB-cache-longxiaochun.pcm`，WAV 容器可直接复听。）
唤醒后用户听感：**「换声成功 ✓」**（龙小淳声线，与 Case A 明显不同，起音即时）：
```
10:04:44.452 wake word hit (小朵小朵), -> startListening
10:04:44.458 WakeAck: source=cache:ack-6_aliyun12_cosyvoice-v215_longxiaochun_v2-6ad30e16b4.pcm provider=aliyun model=cosyvoice-v2 voice=longxiaochun_v2   # 6ms 解析
```

### Case C 预取失败 → 兜底链 ✅
火山+自定义音色保存 →（volc_speech_key 含无效值）真实网络失败路径：
```
10:09:39.831 W WakeAckPrefetch: prefetch failed: TTS HTTP 401: {"…code":45000010,"message":"Invalid X-Api-Key"} (backoff 10min)
```
（「未配 key」变体已在模拟器验证；真机补验「key 无效 401」变体——失败→退避→静默链路双变体覆盖。）
唤醒后用户听感：**「回退旧声 ✓」**（火山资产暂缺+无缓存 → 落到 wozai.wav 平台声）：
```
10:10:22.540 WakeAck: source=asset:wozai.wav provider=volcengine model=seed-tts-2.0-standard voice=custom_voice_s9sheng_uranus_bigtts
```

### Case D platform 模式回归 ✅
（UI 单选两次未点中，最终 run-as 直改 `tts_mode=platform` + force-stop 重启生效——SP 回读+行为探针双重确认：重启后零 prefetch 行=needsPrefetch=false=platform 生效，对照 cloud+自定义音色必现 prefetch failed。）
用户听感（含 D+F 轮）：**「全部通过 ✓」**（老平台声回归）：
```
10:27:57.454 WakeAck: source=asset:wozai.wav provider=volcengine …   # wozai.wav 选中；provider 标签在 platform 模式带的是残留云厂商字段（见 P3 备注）
```

### Case E barge-in 打断 ✅（旁证链）
用户对话轮自然发生（TTS 播放中打断）：
```
10:04:54.539 VoiceSession: 播放异常（气泡文字不受影响）: stopped
10:04:54.541 VoiceSession: state -> IDLE (barge-in cancel)
```
应答播放期打断（0.4s 窗）人工不可稳定命中：VSM 零 diff（git 亲证）+ 既有 VoiceSessionManagerWakeAckTest 12 测（cancel 语义含变异锚）继承覆盖，08-05 真机已验路径未动。

### Case F 连续即时性 ✅
用户连唤 5 次：**「起音都即时无可感延迟」**（A/B/C/D 各轮同样即时；memo 生效后热路径仅剩一次 stat）。

## 三、验收后设备状态
- SP xiaoduo_net.xml 已删（恢复出厂默认 cloud+aliyun/longanfengyue）、files/wake_ack 缓存已清、KWS 前台服务在跑、设备锁已放、模拟器已关。

## 四、遗留（记 implementation-notes/PROGRESS）
1. **P3**：source 日志在 platform 模式下 provider 字段带残留云厂商值（如 provider=volcengine）——选择正确仅标签歧义，小修一行（platform 时打 provider=platform）。
2. **观察项（S9 范围外）**：用户报「进入全屏语音模式概率性白屏」——需另立票取证（进程无 crash 记录，疑渲染/导航层）。
3. wozai-volcengine.wav 暂缺（VOLC_SPEECH_KEY 空）、wozai-openai.wav 暂缺（dashscope compatible-mode /audio/speech 404）——key/端点到位后 `tools/wozai/synth_cloud.py` 补跑 + 契约测试必备集各加一行。
