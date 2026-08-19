# -*- coding: utf-8 -*-
"""唤醒应答音「我在」资产工具库（S9-1）。

原语从 `.scratch/wozai/remake_wozai.py` **逐参数原样迁移**（2026-08-06 wozai.wav 后处理管线）：
  trim_edges: win=240（10ms@24k），阈 th=max(peak*0.02, 60)，
             头部保留 (first-3)*win 下钳 0（30ms 呼吸），尾部 (last+4)*win 上钳 len（40ms 呼吸）
  normalize: 峰值归一 26000，round（半偶舍入，与 Kotlin Math.rint 一致），钳 [-32768, 32767]
  write_wav: 44B 标准 RIFF 头，24000Hz / mono / 16bit PCM

该口径与 app 侧 WakeAckAudio（S9-2 Kotlin 移植）构成 Python↔Kotlin parity：
`--emit-parity-fixture` 冻结的黄金向量（app/src/test/resources/wakeack/）供 Kotlin 逐字节对拍。

key 纪律：load_keys() 从 secrets/cloud.properties 读取，调用方**严禁打印 key 值**。
"""
import argparse
import math
import struct
from pathlib import Path

SR = 24000
PEAK_TARGET = 26000
WIN = 240  # 10ms @ 24k

# validate 契约（与 S9-2 WakeAckAudio.validate 一致）：字节长 ∈[9600,144000]（0.2s–3.0s）+ 峰值 ≥1000
MIN_BYTES = 9600
MAX_BYTES = 144000
MIN_PEAK = 1000

REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"
FIXTURE_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "wakeack"
SECRETS_FILE = REPO_ROOT / "secrets" / "cloud.properties"


