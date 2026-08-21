---
title: gitignored 密钥不在任何取证源内——重建树云密钥全空、云链路静默断链，从保全证据 APK 反编译 BuildConfig 取回（S10-3 真机语义验收浮出）
date: 2026-08-21
type: solution
category: workflow-issues
module: app/build.gradle.kts（secrets/cloud.properties → BuildConfig 密钥注入链）
problem_type: runtime_error
component: recovery
severity: high
symptoms:
  - "重建树出包 assembleDebug 绿、基线 438 单测全绿，但 BuildConfig 三个云密钥（CLOUD_KEY/ALIYUN_SPEECH_KEY/VOLC_SPEECH_KEY）全为空串——构建期零告警（缺文件/空值时为空串是注入链的既有语义）"
  - "真机语音轮 ASR 一进即 abort：logcat『pipeline abort at ASR: 阿里 ASR 未配 dashscope key』（CloudClientFactory key.isBlank() 分支，2026-08-19 19:24 RM-6 首轮）"
  - "云端 ASR/LLM 链路整体静默失效，直到 S10-3 真机语义验收的真实语音轮才浮出，非构建/测试期可见"
  - "恢复树上 secrets/cloud.properties 缺失，且 git 全历史从未收录（.gitignore `secrets/` 设计使然）、五个取证源无一个覆盖该文件"
root_cause: missing_workflow_step
resolution_type: config_change
tags: [gitignored-secrets, repo-rebuild, buildconfig, cloud-keys, secrets-recovery, evidence-apk, jadx-decompile, silent-failure]
---

# gitignored 密钥不在任何取证源内——重建树云密钥全空，从保全证据 APK 反编译 BuildConfig 取回

## 问题（Problem）

2026-08-18 仓库整体清空事故经五源取证重建后（见 [repo-wipeout-five-source-forensic-rebuild.md](repo-wipeout-five-source-forensic-rebuild.md)），构建与 438 单测全绿、新 APK 装机可跑——但 gitignored 的 `secrets/cloud.properties` 不在 git 历史、也不在任何取证来源的恢复清单里，未随恢复树回迁，新构建的 BuildConfig 三个云密钥全为空串。缺口被绿构建/绿单测/表层装机验证三层掩盖，直到 S10-3 真机语义验收的语音轮才以「ASR 中止」暴露；最终从**事故前保全的旧 APK 反编译产物**中取证恢复了密钥。

## 症状（Symptoms）

- S10-3 真机贴边态语音轮（2026-08-19 19:24，de61cd8）唤醒链前半段全正常：wake「小朵小朵」命中 → wozai 应答 → LISTENING → VAD 判停 → PROCESSING，随后在 ASR 环节中止。logcat 原文行：
  - `pipeline abort at ASR: 阿里 ASR 未配 dashscope key`（产出点 `app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt:472`）
  - `state -> IDLE (asr failed)`（`backToIdle("asr failed")`，`VoiceSessionManager.kt:478`；行格式 `state -> $to ($reason)` 见 `:695`）
  - 错误气泡 27 字符「没听清：阿里 ASR 未配 dashscope key」（`bubble.show(...)`，`VoiceSessionManager.kt:477`）
- **掩盖面（同样重要）**：症状出现前，恢复树 `./gradlew.bat assembleDebug testDebugUnitTest` 全绿（51 suites / 438 tests / skipped=0），新 APK 双端装机后 App 启动、悬浮窗双窗口、KWS sherpa 加载全部正常——这些路径都不触云凭证。
- 证据：`docs/verification/v16-edge-dock/real-rm6-voice-round-docked.txt`（首轮症状+定性）、`docs/verification/v16-edge-dock/apk-preservation-2026-08-19.md`、`DEV_LOG.md` 2026-08-19 条目「顺带修复」段、`implementation-notes.html` 缺陷修复条目（2026-08-19）。

## 走过的弯路（What Didn't Work）

