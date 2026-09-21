# 小朵 Android 开发日志


## 2026-09-21 S55/S56 真根因追加定案：采麦模式漂移（mic_source_mode 1/2，已双板复位 mode 0）

用户一句「9月18日没这个问题」触发对照：DEV_LOG 09-18 S52 表格 diag 噪底 **0.004~0.008** vs 今日 **0.19**（同板同指标差 30~50 倍）→ 代码音频链零改动（S54 词表/S55 续听/S56 floor 全不碰采集增益）→ 查 prefs 石锤：**测试板 mic_source_mode=1（VOICE_RECOGNITION）**、**生产板 =2（UNPROCESSED 裸麦）**，而 09-18 S52 收口实证现役=**mode 0（VOICE_COMMUNICATION，硬件语音路径）**。映射（AudioCapture.kt：0→通话/1→识别/2→原始）；设置界面 SettingsScreen 有该选择器（:941）——漂移路径=设置页人工拨动（何时/何人待用户回忆），生产板另有 09-17 16:41 远端同事重装（prefs 全新，可能当时即非 0）。

mode 1/2 与 mode 0 是**不同模拟增益拓扑**：raw/识别路径增益高 → 底噪 0.19 + 说话削顶 1.0 → 判停线 0.03 形同虚设 → 连锁出 hold 死锁（S55）与 floor 失学（S56 缺口）。**复位动作**：双板 force-stop → run-as sed → relaunch → `capture start, source=7` 双板实证。S55/S56 的加固（冻结短路、ASR 裁判式 floor）在 mode 0 安静域自动休眠不触发，作为护甲保留。

**教训入册**：①「环境噪声」结论必须先排除**配置漂移**——同一个 0.19，先查 pref 再怪房间；②diag 噪底是最佳回归哨兵（与 09-18 表格数据对照 5 分钟定位），此类「设置页可改的音频路径参数」应入对账表巡检项。

```evidence
verify_cmd: run-as grep mic_source_mode（复位前 1/2 → 复位后 0/0）；logcat capture start source=7 双板实证；09-18 DEV_LOG diag 0.004~0.008 vs 今日 0.19 对照
verify_claim: 采麦模式漂移为判停退化的第一因（mode 决定增益拓扑）；S56 自适应在 mode 0 安静域自动休眠；待用户喊话确认 diag 回落 0.005~0.01
files_touched: [docs/DEV_LOG.md]
```



## 2026-09-21 S56：判停线自适应落地——ASR 裁判式噪声底上行（双板已更，待喊话验收）

**设计缺口**：S19-1 v5 自适应底本有双通道，但上行通道（噪声上攻）被 S52 停用（误伤持续元音/衰减尾音）后，floor 只能向下/低位跟随——环境噪声阶跃到阈值之上（实测 0.19 vs 判停线 0.03）时 floor 永远学不到，判停恒失效拖满 8s（用户实测「说完 ~5s 才动」= 8s 窗减去说话时长）。

**S56 机制（语义裁判，误伤免疫）**：空 ASR 轮 = ASR 认证「收集到的不是话」——`VadDetector.raiseNoiseFloorFromLastUtterance()`（VSM S19-2 空 ASR 分支调用）以 0.6 速率把 floor 向该轮整段 RMS 收敛（封顶 0.5）；**有文本轮绝不推高 floor**（真语音免疫 S52 式误伤）。收敛动力学（单测锁死）：1 个幻觉轮反馈 → floor≈0.116 → 判起线 floor×2=0.232 > 噪声 0.19 → **幻觉轮绝迹** → 慢速跟踪（NOISE_TRACK_RATE）在聆听期接管（噪声块落回阈值下方可更新）→ floor→0.19、end=max(0.03,×1.4)≈0.25>噪底 → **判停复活（600ms 噪声尾即成句）**，判起=max(0.05,×2)≈0.38 过滤噪声。

**物理极限（明示）**：底噪 0.19 内正常音量（峰值 0.1~0.3）SNR 不足，判起线 0.38 意味着**需较大声说话**；根治=消除噪声源（板旁风扇/空调排查，用户侧）。

**验收预期**：首轮唤醒可能仍有一轮 8s+「没听清」（自适应轮），第二遍起：较大声说完 → ~1s 内判停 → ~2s 回答。若持续「没听清」且声嘶力竭 → 噪声源必须物理处置。

```evidence
verify_cmd: gradlew testDebugUnitTest（642 绿 = 639 + 3 S56 锁：floor 上行/幻觉轮过滤/适应后判停复活/no-op+语音中性）；assembleDebug（22b6522c）；双板 install -r Success + 2×newFromAsset 0 FATAL
verify_claim: floor 上行仅由空 ASR 轮触发（真语音中性）；适应后判停 600ms 恢复且幻觉轮被滤；0 直写陷阱/锚错位教训已规避（行号手术）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, gradle.properties, docs/DEV_LOG.md]
```



## 2026-09-21 S55 取证收口：链路端到端复活 + 噪声源定案（真实环境噪声，非 AGC）

**10 秒实验结果**（18:25~18:26 logcat）：用户唤醒后静默期 vad diag 恒定 **0.185~0.21**——app 刚重启（AGC 已重置）仍不衰减 → **排除 AGC 增益爬升，定案真实环境噪声**（稳态宽频特征，疑似板载风扇/空调/设备声，位置待用户排查）。