def read_wav(path):
    """读 WAV → int16 采样列表（简易 RIFF 解析：找 data chunk，同 remake_wozai.py）。"""
    with open(path, "rb") as f:
        data = f.read()
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        csize = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        if cid == b"data":
            pcm = data[pos + 8:pos + 8 + csize]
            break
        pos += 8 + csize + (csize & 1)
    else:
        raise ValueError("no data chunk")
    return list(struct.unpack("<%dh" % (len(pcm) // 2), pcm))


def write_wav(path, samples):
    """写 44B 标准 RIFF（24000Hz/mono/16bit）+ 裸 PCM。"""
    pcm = struct.pack("<%dh" % len(samples), *samples)
    hdr = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + \
        struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16) + \
        b"data" + struct.pack("<I", len(pcm))
    with open(path, "wb") as f:
        f.write(hdr + pcm)


def pcm_to_samples(pcm):
    """裸 PCM 字节 → int16 采样列表。字节长必须为偶（奇数=坏帧，直接拒）。"""
    if len(pcm) % 2 != 0:
        raise ValueError("pcm 字节长为奇数：%d" % len(pcm))
    return list(struct.unpack("<%dh" % (len(pcm) // 2), pcm))


def window_max(samples, win=WIN):
    """分窗包络（10ms 窗内绝对值最大），末窗残缺按实际内容计（空窗=0）。"""
    return [max(abs(x) for x in samples[i:i + win]) if samples[i:i + win] else 0
            for i in range(0, len(samples), win)]


def trim_edges(samples, win=WIN):
    """去头尾静音：按包络阈 th=max(peak*0.02,60) 找首末有声窗，头留 3 窗/尾留 4 窗呼吸。"""
    env = window_max(samples, win)
    peak = max(env) if env else 0
    th = max(peak * 0.02, 60)
    first = next((i for i, v in enumerate(env) if v > th), 0)
    last = next((i for i in range(len(env) - 1, -1, -1) if env[i] > th), len(env) - 1)
    s = max(0, (first - 3) * win)
    e = min(len(samples), (last + 4) * win)
    return samples[s:e]


def normalize(samples, target=PEAK_TARGET):
    """峰值归一 target：round 半偶舍入（Kotlin 侧 Math.rint 对拍基准），钳 ±32768/32767。"""
    peak = max(abs(x) for x in samples) if samples else 0
    if peak == 0:
        return samples
    g = target / peak
    return [max(-32768, min(32767, round(x * g))) for x in samples]


def validate(samples):
    """校验合成 PCM 可否作为唤醒应答（防垃圾入缓存/入资产）。返回 (ok, 诊断串)。"""
    n_bytes = len(samples) * 2
    peak = max((abs(x) for x in samples), default=0)
    if n_bytes < MIN_BYTES or n_bytes > MAX_BYTES:
        return False, ("byte_len=%d 不在 [%d,%d]（%d 采样，时长 %.2fs，peak=%d）"
                       % (n_bytes, MIN_BYTES, MAX_BYTES, len(samples), len(samples) / SR, peak))
    if peak < MIN_PEAK:
        return False, "peak=%d < %d（%d 采样，时长 %.2fs）" % (peak, MIN_PEAK, len(samples), len(samples) / SR)
    return True, "byte_len=%d peak=%d dur=%.2fs" % (n_bytes, peak, len(samples) / SR)


def process(samples):
    """完整后处理管线：去头尾静音 → 峰值归一 26000（与 wozai.wav 现行口径一致）。"""
    return normalize(trim_edges(samples))


def load_keys(path=SECRETS_FILE):
    """读 secrets/cloud.properties → {key: value}。调用方严禁打印 value。文件缺失返空 dict。"""
    keys = {}
    if not path.exists():
        return keys
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            keys[k.strip()] = v.strip()
    return keys


def key_status(name, keys):
    """只报 key 名与是否为空（README/日志纪律：绝不打印 key 值）。"""
    v = keys.get(name, "")
    return "%s=%s" % (name, "SET" if v else "EMPTY")


# ---------------------------------------------------------------------------
# parity 黄金向量（确定性合成输入，零随机；冻结后供 Kotlin WakeAckAudioTest 逐字节对拍）
# ---------------------------------------------------------------------------

def parity_input_samples():
    """确定性输入：0.8s 静音 + 0.25s 正弦(amp=3333) + 0.1s 静音 + 0.3s 正弦(amp=5000) + 0.6s 静音。
    正弦周期 480 采样（50Hz）；中间 0.1s 静音夹在两段有声之间，验证 trim 只切边缘不切内部。"""
    def silence(ms):
        return [0] * (SR * ms // 1000)

    def sine(dur_ms, amp):
        n = SR * dur_ms // 1000
        return [int(round(amp * math.sin(2 * math.pi * i / 480.0))) for i in range(n)]

    return silence(800) + sine(250, 3333) + silence(100) + sine(300, 5000) + silence(600)


def emit_parity_fixture(out_dir=FIXTURE_DIR):
    """跑 process() 全管线，落盘 parity-input.wav（输入）与 parity-expected.wav（期望输出）。"""
    inp = parity_input_samples()
    out = process(inp)
    out_dir.mkdir(parents=True, exist_ok=True)
    p_in = out_dir / "parity-input.wav"
    p_exp = out_dir / "parity-expected.wav"
    write_wav(p_in, inp)
    write_wav(p_exp, out)

    peak_in = max(abs(x) for x in inp)
    peak_out = max(abs(x) for x in out)
    env = window_max(inp)
    th = max(max(env) * 0.02, 60)
    first = next(i for i, v in enumerate(env) if v > th)
    print("[fixture] input : %d 采样（%.2fs）peak=%d -> %s" % (len(inp), len(inp) / SR, peak_in, p_in))
    print("[fixture] expect: %d 采样（%.2fs）peak=%d -> %s" % (len(out), len(out) / SR, peak_out, p_exp))
    print("[fixture] trim  : 首有声窗=%d（含 0.8s 前导静音已被切），阈 th=%.1f" % (first, th))
    assert peak_out == PEAK_TARGET, "期望输出峰值必须恰为 %d，实际 %d" % (PEAK_TARGET, peak_out)
    ok, diag = validate(out)
    assert ok, "期望输出未过 validate：%s" % diag
    print("[fixture] 期望输出过 validate：%s" % diag)
    return p_in, p_exp


def main():
    ap = argparse.ArgumentParser(description="唤醒应答音共享原语（trim/normalize/validate/parity 夹具）")
    ap.add_argument("--emit-parity-fixture", action="store_true",
                    help="生成 Kotlin 对拍黄金向量到 app/src/test/resources/wakeack/")
    args = ap.parse_args()
    if args.emit_parity_fixture:
        emit_parity_fixture()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