1. **把「绿构建 + 绿单测」当恢复完备性的证明**。438 测全 JVM、零 Robolectric/MockK（项目硬纪律），语音链路测试用 MockWebServer 全桩 HTTP 层——单测结构上从不读真实密钥、从不触真实云端，密钥为空对它们不可见。测试绿 ≠ 运行环境完备。
2. **把「能装能跑」当验收**。App 启动 / 悬浮窗 / KWS 加载都是零网络凭证依赖的路径，验了等于没验云端链路。表层装机验证只能证明「包没坏」，不能证明「环境齐了」。
3. **信任「从 git 恢复 = 一切都在」——失败的假设**。`secrets/` 在 `.gitignore:14`（`*.keystore` 在 `:15`），git 历史/克隆**结构性不含**它；五源取证重建的清单（金档 / 转录重放 / 证据链 sha1 / APK 资产与反编译 / winfr）逐项核对，没有任何一项把「本地未跟踪秘密文件」列为恢复对象。（session history）落位机制本身也结构性排除：E: 盘恢复 = 从合并树 `git clone`——即使合并树工作目录里幸存 secrets，clone 也不携带 gitignored/未跟踪文件。
4. **恢复过程中唯一一次线索点名被静默丢弃**（session history）。winfr MFT 清点表（2026-08-19 03:29）明确出现过 `secrets/ | 1 文件 | cloud.properties 有望`——但 TRIM 定性（只有 <~1KB 的 MFT 驻留小文件是真货，该文件不在真货清单）之后这条线被静默放弃，恢复全程没有一句「secrets 确认丢了」的结案结论。**线索出现过 ≠ 线索闭环；未结案的线索等于没有。**
5. **密钥文件的「出生记录」在一切可恢复通道之外**（session history）。08-14 cloud 八件套经排查「不是任何 Claude/pi 会话的 Write 工具写的」（转录/证据链零命中，写入通道至今无考）——secrets/cloud.properties 大概率同批同命运，transcript 重放结构性抓不到它的内容。
6. **讽刺点**：所需的密钥其实一直躺在重建时已反编译好的 `D:\desk-pet-recovery\apk-src\sources\com\xiaoduo\deskpet\BuildConfig.java` 里（五源里的 L4 来源）——不是数据丢了，是恢复清单里没有它，没人想到去取。盲区的本质是**枚举缺失**，不是来源缺失。

## 解法（Solution）

**前置（决定成败的一步）**：S10-3 装新包**之前**，当天已把真机现装老 APK 保全为不可变证据——`device-base.apk`（90,876,784B，sha1 `f018793f59b494c8a899d5e95047b2ff82bd8456`，`docs/verification/v16-edge-dock/apk-preservation-2026-08-19.md`）。没有这份副本，密钥将随覆盖安装一起灭失。

**取证提取**（一次性脚本，存档于 `E:/desk-pet-android/.scratch/restore_cloud_properties_0819.py`，.scratch 本身 gitignored）：从旧 APK 的 jadx 反编译 `BuildConfig.java` 用正则抽三个常量，安全断言三连——抽不到拒写、含转义/非可打印 ASCII 拒写、含 properties 特殊字符拒写，**值全程不回显**：

```python
for name in ('CLOUD_KEY', 'ALIYUN_SPEECH_KEY', 'VOLC_SPEECH_KEY'):
    m = re.search(r'public static final String ' + name + r' = "(.*?)";', src)
    if not m:
        sys.exit(f'FATAL: {name} not found')
    v = m.group(1)
    if '\\' in v or any(ord(c) < 33 or ord(c) > 126 for c in v):
        sys.exit(f'FATAL: {name} contains escapes/non-ascii, refuse to write blindly')
    if any(c in v for c in ('#', '!')):
        sys.exit(f'FATAL: {name} contains properties-special char')
    out[name] = v
...
print('written:', {k: f'len={len(v)}' for k, v in out.items()})   # 只打长度，不打值
```

拒写断言的用意：正则从反编译产物抽取是启发式，一旦抽偏（残渣、转义、半截串）宁可失败也不把脏值硬写进 secrets 文件——错误恢复出的密钥比缺失更难排查。结果：CLOUD_KEY / ALIYUN_SPEECH_KEY 各 len=116，VOLC_SPEECH_KEY len=0（事故前即为空，原状——判定「丢了什么」要以事故前在案状态为基线，见 session history 的 pre-wipe 记录）。

**回迁 + 重建 + 重装**：写入 `E:/desk-pet-android/secrets/cloud.properties`（gitignored，永不入库，文件头注明来源与换机重建指引）→ `assembleDebug` 重建（注入链见 `app/build.gradle.kts:33-35`）→ 新 APK sha1 `070d453022f1b82f4a4d6c3412a2963cd869c7c2` → 重装 emulator-5554 与真机 de61cd8。

**语义验证闭环**（真机、用户真声，`real-rm6-voice-round-docked.txt`）：wake「小朵小朵」命中 → wozai 应答 → VAD 判停 → ASR 转写「今天的天气怎么样？」→ LLM firstToken 456ms → TTS SPEAKING → 气泡显示 → 多轮 LISTENING → 超时自愈回 IDLE；精灵全程 x=-468 贴边不变（正交项，恰是被测 Case）。与首轮同场景同路径，唯一变量是密钥——症状消失即归因成立。证据与文档随 commit `cb2ce93`（docs+evidence only）入库。

**时间线旁证消解**（session history + 本会话核实）：恢复会话记录里 08-19T21:05Z 起有两次「恢复树云 LLM 实测可用」（firstToken 487/615ms），与本篇「空 key 断链」表面矛盾——核对时区即解：密钥修复完成于 08-19 11:33Z（北京 19:33），两次成功观测均在修复**之后**，无矛盾。

## 为什么有效（Why This Works）

