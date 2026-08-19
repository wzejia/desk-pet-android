# TTS→ASR 回环验证留档（2026-07-31）

方法：知识包踩坑 #50「干净机无麦回环验证法」——平台 TTS 合成一句话 → WAV 剥头取 PCM →
送平台 ASR 转写 → 比对原文。用途：真麦可用前先验语音双服务链路与契约参数，结果可审计。
背景票：BLOCKED.md backlog #2（S4 P3×3）之「回环留档」（critic-G：自称验证过 ≠ 可审计）。

## 产物清单

| 文件 | 内容 |
|---|---|
| `合成文本.txt` | TTS 输入原文（今天天气真好，适合出门散步。） |
| `tts.wav` | TTS 流式 NDJSON 帧剥 WAV 头拼 PCM 后的成品（24kHz/16bit/mono，3.44s，1 帧 + done 帧齐） |
| `asr-transcript.txt` | SenseVoice 转写结果（与原文逐字一致，含标点） |
| `run-log.txt` | 完整命令 + 原始响应关键行 + 比对结论 |

## 结论

TTS（10.0.18.97:9803，cosyvoice3/default/1.0）与 ASR（10.0.18.97:9802，sensevoice，language=zh）
双服务在线，契约参数与知识包 §2.3/§2.4 一致；合成→转写回环**逐字命中**。

## 复现步骤

```bash
# 0. 环境：Git Bash + curl + python3（标准库即可）；必须 NO_PROXY 直连内网
mkdir -p .scratch/loopback && cd .scratch/loopback

# 1. 生成 UTF-8 请求体（勿在 curl 命令行内联中文，会 400）
python - <<'EOF'
import json
body = {"text": ["今天天气真好，适合出门散步。"], "serviceName": "cosyvoice3",
        "speaker": "default", "speed": 1.0}
open("tts-body.json", "wb").write(json.dumps(body, ensure_ascii=False).encode("utf-8"))
EOF

# 2. TTS 流式合成
curl --noproxy '*' -s -o tts-ndjson.raw --max-time 120 \
  -X POST http://10.0.18.97:9803/audio/tts/stream \
  -H 'Content-Type: application/json; charset=utf-8' --data-binary @tts-body.json

# 3. 剥头拼 PCM（脚本同目录 pack_tts.py，逻辑见 run-log 第 2 节）
python pack_tts.py   # 产出 tts.wav

# 4. ASR 转写
curl --noproxy '*' -s --max-time 30 \
  -X POST http://10.0.18.97:9802/audio/asr/transcribe \
  -F 'file=@tts.wav;type=audio/wav;filename=utterance.wav' \
  -F 'language=zh' -F 'serviceName=sensevoice'

# 5. 比对 data.text 与原文（应逐字一致）
```
