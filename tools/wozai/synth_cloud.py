# -*- coding: utf-8 -*-
"""三家云厂商「我在」唤醒应答音合成（S9-1）。

协议**逐字段镜像** App 线上请求（与运行时预取 CloudClientFactory.ttsSynthesize(ctx,"我在") 严格同参）：
  aliyun    ← AliyunTtsClient.kt     dashscope WS duplex（run-task/continue-task/finish-task）
  volcengine← VolcengineTtsClient.kt  HTTP NDJSON（X-Api-Key/X-Api-Resource-Id）
  openai    ← OpenAITtsClient.kt      POST {base}/audio/speech（OpenAI 兼容）

模型/音色/采样率默认值取 NetConfig.kt 默认（云开箱即用）：
  aliyun: model=qwen-audio-3.0-tts-flash, voice=longanfengyue（KEY_ALIYUN_TTS_*）
  volc:   model=seed-tts-2.0-standard, speaker=zh_female_qingxinnvsheng_uranus_bigtts, resource=seed-tts-2.0
  openai: base=https://dashscope.aliyuncs.com/compatible-mode/v1, model=gpt-4o-mini-tts, voice=alloy

用法：
  aliyun（需 websockets，依赖临时化不装全局）：
    uv run --with 'websockets>=14' python tools/wozai/synth_cloud.py --vendor aliyun
  volcengine / openai（纯 stdlib）：
    python tools/wozai/synth_cloud.py --vendor volcengine
    python tools/wozai/synth_cloud.py --vendor openai

key 从 secrets/cloud.properties 读（gitignored），**绝不打印 key 值**。
key 缺 → 该资产不落盘并记偏差（exit 0，兜底链已覆盖）；端点失败 → exit 1。
产出统一过 common.validate + process（trim+归一 26000）+ 44B RIFF 24k/mono/16bit。
"""
import argparse
import base64
import json
import struct
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

TEXT_DEFAULT = "我在"
SPEED_DEFAULT = 1.0

# key 名 → secrets/cloud.properties 键（aliyun 用 ALIYUN_SPEECH_KEY，openai 走 CLOUD_KEY，volc 用 VOLC_SPEECH_KEY）
KEY_NAME = {
    "aliyun": "ALIYUN_SPEECH_KEY",
    "volcengine": "VOLC_SPEECH_KEY",
    "openai": "CLOUD_KEY",
}

# 与 CloudClientFactory.ttsSynthesize 分发一致的三家参数（默认值=NetConfig）
ALIYUN_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"  # AliyunTtsClient.WS_URL
ALIYUN_MODEL = "qwen-audio-3.0-tts-flash"                            # NetConfig.aliyunTtsModel 默认
ALIYUN_VOICE = "longanfengyue"                                       # NetConfig.aliyunTtsVoice 默认

VOLC_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"  # VolcengineTtsClient.URL
VOLC_MODEL = "seed-tts-2.0-standard"                # NetConfig.volcTtsModel 默认
VOLC_SPEAKER = "zh_female_qingxinnvsheng_uranus_bigtts"  # NetConfig.volcTtsSpeaker 默认
VOLC_RESOURCE = "seed-tts-2.0"                      # NetConfig.volcTtsResource 默认

OPENAI_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"  # NetConfig.DEFAULT_CLOUD_BASE
OPENAI_MODEL = "gpt-4o-mini-tts"                   # NetConfig.DEFAULT_CLOUD_TTS_MODEL
OPENAI_VOICE = "alloy"                             # NetConfig.DEFAULT_CLOUD_TTS_VOICE


def direct_opener():
    """本机 Clash(7897) MITM 会拦公网 TLS；国内端点直连可达 → 空代理表绕行（errors.md 网络节）。"""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------------------
# aliyun：dashscope WS duplex（镜像 AliyunTtsClient.runTask/continueTask/finishTask）
# ---------------------------------------------------------------------------

def _run_task(task_id, model, voice, sample_rate, rate, pitch=1.0):
    return {
        "header": {"action": "run-task", "task_id": task_id, "streaming": "duplex"},
        "payload": {
            "model": model, "task_group": "audio", "task": "tts", "function": "SpeechSynthesizer",
            "input": {},
            "parameters": {
                "voice": voice, "format": "pcm", "sample_rate": sample_rate,
                "rate": rate, "text_type": "PlainText", "pitch": pitch,
            },
        },
    }