- **根因链**：`secrets/` 被 `.gitignore:14` 排除 → 文件不在 git 历史 → 从恢复树克隆回的工作树里没有它 → `app/build.gradle.kts:10-15` 对缺文件静默容错（`if (f.exists())` 才 load，`orEmpty()` 兜底）→ 三条 `buildConfigField`（`:33-35`）注入空串（当时亲查 `app/build/generated/.../BuildConfig.java` 三常量 len=0）→ `NetConfig` 回退链 SP → BuildConfig → 空 → `CloudClientFactory` 的 blank-key 守卫触发。整条链无一处报「文件缺失」——构建期静默容错把环境缺口推迟到了运行期最深处方才发声。
- **BuildConfig 字符串是可回收的**：`buildConfigField("String", ...)` 生成 `public static final String` 常量，不经混淆（release 未开 minify）、不以任何变换存储，编译进 APK 后仍是完整明文常量，jadx 反编译直接可读。**凡打包期注入的密钥，部署产物本身就是一份（不受 gitignore 影响的）备份**——前提是那份产物在覆盖前被保全。
- **不可变保全件的双重身份**：device-base.apk 当天是为「APK 对拍验收」而保全的证据，事故恢复时已用作资产正本；这次又成为密钥的唯一幸存来源。为取证保全的不可变副本，在下一个事故里就是恢复源——保全动作永远先于需求成立。
- **语义验收补上了结构缺口**：单测桩掉网络层、装机冒烟不触凭证路径，只有「真实凭证 + 真实云端 + 真人语音」的端到端一轮同时覆盖了「密钥存在且正确」这一环境断言。RM-6 首轮失败/二轮成功恰好构成一次可证伪对照。

## 预防（Prevention）

1. **灾难恢复清单必须逐文件枚举本地秘密文件**（`secrets/`、`.env`、`*.keystore`/签名密钥、`.scratch/secrets/`），不接受「从 git 重建即完备」的笼统口径——gitignored 的每一行都是 git 路线恢复的盲区（本仓现役清单：`.gitignore` 的 `secrets/`、`*.keystore`；项目 CLAUDE.md 记载的平台凭据 `.scratch/secrets/platform-account.json`）。两个已知同命运实例：cloud.properties（本篇）与 platform-account.json（session history：同随 wipe 消失且五源未覆盖，后经用户手输密码重走通）。**远端备份同样救不了**：恢复树 08-20 起已定期 `git push`「落袋为安」，但远端不含 gitignored 文件——备份介质必须显式覆盖秘密清单。
2. **恢复中的秘密类线索必须显式结案**（「找回/确认丢失/替代来源」三选一），不许静默滑过——本篇弯路 4 的 winfr 点名就是被静默丢弃的实例。
3. **覆盖部署产物之前先做不可变保全**（sha1 落证据）。本例若先装机后保全，密钥即永久灭失——SSD TRIM 已证磁盘恢复不可依赖（见五源重建篇）。保全成本是一分钟，缺失成本是不可逆。
4. **语义验收（真实端到端路径 + 真实凭证）是环境级回归的唯一探测器**。单测在结构上 mock/绕开网络与凭证，绿单测对「密钥为空」不可见；装机冒烟只验零凭证路径。凡依赖本地秘密的链路，收口验收必须含至少一轮真凭证端到端。
5. **恢复过程中密钥值同样不入日志、不回显、不入库**——只断言长度（脚本 `print(len=...)`；运行期红线见 `NetConfig`「严禁写入日志」注释）。取证产物本身可循 S11-5 先例入库 `docs/verification/forensic-*/`（session history），但密钥值永不入库；本篇证据（`docs/verification/v16-edge-dock/`、DEV_LOG、`cb2ce93`）全程只含 len=116/0 与 sha1，可安全入库。

## 相关（Related Issues）

- [repo-wipeout-five-source-forensic-rebuild.md](repo-wipeout-five-source-forensic-rebuild.md) — 同事故母篇（五源重建方法论）。本篇实证其五源对 gitignored 本地秘密全不覆盖、其验收锚（单测全绿 + APK 结构对拍）对环境/密钥类回归无效。
- [unanchored-code-silent-loss-in-rebuild.md](unanchored-code-silent-loss-in-rebuild.md) — 同事故姊妹篇（无测试锚代码静默丢失）。本篇是该盲区家族的第三案·非代码变体：gitignored secrets 连「加测试锚」这条出路都原理性不可用，唯一出路是异地秘密备份 + 产物反编译取证。
- [per-modality-multi-cloud-native-ws-clients.md](../design-patterns/per-modality-multi-cloud-native-ws-clients.md) — 被空 key 饿死的云客户端架构底座（构造注入凭证、byok 多厂商分发）。
- [dashscope-asr-task-field-tuple-unpack.md](../logic-errors/dashscope-asr-task-field-tuple-unpack.md) — 同一 ASR 报错面的对照故障（key 有效但字段错 → Model not found vs 本篇 key 缺失 → 未配 dashscope key）。
- GitHub issues：私有库 `wzejia/desk-pet-android` 无匹配 issue（gh 核实，0 条）。
