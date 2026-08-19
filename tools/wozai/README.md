# tools/wozai — 唤醒应答音「我在」资产工具（S9-1）

四文件：`common.py`（共享原语+校验+parity 夹具）/ `synth_cloud.py`（三家云合成）/
`synth_platform.py`（平台合成**历史存档，勿运行**）/ 本 README。

## 产物契约（与 app 侧 WakeAckAudio 逐字节 parity）

所有应答音资产统一口径（源自 2026-08-06 wozai.wav 管线）：

- **后处理**：去头尾静音（win=240，阈 `max(peak*0.02, 60)`，头留 3 窗/尾留 4 窗呼吸）
  → 峰值归一 **26000**（round 半偶舍入 = Kotlin `Math.rint`，钳 ±32768/32767）
- **容器**：44B 标准 RIFF，24000Hz / mono / 16bit PCM
- **validate**：字节长偶且 ∈[9600, 144000]（0.2–3.0s）+ 峰值 ≥1000；不过校验**不落盘**并打印峰值/时长

## key 纪律（红线）

- key 从仓库根 `secrets/cloud.properties` 读（gitignored），映射：
  `aliyun→ALIYUN_SPEECH_KEY`、`volcengine→VOLC_SPEECH_KEY`、`openai→CLOUD_KEY`
- **任何输出绝不打印 key 值**（只打 key 名与 SET/EMPTY）
- key 缺 → 对应 `wozai-<vendor>.wav` 不生成，记偏差（不算失败，运行时兜底链覆盖）

## 用法

```bash
# 三家云资产（文案「我在」，speed/rate=1.0，与 App 运行时预取严格同参）
uv run --with 'websockets>=14' python tools/wozai/synth_cloud.py --vendor aliyun
python tools/wozai/synth_cloud.py --vendor volcengine
python tools/wozai/synth_cloud.py --vendor openai

# Kotlin 对拍黄金向量（确定性合成输入跑全管线，冻结输入/期望输出两个 wav）
python tools/wozai/common.py --emit-parity-fixture
```

产物：`app/src/main/assets/wozai-{aliyun,volcengine,openai}.wav`；
夹具：`app/src/test/resources/wakeack/{parity-input,parity-expected}.wav`。

协议逐字段镜像 App 线上客户端（`app/src/main/java/com/xiaoduo/deskpet/cloud/`）：
aliyun=WS duplex（`AliyunTtsClient`）、volcengine=NDJSON（`VolcengineTtsClient`）、
openai=`/audio/speech`（`OpenAITtsClient`）；模型/音色默认值同 `NetConfig` 默认。

## 网络注意

本机 Clash(7897) MITM 拦公网 TLS：urllib 一律 `ProxyHandler({})` 直连（国内端点直连可达）；
websockets 用 `proxy=None`。报错先查 `~/.claude/docs/errors.md` 网络节。

## 契约测试

`app/src/test/java/com/xiaoduo/deskpet/voice/WakeAckAssetsContractTest.kt` 对资产逐文件断言：
资产集对账 / 44B RIFF+PCM=1+mono+16bit+24000Hz / 时长 0.2–3.0s / 峰值 26000±2 /
头部 50ms 内信号起（锁 trim 契约）。新增资产 = 常量清单加一行 + 重新生成。

## 真机试听（S9-4 用，需 device.lock 互斥锁）

```bash
adb push app/src/main/assets/wozai-aliyun.wav /sdcard/Download/   # 配 MSYS2_ARG_CONV_EXCL="*"
```

## 历史与偏差

- `wozai.wav`（平台 cosyvoice3 b-trim「我在」0.54s，08-07 定稿）**不重制**；其合成路径存档于
  `synth_platform.py`（内网端点 10.0.18.97:9803，本目录工具不依赖）。
- 已知偏差（生成时如实记录于 DEV_LOG）：`VOLC_SPEECH_KEY` 为空 → volcengine 资产暂缺；
  `gpt-4o-mini-tts` 在 dashscope compatible-mode `/audio/speech` 能否出声为探针项（R3），
  404/400 则 openai 资产暂缺。两者 key/端点到位后重跑对应命令 + 契约测试必备集加一行即闭环。