**修复实效端到端实证**：`wake → 判停(8s 截断) → dispatch(100ms 内) → ASR text「今天的天气怎么样？」「你能做哪些事情？」→ LLM（firstToken=1810ms）→ TTS 播报（143280 frames）→ 多轮续听 → 下一轮 dispatch→PROCESSING→SPEAKING` 全循环正常转——「说完不回答」问题**解决**。

**遗留（量化）**：判停线 0.03 在 0.19 噪底下失效 → 每轮最长 8s 才截断派发（延迟）+ 截断位置任意（ASR 出现「寻到是」类碎片文本）。**S56 候选（正解）**：判停线自适应——滚动噪底跟踪，end = max(0.03, 噪底×k)，恢复快自然判停；小票体量。临时缓解：排查板子附近噪声源（风扇/空调）；或接受 8s 节奏使用。

```evidence
verify_cmd: 137.45 logcat 18:25~18:26：vad diag 静默期 0.185~0.21 恒定；ASR text×2 正常；AG-UI done reply 30 chars firstToken=1810ms；VoicePlayer stream 143280 frames；多轮 LISTENING→dispatch→PROCESSING→SPEAKING 循环
verify_claim: 派发-ASR-LLM-TTS-续听全链复活；噪声源=真实环境（AGC 排除：重启后不衰减）；判停失效遗留已量化并立项候选
files_touched: [docs/DEV_LOG.md]
```



## 2026-09-21 S55 第二刀：hold 冻结死锁根因石锤 + 短路修复（双板已更）

**取证**（137.45 重测现场 logcat）：①每条 utterance 恒 132800 样本=8s MAX_UTTERANCE 强截断，maxLevel 1.0000 削顶——判停（600ms<0.03）**零触发**，环境电平 0.19~0.29=判停线的 7~10 倍；②`parts=1→8` 无限累积（66s 音频全滞留 hold，零派发）——即便第一刀把窗关到 1，`tickContinuation` 冻结条件（块 ≥0.03 或 isSpeaking）在噪声下永不解除，派发通道仍死锁。校准对照：kws_energy_gate=0.0046（校准时刻安静）+ 重启后 60s stats fed=512/gated=10（当前全程高于校准底）——环境电平暴涨 40 倍（真实噪声 or codec AGC 增益爬升，S18 老病史，**待分辨**：重启 app 后立即唤醒看 vad diag 是否回落）。

**修复**：`tickContinuation` 冻结条件加 `CONTINUATION_TICKS > 1` 门——窗=1（暂关续听）时无条件倒数，判停后下一块**必派发**，与噪声电平无关；窗>1 恢复时冻结语义整体回归。锚随拍板改：用例 3（轻声冻结合并）重写为「合并全关」锁 + 新增噪声回归锁（0.25 响块后必派发），639 绿。

**双板部署**：install -r Success（APK 前 16 `d433b9ac`），双引擎加载 0 异常，app 已重启加载新包。

**遗留分级**：①判停本身在此噪声下仍死（每句 8s 大块+削顶音频，ASR 质量与延迟受损）——正解=**判停线自适应**（滚动噪底跟踪，end=max(0.03, 噪底×k)）或 AGC 限幅（大票 A 复活候选），下一票；②环境电平 40 倍暴涨归因（AGC 爬升 vs 真噪声）待用户配合 10 秒实验（重启后立即唤醒+静默，看 vad diag 回落与否）；③生产板 10.0.14.18 今晚 logcat 无语音活动痕迹（用户重测或在 137.45）。