def _continue_task(task_id, model, text):
    return {
        "header": {"action": "continue-task", "task_id": task_id, "streaming": "duplex"},
        "payload": {
            "model": model, "task_group": "audio", "task": "tts", "function": "SpeechSynthesizer",
            "input": {"text": text},
        },
    }


def _finish_task(task_id):
    return {
        "header": {"action": "finish-task", "task_id": task_id, "streaming": "duplex"},
        "payload": {"input": {}},
    }


def synth_aliyun(key, text, model=ALIYUN_MODEL, voice=ALIYUN_VOICE,
                 sample_rate=common.SR, rate=SPEED_DEFAULT, timeout_s=30.0):
    """需 websockets>=14（sync 客户端）。二进制帧=PCM，TEXT 帧 event: task-finished/task-failed。"""
    try:
        from websockets.sync.client import connect
    except ImportError:
        print("[error] 缺 websockets，请用：uv run --with 'websockets>=14' python tools/wozai/synth_cloud.py --vendor aliyun")
        return None, "缺依赖 websockets"

    task_id = uuid.uuid4().hex  # Kotlin 侧 UUID.randomUUID().toString().replace("-","")
    pcm = bytearray()
    error = [None]

    try:
        # proxy=None：直连（同 urllib 侧绕 Clash）；additional_headers 为 websockets>=14 参数名
        ws = connect(ALIYUN_WS_URL, additional_headers=[("Authorization", "Bearer " + key)],
                     open_timeout=15, close_timeout=5, proxy=None)
    except TypeError:
        ws = connect(ALIYUN_WS_URL, extra_headers=[("Authorization", "Bearer " + key)],
                     open_timeout=15, close_timeout=5)
    try:
        # WebSocket 保序，可连发（镜像 onOpen 三连发）
        ws.send(json.dumps(_run_task(task_id, model, voice, sample_rate, rate)))
        ws.send(json.dumps(_continue_task(task_id, model, text)))
        ws.send(json.dumps(_finish_task(task_id)))
        from websockets.exceptions import ConnectionClosed
        import time
        deadline = time.monotonic() + timeout_s
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                error[0] = "TTS 超时（%.0fs）" % timeout_s
                break
            try:
                msg = ws.recv(timeout=remain)
            except ConnectionClosed:
                if not error[0]:
                    error[0] = "WS 在 task-finished 前关闭（pcm=%dB）" % len(pcm)
                break
            except TimeoutError:
                error[0] = "TTS 超时（%.0fs）" % timeout_s
                break
            if isinstance(msg, bytes):
                pcm.extend(msg)
                continue
            obj = json.loads(msg)
            header = obj.get("header", {})
            event = header.get("event", "")
            if event == "task-finished":
                break
            if event == "task-failed":
                error[0] = "TTS task-failed: %s" % str(header.get("error_message", msg))[:200]
                break
            # task-started / task-meta 等继续收
    finally:
        try:
            ws.close()
        except Exception:
            pass

    if error[0]:
        return None, error[0]
    if not pcm:
        return None, "TTS 返回空音频"
    return bytes(pcm), "aliyun tts ok, %dB pcm" % len(pcm)


# ---------------------------------------------------------------------------
# volcengine：HTTP NDJSON（镜像 VolcengineTtsClient.synthesize）
# ---------------------------------------------------------------------------

def synth_volc(key, text, model=VOLC_MODEL, speaker=VOLC_SPEAKER, resource_id=VOLC_RESOURCE,
               sample_rate=common.SR, speed=SPEED_DEFAULT):
    # speech_rate: [-50,100]，100=2x，-50=0.5x；speed=1.0 → 0
    speech_rate = max(-50, min(100, int((speed - 1.0) * 100)))
    payload = {"req_params": {
        "text": text, "model": model, "speaker": speaker,
        "audio_params": {"format": "pcm", "sample_rate": sample_rate, "speech_rate": speech_rate},
    }}
    req = urllib.request.Request(
        VOLC_URL, data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Api-Key": key,
            "X-Api-Resource-Id": resource_id,
            "X-Api-Request-Id": str(uuid.uuid4()),
        })
    with direct_opener().open(req, timeout=60) as resp:
        body = resp.read().decode("utf-8")
    pcm = bytearray()
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        code = obj.get("code", 0)
        b64 = obj.get("data", "")
        if not b64:
            continue  # OK 末帧（code=20000000）无 data 跳过
        if code not in (0, 20000000):
            return None, "TTS code=%s: %s" % (code, obj.get("message", ""))
        pcm.extend(base64.b64decode(b64))
    if not pcm:
        return None, "TTS 返回空音频"
    return bytes(pcm), "volc tts ok, %dB pcm" % len(pcm)


