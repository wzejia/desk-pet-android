# -*- coding: utf-8 -*-
"""平台 TTS「我在」合成 —— **历史存档**（S9-1 迁入，勿运行）。

来源：`.scratch/wozai/remake_wozai.py`（2026-08-06/07 wozai.wav 重制蓝本）。
现行 `app/src/main/assets/wozai.wav`（b-trim「我在」0.54s，08-07 定稿）**不重制**；
本文件仅存档平台合成路径备查。运行需内网端点 10.0.18.97:9803 可达，本票不依赖。
"""
import base64
import json
import struct
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

TTS_URL = "http://10.0.18.97:9803/audio/tts/synthesize"
SRC = common.ASSETS_DIR / "wozai.wav"
OUT_DIR = common.REPO_ROOT / ".scratch" / "wozai"


def synth(text, speed):
    """平台 cosyvoice3/default 合成（内网直连绕代理），返 int16 采样列表。"""
    body = json.dumps({"text": text, "serviceName": "cosyvoice3",
                       "speaker": "default", "speed": speed}).encode("utf-8")
    req = urllib.request.Request(TTS_URL, data=body,
                                 headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 内网绕代理
    with opener.open(req, timeout=60) as resp:
        env = json.loads(resp.read().decode("utf-8"))
    assert env.get("code") == 200, env
    wav = base64.b64decode(env["data"]["audioData"])
    tmp = OUT_DIR / "__raw_a.wav"
    tmp.write_bytes(wav)
    return common.read_wav(tmp)


def find_mid_gap(samples, win=common.WIN):
    """找前 60% 内最长静音缝的中点（「哎，」与「我在」之间）。"""
    env = common.window_max(samples, win)
    peak = max(env)
    th = peak * 0.05
    limit = int(len(env) * 0.6)
    best = (0, 0)  # (start_idx, length)
    i = 0
    while i < limit:
        if env[i] <= th:
            j = i
            while j < limit and env[j] <= th:
                j += 1
            if j - i > best[1] - best[0]:
                best = (i, j)
            i = j
        else:
            i += 1
    mid = (best[0] + best[1]) // 2
    return mid * win, best[0] * win / common.SR, (best[1] - best[0]) * win / common.SR


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # 路线 a：重合成「我在」，speed 0.95（对齐 e3）与 1.0 两候选
    for spd, tag in ((0.95, "a-095"), (1.0, "a-100")):
        raw = synth("我在", spd)
        out = common.process(raw)
        p = OUT_DIR / ("wozai-only-%s.wav" % tag)
        common.write_wav(p, out)
        print("[a:%s] raw=%d out=%d (%.2fs) -> %s" % (tag, len(raw), len(out), len(out) / common.SR, p))

    # 路线 b：裁现有 e3「哎,我在。」找「哎，」与「我在」间静音缝
    src = common.read_wav(SRC)
    cut, gap_s, gap_len = find_mid_gap(src)
    print("[b] 静音缝 @ %.2fs 长 %.0fms，cut=%d (%.2fs)" % (gap_s, gap_len * 1000, cut, cut / common.SR))
    out = common.process(src[cut:])
    p = OUT_DIR / "wozai-only-b-trim.wav"
    common.write_wav(p, out)
    print("[b:trim] out=%d (%.2fs) -> %s" % (len(out), len(out) / common.SR, p))


if __name__ == "__main__":
    main()