```evidence
verify_cmd: log-137.txt：VAD utterance 132800 samples×8 + hold parts=1→8 + vad diag level=0.19~0.29；修复后 gradlew testDebugUnitTest（639 绿）+ assembleDebug（d433b9ac）+ 双板 install -r Success + 2×newFromAsset 0 FATAL
verify_claim: hold 派发通道在任意电平下必通（噪声回归锁锁死）；判停 0 触发系环境电平超线 7~10 倍（非代码回归）；638→639 测试（+1 噪声锁，用例 3 重写）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S55 第一刀（用户拍板）：续听窗 10→1 双板暂关（排查「说完不回答、持续聆听」）

**现象**：用户实测说完话后 app 持续「聆听中…」不回答。**嫌疑定位**（代码走查，未取证即先行处置——用户拍板优先）：`tickContinuation` 冻结条件 = 单块 ≥end 线(0.03) 或 `vad.isSpeaking` 即冻结倒计时；噪声环境下永不解除，且 `holdForContinuation` 内 `vad.start()` **重置 30s 聆听超时** → 无限挂起（30s 兜底也失效，与「一直监听」吻合）。

**处置**：`CONTINUATION_TICKS` 10→1（判停 600ms + 100ms 即派发，≈S50 前行为）。**关键陷阱**：不可设 0——`holdForContinuation` 只攒片，派发全靠倒计时耗尽，0=永不派发=语音哑火。代价：短语气口合并（S51 主价值）暂失，「查询一下…（停>0.7s）…2025年的合同」会被拆两轮；轻声续说（0.03~0.05 频段）因冻结线语义仍可合并（用例 3 不变仍绿）。锚随拍板改：S51ContinuationTest 用例 2 重写为关窗语义锁（KDoc 注明恢复窗口时还原，原版见 git 历史），638 绿不变。

**双板部署**：137.45 + 10.0.14.18 install -r Success，双引擎加载 0 异常（APK sha256 前 16 `edeee8d6`）。

**待办**：①重测——若症状消失=冻结假设成立，正解候选=**有界续听**（冻结也有总上限 ~2.5s，保住合并语义又杜绝无限挂起）；②若仍不回答→抓 logcat 分流（empty ASR 续听 / 平台 LLM 挂起 / 判停线被噪声顶穿），与续听窗无关；③生产板环境噪声 vs 判停线 0.03 需 AmbientCalibrator 复核。

```evidence
verify_cmd: gradlew testDebugUnitTest（638 绿，用例 2 已随拍板重写）；assembleDebug（edeee8d6）；双板 install -r Success + logcat 2×newFromAsset 0 FATAL
verify_claim: 双板续听窗=1 块生效；判停即派发（+100ms）；hold 零派发陷阱已规避（0 未使用）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt, docs/DEV_LOG.md]
```



## 2026-09-21 S54 多唤醒词+打断词（grilling 三轮拍板全落地）+ S55 立项（快语速漏唤醒取证）

**S54 内容**：KWS 词表 1→6 行——新增唤醒词「你好小朵」「小度小度」（Q1=①+②）、打断词「好了」「停下」、「停」为 spike 观测词（Q7）。命中后按词组路由：`VoiceStateRouter.decide(keyword, state)`——唤醒组全态动作；打断组仅 SPEAKING/PROCESSING 动作（Q5=b），**IDLE/LISTENING 命中丢弃**（Q6=a，防闲聊「好了」误唤醒）；「停」恒 DROP 只留日志，真机双合格（播报中真喊 ≥8/10 且播放期误打断 0；负样本误动作>0 判废）后挪 INTERRUPT_WORDS 转正。打断语义=打断并续听（Q3=b），与 S12 唤醒词打断完全同构，动作层零分叉。未登记词默认 ACT（词表/组表漂移不静默失效）。ASR 剥除表 WAKE_PREFIXES 补「你好小朵」与声学行为对称（原「非前缀不剥」用例中 你好小朵 断言随拍板删除）。六词拼音 token 逐一核验在表（含轻声 e）；keywords.txt「停」行带独立阈值 `@0.5`。

**机制备案**：sherpa-onnx KeywordSpotter 原生多词每行一个+回调带词名+每词阈值；打断组 IDLE 丢弃是唯一防线（sherpa 无按态切换词表能力，改词表=重建引擎）；「你好小朵」兼唤醒/打断是语义 no-op（唤醒词在 S12 通道下本来就打断续听），词表只写一次。

**坑与修复**：① enum `KwsHitAction` 定义在 companion object 内时跨文件 `VoiceStateRouter.KwsHitAction` 引用 Unresolved——挪类级嵌套解决；首版修复错插在 companion 花括号内（缩进误导，Kotlin 只看大括号不看缩进），clean+关缓存后仍复现才定位真因。② 本机 Gradle 全挂 `BindException: Address already in use`（FileLockCommunicator 随机 UDP）：最小复现=Java 经典 `DatagramSocket()` 通配绑定 0/30 全败，显式 loopback/NIO DatagramChannel/Python 均全通（疑 Clash TUN 驱动干扰双栈通配路径，**待复核**）——`-Djava.net.preferIPv4Stack=true` 救活（三个候选开关 preferIPv4Stack/preferIPv6Addresses=true/system 各 5/5 实测），写入用户级 `~/.gradle/gradle.properties` 不污染仓库。

**板端**：192.168.137.45:5555 install Success（app-debug.apk sha256 前 16 位 91efafccd7d63eab）；主引擎加载成功后影子引擎才启动加载（代码时序保证），两次 newFromAsset 无 FATAL；KwsEngine gap-reset 周期行=消费线程活跃。`@0.5` 语法初始化未炸；**细粒度行为（0.5 档命中率）随 spike 日志复核**。

**S55 立项（快语速漏唤醒，不并入 S54）**：现象=语速快时「小朵小朵」概率性唤不醒。三假设与判据：H1 阈值/韵律——影子通道(0.15)命中而主(0.3)不中=实锤，S54 soak 顺带采集差分数据（新唤醒词一并攒料）；H2 门削首音——影子也不中+探针构建见词首块被 RMS 门吞（S11-6 机制残余），坐实才临时 re-arm 探针（S52 先例用完收回）；H3 解码积压——kws stats fed/gated 断档。取证口径：用户 20 次快语速+20 次常速对照喊话+logcat 留痕。对症菜单（影子先行证据后动主通道）：每词阈值↓ / keywordsScore 1.0→1.3 / maxActivePaths 4→8；**拒绝项**：换/重训模型（成本不成比例）、关能量门（功耗+误触）。权衡拍板（Q11）：证据先行，误唤醒比漏唤醒更伤日常。

```evidence
verify_cmd: ./gradlew testDebugUnitTest（638 绿 = 633 基线 + 5 新增）；assembleDebug（apk sha256 前缀 91efafccd7d63eab）；adb install -r 192.168.137.45:5555 Success；logcat：双 newFromAsset 无 FATAL + KwsEngine gap-reset 活跃行
verify_claim: 六词词表+词组路由+剥除表落地，板端双引擎加载运行中；「停」spike 恒 DROP 不接动作；待用户喊话验收（唤醒组 IDLE ≥10/10；打断组播报中真喊 ≥8/10 且 IDLE 闲聊含好了/停 0 误唤醒；「停」spike 数据另行采集）
files_touched: [app/src/main/assets/kws-model/keywords.txt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceStateRouter.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceStateRouterTest.kt, docs/DEV_LOG.md, implementation-notes.html]
```


## 2026-09-18 两张小票：探针诊断构建回退 + mic_source_mode 对照复测（S52 后收尾）

**票 1：探针回退**（S52 遗留 hack 收回）：`AudioCapture.probeBlock` 门限 `shouldLog(rms, 0f)`（armed 全量 ~10 行/s）→ 回退 `shouldLog(rms, EnergyGate.DEFAULT_GATE)`，KDoc 注明 S52 历史（为何曾全量：AGC 压制取证需看 0.001~0.015 float 域低值段；再需时临时改 0f 随取证票收回）。`shouldLog` 语义已有 AudioProbeTest 5 锁，调用点回归无新行为不需新测，633 绿不退。

**票 2：mode 0/2 对照复测**（S52 停用上攻后首次同条件对照，一轮一变量）：新包装板 → 轮 A（mode 0/source=7）3 轮 → force-stop+sed prefs+relaunch 切 mode 2（source=9，capture start 实证）→ 轮 B 同脚本 3 轮 → 切回 mode 0（source=7 实证）。

| 维度 | 轮 A mode 0 | 轮 B mode 2 |
|---|---|---|
| 唤醒 | 3/3 | 3/3（含聆听期重唤醒 1 次） |
| maxLevel | 0.63/0.33/0.29 | 0.32/0.39/0.30 |
| ASR 完整性 | 3/3 完整 | 3/3（第 3 轮少「合同」系用户口误，非切字） |
| S51 链 | hold→dispatch 3/3（32000/35200/51200 样本） | 3/3（33600/38400/48000 样本） |
| 幽灵轮/切尾/拖尾 | 0/0/0 | 0/0/0（S40 backfill 每轮 7 块正常，**无硬件 AEC 下「我在」回声未构成回灌**） |
| 判停尾段 | diag 落底 0.004~0.007 | 同 0.005~0.008 |
| 噪声底（VAD 域 diag） | 0.0045~0.0076 | 同量级（电子噪底 0.0013 float×4.096≈0.005 吻合） |

**结论**：S52 后 mode 0/2 在判停质量/回声/ASR 完整性上**无实质差异**（n=3，同脚本同音量）；曾经 S18 时代的 AGC 钉死问题在两模式下均未复现（用户现中等音量 + end 线 0.03 + 上攻停用三重缓解）。**维持 mode 0 现役**（mode 2 无优势，mode 0 硬件 AEC 在真回声场景仍有保险价值且为出厂默认）。顺带：用户确认悬浮窗已自行开启（prefs 无 false 条目=默认 true，relaunch 后 `PetOverlayService pref=true` 双窗口实证）。轮 B 第 2 轮未走 MCP 工具、第 3 轮 empty run retry×1——均平台 LLM 侧行为，与采麦模式无关，不入票。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（633 绿）；adb install -r 192.168.137.45:5555 Success；轮 A tail -n +34619 / 轮 B tail -n +34817 会话锚对齐；capture start source=7→9→7 三段实证
verify_claim: 探针回退后 armed 仅打过门块（S52 hack 收回）；mode 0/2 对照 3+3 轮全部完整收账无幽灵轮无切尾，mode 0 恢复现役；悬浮窗 pref=true 双窗口已加
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt, docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 大票 A/B 归档（用户拍板：当前效果可以）

S52 交接后立项排程中的两张候选大票归档：**大票 A（软件 AGC 补偿/限幅预压）+ 大票 B（流式 ASR+语义端点），不立项**。依据：S52 规避方案（中等音量，峰值压 0.1 float 下）用户验收通过——连续两轮完整句收账后日常使用效果可接受，codec AGC 烸增益问题在现行用法下不再构成障斑。

**阶段 0 侦察留档**（立项前只读排查，已到手即归档，未来重启此票直接从这里继续）：
- 板上 `/system/bin/tinymix` + `tinycap` 存在（amixer 无）——tinyalsa 工具链可用性已验证 ✅。
- 声卡拓扑：0=rockchipbt / **1=rockchiprk809co（rk809 codec，采麦通路）** / 2=hdmi / 3=USB UGREEN CM564。`tinymix -D 0` 报 Failed to open mixer 系 card 0 是蓝牙（无 mixer），非工具不可用——**重启此票从 `tinymix -D 1 contents` 查起**，在 rk809 控件里找 AGC/ALC 开关。
- 若开关存在→大票 A 退化为「一行 mixer 设置 + init.rc/持久化小票」；若无→才进软件补偿长路（增益轨迹盲估）。

大票 B 归档口径：S51 续听窗（总静音容忍 ≈1.6s）+ 按住说话直通已覆盖现行场景，回忆型 2s+ 停顿的语义端点判定不再追（S51 边界拍板已明确「不迁就」）。

```evidence
verify_cmd: adb shell which tinymix tinycap（存在）；cat /proc/asound/cards（4 卡拓扑）；tinymix -D 0（Failed to open mixer，card 0=bt 无 mixer）
verify_claim: 用户拍板归档（会话内确认）；S52 规避方案验收依据见 DEV_LOG 2026-09-18 S52 条（两轮完整句收账）
files_touched: [docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 S52：S19-1 噪声上攻通道停用（逐块 RMS 探针定案早切真因）

接 S50/S51：用户报告「聆听时间足够长，说档案时还在聆听但没识别到，且基本无停顿（词间 <1s）」——证词与「回忆型停顿」结论冲突，重新取证。用 AudioProbe（诊断构建：armed 时去掉 0.015 门全量落行）拿逐块 RMS 包络，两个实锤：

1. **持续元音被 plateau 通道中途判停**：用户发「啊——」2s+（VAD 0.15~0.30 稳定，波动 0.10~0.15），VAD 在 02.012 中途判停——S19-1 的 plateau 通道把「稳定电平」判为「AGC 钉死噪声」触发上攻（floor 0.35/块 → end=floor×1.4 数块内越过说话者电平）。**稳定是长元音与钉死噪声的共同特征，判据天然误伤**。
2. **自然语句尾段悬崖**（17:36 轮逐块）：…0.30→0.26→0.18→0.095→**纯噪底 0.0013**（100ms 内 -17dB），之后 5.9s 无任何语音能量跨过派发/PROCESSING——half-peak 通道（level<peak/2 在自然语音动态里常真）触发上攻级联同样成立。

**根因分层定案**：①S19-1 上攻通道在正常语音动态上误伤（判停阈值被自己抬过说话者电平）；②真并发现在：codec AGC 对热输入砸模拟增益（峰值 float 越大切得越早：0.19→切 6 字 / 0.16→8 字 / 0.073 元音→存活；声学噪底 0.0082 也被压到 0.0013=只剩电子噪底，约 30 倍砸益指纹）——用户最初「是否与收音设备有关」问对了，且用户证词（无停顿）推翻了我此前「回忆停顿」的误判（证伪时误把 AGC 方向建反）。

**处置**：①`NOISE_ATTACK_ENABLED=false` 停用上攻通道（当前板端噪底 0.0013~0.002，S23 NLMS 后泵态不再观测，上攻收益消失、误伤恒在；开关保留，泵态复发再审）；三个 S19-1 旧测重写为「有界行为锁定」（泵态拖尾由 max_utterance 8s 兑底、幽灵轮由 S19-2 空 ASR 续听兜底）+ 新增持续音回归锁。②用户侧验证：中等音量（峰值压在 AGC 触发线下）连说两轮完整句「查询2022年的合同档案。」「查询2021年的合同档案。」全部完整收账。③遗留：codec AGC 热输入砸增益是硬件/固件层问题（UNPROCESSED/VOICE_RECOGNITION 均躲不过），软件侧候选=软 AGC 补偿/限幅预压（大票候选），当下降音量用法规避。

**追加（mode=0 核实）**：用户设回出厂 mode=0 并重启（source=7）后实喊「查询2023年的档案」完整收账（33600 samples）——注意 Settings 只写 pref，采集源构造时固化，**改后需重启才生效**；另澄清常数表：板上 pref0→source=7（VOICE_COMMUNICATION）、pref2→source=9（UNPROCESSED），全日 source=7 即 mode=0（此前「用户设过 mode1」系常数表记误，cliff 与模式无关的结论不变）。悬浮窗 pref 当前 false（用户关闭，待定是否恢复）。

**追加二（判停窗回调 600ms，用户拍板）**：S50 判停窗 6→8 的两个根因（弱音节掉线、上攻级联）已被 end 线拆分与 S52 停用上攻解决，800ms 的额外 200ms 延迟不再必要——`SILENCE_END_TICKS` 8→6 回调，总静音容忍 = 600ms + 续听窗 1000ms ≈ 1.6s。演进史终态：8→4(08-29)→6(09-11)→8(S50)→6(S52 晚)。对账表补注 4/清单、KwsDefaults 注释、VadDetectorTest 锚（注入 provider/基础判停/pre-roll/byte 路径/quiet-domain 五处）同步。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（633 全绿，含 S52 重写 3 测 + 持续音回归锁）；AudioProbe 诊断构建（armed 全量 cap rms）板端逐块包络取证；adb install -r 后真机两轮完整句验证 + force-stop 重启后 mode=0 复验一轮 + 判停窗 600ms 回调后全锚重跑
verify_claim: 持续稳定音 30 块（3s）无中途判停（旧代码 ~2s 即切）；用户中等音量连续两轮「查询202X年的合同档案。」ASR 全文完整（36800/38400 samples），S51 hold/dispatch 链正常；上攻停用后泵态场景由 max_utterance 有界兑底；mode=0 重启生效后完整句复验通过；判停窗 600ms（S52 晚回调）下全锚绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt(探针去门诊断), app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-18 S50+S51 语音端点双修：判停窗/阈值拆分 + 断句续听

用户报障「总是提前判停，是否与收音设备有关」。三阶段取证闭环：

**取证一（S50，判停参数）**：板端 15:59 四连切实锤——轻声说话句尾弱音节 RMS 跌破 0.05 被计静音，600ms 计满切尾（「盘点当前有哪」「盘点当前有」「进行档案」「进行档案盘」，maxLevel 0.31~0.47 vs 大声完整句 0.83）。用户拍板 A+B 组合：判停窗 6→8 块（回桌面端对账值）+ 判停/判起阈值拆分（end 线独立 0.03）。<b>关键发现</b>：环境音自动校准没缺席（09-14 自动跑过 + 09-16 手动重校两次），但公式 `suggestVadSpeechLevel = p95×1.6 coerceIn(0.05,0.15)` 在安静域被下限钳死（p95=0.0018×1.6=0.0029→0.05），校准数学上给不出低于 0.05 的判停线——拆分是唯一出路。改动：VadDetector 新增 `SPEECH_END_LEVEL=0.03` + `speechEndLevelProvider`（判起线继续跟校准联动；恒 end&lt;start），SILENCE_END_TICKS 6→8；KwsDefaults 注释/对账表补注 4 同步演进史。效果：切尾点从第 7 字推到第 8 字（弱音节不再掉线）。

**取证二（排除 AGC 假设）**：正常音量对照轮（maxLevel 0.39 更响）反而切得更早（第 7 字）——<b>切尾位置=气口位置，与音量无关</b>；换采集源关 AGC 的路 S18 已实测不通（rk809 codec 级，VOICE_RECOGNITION 也钉死）。真因：固定静音窗判停吃不掉长句字组气口，且切尾改变 LLM 工具路由（盘点被当查询调 c_archive_query）。

**修复（S51 断句续听，用户拍板）**：判停片段不即派发——`handleVadEvent` 截流→累积+重启 VAD+静音块倒计时（`CONTINUATION_TICKS=10`/1000ms）；语音级块或 `vad.isSpeaking`（含判停静音尾）冻结倒计时；窗耗尽合并成单条 utterance 走 `onUtterance` 原语义派发。直通例外：按住松开 stopListening（松开=明确说完）与单测直注不经截流（15 个既有 VSM 测零改动）。坑：判停静音尾与续听窗共用音频流重叠计数，窗剩余&lt;判停窗时片段成型中途提前派发（新用例 2 实锤）——`isSpeaking` 期冻结解。backToIdle/startListening 单点清挂起（同 kwsPaused 纪律）。<b>边界拍板</b>：回忆型 2s+ 停顿不迁就（每轮延迟不可接受），按住说话是正解；续听窗 1000ms 容忍总静音≈1.8s 覆盖短语间气口。

真机：S51 hold/dispatch 链 3 轮全对（713ms 派发无残留），一口气长句「查询当前有哪些2021年」完整收账。

```evidence
verify_cmd: ./gradlew testDebugUnitTest assembleDebug（631 tests 全绿，含 S51 新增 4 测：hold 不即派发/续说冻结+双片段合并 20800 samples/cancel 清挂起/按住直通；VadDetectorTest S50 锚同步 6 处）；adb install -r 192.168.137.45:5555 + 板端日志取证（S51 continuation hold/dispatch 锚 + ASR text 对照）
verify_claim: S50：轻声弱音节 0.03~0.05 域计回说话（切尾点 7 字→8 字）；判起线校准联动零回归（S18/S19-1 用例全绿）。S51：带气口说话片段自动合并（hold→静音耗尽→单条派发）；cancel/超时/重唤醒清挂起无幽灵派发；按住松开零延迟直通；一口气长句 ASR 完整、延迟 = 判停 800ms + 续听窗 1000ms ≈1.8s（既定代价）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VadDetector.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsDefaults.kt, docs/voice-param-reconciliation-2026-07-28.md, app/src/test/java/com/xiaoduo/deskpet/voice/VadDetectorTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS40BlindWindowTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerS51ContinuationTest.kt(新), docs/DEV_LOG.md, implementation-notes.html]
```

## 2026-09-17 S48 Agent 全量拉取 + 会话闲置轮转 + 悬浮窗「创建新会话」

三项需求（grilling 一轮定案）：Q1=a 大 pageSize 一次拿全（失败退循环翻页）；Q2=惰性超时轮转——按消息 ts 记录，最后一条消息超 10 分钟无后续才在下次交互时新建（空会话无时间戳永不触发，杜绝空会话循环，用户拍板）；Q3=超时轮转完全静默；Q4=菜单「查看最近一轮」→「创建新会话」+ busy 置灰 + 成功气泡反馈。

#### 票 1：Agent 全量拉取
- 真机实测（curl + 板上 token）：`agent/list` 空 body 黔回前 10 条（信封 total:14/pageSize:10/pages:2，PageHelper 风格）；`{"pageSize":500}` 一把拿全 14 条
- `AguiClient.listAgents`：循环翻页聚合（pageNum 递增直到不足一页；20 页硬上限防服务端忽略参数死循环；401 透明重登后清空半卷从第 1 页重拉）；`listAgentsOnce` body 改 `{"pageSize":500,"pageNum":N}`
- `SettingsScreen` 选择器：限高 320dp 滚动列表（全量后不再撑爆页面）
- 坑：`chatNewSession?.invoke()` 在 runCatching 内产生 Boolean?，`getOrElse{false}` 后仍是可空——`?: false` 在 lambda 内先兜平

#### 票 2：会话闲置 10 分钟惰性轮转
- `SessionRules.shouldRotateForIdle(lastMessageTs, now, timeoutMs)`：纯函数；`ChatMessage.ts` 字段已有，零新增存储
- `ChatViewModel.rotateSessionIfIdle()`：静默版（不查 busy/无提示/存储异常降级不轮转），与 newSessionInternal 同构
- 触发点两处：文字 `send()`（upload gate 后、session 捕获前）+ 语音 `onUserUtterance`（置 voiceActive 守卫窗之前，rotate 后本轮消息入新会话/新 thread）
- resends 不轮转（旧会话内操作语义）

#### 票 3：悬浮窗菜单「查看最近一轮」→「创建新会话」
- `OverlayMenu` 行替换 + `canCreateNewSession` 置灰（busy 快照）
- Service→VM 双桥（chatInterrupt 同范式）：`chatBusyProvider={busy}`（菜单弹出时刻快照）+ `chatNewSession={newSessionFromOverlay()}`（busy 拒绝 false 双保险）；不进 APP，Service 侧 `showBubble(CHAT, "已开启新会话")` 反馈
- Activity 的 viewLastRound 哨兵机制保留未删（仅换菜单入口，surgical）

```evidence
verify_cmd: ./gradlew testDebugUnitTest（606 例全绿，+11：翻页聚合/401 重拉/分页 body 契约/超时判定 4 例）；真机：logcat `agent/list POST ... pageSize=500 pageNum=1` + 设置页可见超 10 个 agent（s48_agents.png）；chat send 空会话不触发轮转 ✓；悬浮窗菜单 dump 六项含「创建新会话」✓（s48 菜单 dump）。用户补验（2026-09-17 10:30）：② 菜单「创建新会话」板端日志实证 `menu: create new session, ok=true`（桥链路/busy gate/气泡链路通）；① 闲置轮转板端日志未捕获 rotate 记录（用户口头确认完成），规则层由 4 例单测 + 空会话不触发实测兜底
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/SessionRules.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientAgentListTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/chat/SessionRulesTest.kt
]
```

## 2026-09-17 S46 密集架页面导航（本地意图直连，不走平台）

需求：语音/文字「打开借阅/归还/盘点页面」→ 密集架 app 跳对应页面。平台侧注册 open_page MCP 工具不确定可行 → 改走本地意图分流（零平台改动），完全复用 S26-3 设备技能同款模式。

- 新增 `device/ShelfNavIntentMatcher`（纯 Kotlin，JVM 单测 20 例）：保守匹配——导航动词（打开/跳转/进入/切换/切到/去…）或页面名词 + 目标词（借阅/借书、归还/还书、盘点）三路取先现者；**含执行对象词（档案/文件/编号/盒…）绝不拦截**——「借阅档案A」必须走平台 MCP 工具链（防双控语义不破）
- `ShelfMcpBridge`：`NAVIGATE_PAGES = {borrow, return, inventory}` 白名单 + `navigate(page)` **同步** POST `{"Op":"mcpUiNavigate","page":...}`（区别 sync 的 fire-and-forget：导航需成败回执）；导航非 MCP 工具，不进 SYNCED_TOOLS
- `ChatViewModel`：文字轮 deviceCmd 后同级拦截 → `runShelfNavTurn`（零 LLM；成功「已打开XX页面」/失败「密集架联动不可用…」作为 assistant 终态上屏）
- `VoiceSessionManager`：S26-3 同款注入 `shelfNavMatcher/shelfNavExecutor`，命中走同一条分句/TTS 管线；密集架无自带播报 → **不置 turnSawToolCall**（正常 TTS，区别设备轮静音语义）
- `VoiceClientFactory`：按 `shelfBaseUrl` 非空装配（空=禁用零劫持）
- 密集架端（D:/mijijia 同步改动）：AppController 新增 mcpUiNavigate Op——borrow/return 走 searchPageRequest 跳检索页对应模式，inventory 走 openInventoryPage()（archives_inventory 同款）

边界：拦截后**不发云端**——「打开借阅页面」若发云端会被 LLM 误路由到 archive_borrow（执行语义）引发多余工具调用（S30 拦截语义同源）。

```evidence
verify_cmd: ./gradlew testDebugUnitTest --tests ShelfNavIntentMatcherTest（20 例全过）+ assembleDebug；密集架端 curl mcpUiNavigate borrow/return/inventory 三页真机跳转截图 + 非法 page 注入守卫（详见 D:/mijijia/docs/DEV_LOG.md S46 追加节）
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcher.kt,
  app/src/main/java/com/xiaoduo/deskpet/device/ShelfMcpBridge.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt,
  app/src/test/java/com/xiaoduo/deskpet/device/ShelfNavIntentMatcherTest.kt
]
```

## 2026-08-05 S8 UI 微调合集代码侧收口

### 会话背景
- 从 anzhuo4 死会话恢复（API 429 5 小时用量上限），加载上下文后继续
- 用户选择执行模式：串行关键路径（S8-3→S8-7）+ 派 opus critic 盲审
- 纪律：单测只增不减（基线 345），skipped=0，禁 mock/skip/删测；每守卫必须有变异锚测试

### 变更清单

#### S8-1 输入框视觉回滚两层+横线
- `ui/screens/InputBar.kt`：R3 单容器 → 两层独立 Surface + 层间 HorizontalDivider（outline 色 1dp）
- R3 功能修复全保留（行高 48、FunctionBar padding 4dp、isUp 快照、idle 粉底 gate、停止键、上传链）
- 纯视觉变更，无新单测

#### S8-2 图标白底
- `res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`：5 密度 PNG 替换（#FFFFFF 底 + 前景小朵形象）
- `res/values/ic_launcher_background.xml`：改 `#FFFFFF`
- 资源替换，无新单测

#### S8-3 TTS 中按住说话=打断并接续新一轮
- `voice/VoiceSessionManager.kt`：
  - `startListening`：speaking/processing 态按住（playWakeAck=false）→ cancelCurrentTurn → 进 LISTENING 采音
  - 3 处 sealed 回归守卫：consumeSentences 循环头 L490 + 合成后二查 L502 + 多轮迁移 L464
- `voice/VoiceSessionManagerBargeInTest.kt`：+2 测（PROCESSING 打断 4 断言 + SPEAKING 打断 stateFlow 闩）
- 测试 351→353

#### S8-4 气泡渲染 markdown 控制符修复
- `ui/overlay/BubbleTextStrip.kt`：裸 markdown 控制符剥离
- `ui/overlay/BubbleTextStripTest.kt`：+6 测（无空格标题/引用/加粗/斜体/行内代码/表格管道符）
- 测试 345→351（S8 开工前已完成）

#### S8-5 气泡文字改流式直出（撤 reveal 音频同步）
- `voice/VoiceSessionManager.kt`：
  - AG-UI delta 回调直接 bubble.show/append，与 TTS 播放解耦
  - 退役 playAndReveal/reveal 方法（RevealScheduler 类保留备查）
  - TTS 单句失败降级：气泡文字不受影响（delta 已上屏），仅跳过本句播放
- 无新单测（退役旧方法，delta 流式路径由现有 BargeInTest sealed 守卫覆盖）

#### S8-6 气泡「看不到开头」补救
- `ui/overlay/OverlayMenu.kt`：新增「查看最近一轮」菜单项（canViewLastRound 门控）
- `PetOverlayService.kt`：hasShownBubbleContent 标记 + playPetOnceThen companion 转发
- `MainActivity.kt`：handleViewLastRoundIntent + onNewIntent（EXTRA_VIEW_LAST_ROUND → ChatViewModel）
- `chat/ChatViewModel.kt`：pendingScrollMessageId 一次性事件（哨兵 `__last__`）+ consumePendingScroll
- `ui/screens/ChatScreen.kt`：LaunchedEffect(pendingScrollMessageId) 消费 → scrollToItem + listState 上提
- 无新单测（ChatViewModel 是 AndroidViewModel，项目无 Robolectric/mock 基建，纯 JVM 不可达；降级为真机取证）

#### S8-7 唤醒成功即时视觉反馈
- `voice/VoiceSessionManager.kt`：
  - `_wakeAckActive: MutableStateFlow<Boolean>` + 公开 `wakeAckActive` StateFlow
  - startListening(playWakeAck=true)：入口置 true + playPetOnceThen(JUMPING, WAITING)
  - transition()：SPEAKING + wakeAckActive → 跳过通用 TALKING 映射
  - wozaiJob finally：无条件 reset false（critic P2 修复）
  - cancelCurrentTurn：reset false
- `ui/screens/FullscreenVoiceScreen.kt`：LaunchedEffect 订阅 wakeAckActive，gate SPEAKING 映射
- `ui/PetView.kt`：playOnceThen 脚手架（上一会话已就绪）
- `PetOverlayService.kt`：playPetOnceThen companion 转发
- `voice/VoiceSessionManagerWakeAckTest.kt`：+3 测（时序/按住路径/cancel 复位）+ 1 增强
- 测试 353→356

#### S8-8 生成中等候态三点动效
- `ui/screens/ChatScreen.kt`：
  - TypingDots Composable（三圆点错相呼吸，900ms 周期，三角波 alpha 0.3↔1.0）
  - SENDING 状态替换原 CircularProgressIndicator + "小朵思考中…" 文字
- 无新单测（Compose UI 动画，重组不崩即为验收）

### 测试基线
- **356 绿 skipped=0 failures=0 errors=0**
- 增量：HEAD 345 → S8-4 +6 → S8-3 +2 → S8-7 +3 = 356
- 验证命令：`./gradlew.bat testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL

### 工作树
- `git diff HEAD --stat`：20 files, +595/-153
- 零 commit，所有变更在 working tree
- 真机 de61cd8 在线，apk 已装（app/build/outputs/apk/debug/app-debug.apk）

### Critic 轮次汇总
| 票 | critic | 结果 | P1 | P2 | P3 |
|---|---|---|---|---|---|
| S8-3 | 不逊 | 4 P2 → 修复 3，1 留档 | 0 | 4→1 | 0 |
| S8-5 | 不逊 | 1 P2 + 1 P3（首轮 critic 死亡，重派） | 0 | 1 | 1 |
| S8-6 | 不逊 | 1 P2 降级 P3 + 4 P3 | 0 | 0 | 5 |
| S8-7 | 不逊 | 1 P2 已修复 + 5 P3 | 0 | 0 | 5 |
| S8-8 | 不逊 | 2 P3 | 0 | 0 | 2 |

### 剩余事项（下会话真机验证）
1. **S8-5 P2**：delta callback check-then-act 竞态窗（L438 sealed → L444 bubble.show，cancel 可插入致幽灵气泡）——权限低，真机期顺修
2. **S8-5 P3**：BargeInTest 注释 "reveal 逐字查岗" → "delta sealed 拦截"
3. **真机逐票验收**（见 CONSTRUCTION.md 各票验收条）
4. **分票 git commit**（当前零 commit，建议每票一 commit + 真机证据截图附 commit message）
5. **S8-2 桌面截图**：浅色/深色壁纸各一，确认白底图标可读性（已抓 `C:\Users\Administrator\s8-2-desktop.png`）

```evidence
verify_cmd: ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL, 356 tests, skipped=0, failures=0, errors=0
files_touched: [
  app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt,
  app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt,
  app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/PetView.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt,
  app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt,
  app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt,
  app/src/main/res/mipmap-*/ic_launcher.png,
  app/src/main/res/values/ic_launcher_background.xml,
  app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStripTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerBargeInTest.kt,
  app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt
]
```