# ---------------------------------------------------------------------------
# openai（兼容模式）：POST {base}/audio/speech（镜像 OpenAITtsClient.synthesize）
# ---------------------------------------------------------------------------

def synth_openai(key, text, base=OPENAI_BASE, model=OPENAI_MODEL, voice=OPENAI_VOICE,
                 speed=SPEED_DEFAULT):
    payload = {"model": model, "input": text, "voice": voice, "response_format": "pcm"}
    if speed != 1.0:
        payload["speed"] = speed  # 镜像 Kotlin：speed==1.0 不发该字段
    req = urllib.request.Request(
        base.rstrip("/") + "/audio/speech",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": "Bearer " + key})
    try:
        with direct_opener().open(req, timeout=120) as resp:
            pcm = resp.read()
    except urllib.error.HTTPError as e:
        return None, "TTS HTTP %d: %s" % (e.code, e.read().decode("utf-8", "replace")[:200])
    if not pcm:
        return None, "TTS 返回空音频"
    return pcm, "cloud tts ok, %dB pcm" % len(pcm)


# ---------------------------------------------------------------------------
# 主流程：key 检查 → 合成 → validate → process → 落盘 assets
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="三家云「我在」应答音合成（协议镜像 App 线上请求）")
    ap.add_argument("--vendor", required=True, choices=["aliyun", "volcengine", "openai"])
    ap.add_argument("--text", default=TEXT_DEFAULT)
    ap.add_argument("--speed", type=float, default=SPEED_DEFAULT)
    args = ap.parse_args()

    keys = common.load_keys()
    key_name = KEY_NAME[args.vendor]
    print("[key] %s" % common.key_status(key_name, keys))  # 只打名与是否为空
    key = keys.get(key_name, "")
    if not key:
        print("[skip] %s 未配置 → wozai-%s.wav 不生成（记偏差，兜底链覆盖）" % (key_name, args.vendor))
        return 0

    print("[synth] vendor=%s text=%r speed=%.2f" % (args.vendor, args.text, args.speed))
    try:
        if args.vendor == "aliyun":
            pcm, msg = synth_aliyun(key, args.text, rate=args.speed)
        elif args.vendor == "volcengine":
            pcm, msg = synth_volc(key, args.text, speed=args.speed)
        else:
            pcm, msg = synth_openai(key, args.text, speed=args.speed)
    except Exception as e:
        pcm, msg = None, "网络/协议错误: %r" % e
    print("[%s] %s" % ("ok" if pcm else "error", msg))
    if not pcm:
        print("[fail] wozai-%s.wav 不落盘（端点不可用，记偏差）" % args.vendor)
        return 1

    try:
        samples = common.pcm_to_samples(pcm)
    except ValueError as e:
        print("[fail] %s → 不落盘" % e)
        return 1
    ok, diag = common.validate(samples)
    print("[validate:raw] %s %s" % ("PASS" if ok else "FAIL", diag))
    if not ok:
        print("[fail] 原始合成未过校验 → 不落盘")
        return 1

    out = common.process(samples)
    ok2, diag2 = common.validate(out)
    peak = max(abs(x) for x in out)
    print("[process] trim+normalize → %d 采样（%.2fs）peak=%d %s" % (len(out), len(out) / common.SR, peak, diag2))
    if not ok2 or peak != common.PEAK_TARGET:
        print("[fail] 后处理产物未达口径（peak 应恰为 %d）→ 不落盘" % common.PEAK_TARGET)
        return 1

    dst = common.ASSETS_DIR / ("wozai-%s.wav" % args.vendor)
    common.write_wav(dst, out)
    print("[write] %s（%dB）" % (dst, dst.stat().st_size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
