---
title: 本地独占仓库被整体清空后的五源分层取证重建——转录重放+APK反编译+winfr鉴别+证据链sha1仲裁+上下文金档，以「438单测全绿+APK对拍」为验收锚
date: 2026-08-19
last_updated: 2026-08-21
type: solution
category: workflow-issues
module: 仓库整体（git 工作树 + 构建资产 + 测试基线）
problem_type: workflow_issue
component: development_workflow
severity: critical
applies_when:
  - 本地独占仓库（无远程无备份）被整体删除/损坏，需要从零重建可构建工作树
  - 磁盘恢复（winfr/Recuva）遇 SSD TRIM 物理清零或簇复用，需要鉴别恢复产物真伪
  - 有历史 AI 会话转录（transcript）与证据链（sha1 日志）可作为重建来源与保真度仲裁依据
  - 只有最终构建产物（APK/二进制）可用，需反编译补齐源码与资产
  - 重建后需要一个可证伪的验收锚点（测试计数基线 + 构建产物对拍）逐层修复重建损伤
root_cause: missing_workflow_step
resolution_type: workflow_improvement
tags: [disaster-recovery, forensics, transcript-replay, jadx-decompile, winfr, evidence-chain-sha1, repo-rebuild, backup-discipline]
---

# 本地独占仓库整体丢失后的五源分层取证重建——以「可构建 + 单测全绿 + APK 对拍」为验收锚

## Context

2026-08-18 18:44（北京时间）之后、08-19 10:12 之前，`E:\desk-pet-android` Android 仓库被**整体清空**（含 `.git` 目录）。该仓库**无远程、无备份**，全部提交历史只存在于被删的 `.git` 里——典型的本地独占单点。删除窗口内证据链（`~/.claude/evidence/events-2026-08-18.jsonl` 哈希链）零记录；取证结论是「不是任何被记录的 Bash/Edit/Write 干的」，根因至终未定性（PowerShell 工具调用不进证据链，是排除不了的盲区之一；唯一开放线索是 `~/.codex/hooks.json` 里 19:38 出现的 clawd-cleanup 条目，未证实关联）。

删除窗口经多轮时间线校准后锁定（session history）：下界=北京 08-18 18:44（最后一次成功写入仓库的截图操作，证据链同刻戛然而止）；清空时刻≈北京 19:20（目录时间戳定格，距事故前最后一个开发会话收工仅 11 分钟）。事故前最后已知良好状态：HEAD=`7ec7cf0`（**已灭失的旧仓库** main 的 docs 提交——旧 `.git` 随事故灭失，此 SHA 在现仓库不可解析，仅作历史锚点）、427 测全绿 skipped=0、工作树净。删除特征指向程序式删除：回收站无仓库内容、VSS 无影子副本、E 盘 USN 日志未启用、SSD TRIM 使被删簇物理清零（session history）。调查初期曾被模拟器 logcat 时钟（慢 ~7.7 小时）误导错判窗口，以宿主机证据链为准后才校准（session history）。

在不动现场的前提下（先杀模拟器 qemu 进程、暂停机器上其他 Claude 会话防二次破坏），用五类互不信任的来源分层重建，在 `D:\desk-pet-recovery\merged\desk-pet-android` 拼出完整工作树，并以两次可证伪验收冻结成果：

- `./gradlew.bat assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`，**51 suites / 438 tests / skipped=0 / failures=0**（438 = 427 S9 基线 + 11 EdgeDockTest；逐 XML 亲算，非听构建自述）；
- 基线提交 `103a496`（本仓 main，231 files / 32942 insertions），产出 APK 90703733B 与事故前最终构建对拍。

**What Didn't Work（磁盘恢复路线全灭，逐条记录防后来人重踩）**：

1. **SSD TRIM 秒杀已删数据**。winfr regular 扫出 2694 个 MFT 记录，文件名/大小全对（PetOverlayService.kt 30399B 与证据链最终版尺寸吻合），但数据簇**全为零**——SSD 在删除后很快物理清零，任何工具不可恢复。删除后 E 盘还持续有写入（模拟器 qemu 写了几小时），进一步降低找回率。
2. **winfr regular 模式只回 MFT 记录**（元数据），不还原数据簇内容。「扫到了文件名和正确大小」不等于「找回了文件」；2832 文件「恢复」完成但 `.git` 0 文件（session history）。
3. **「恢复出非零内容」≠ 真文件**。「538/2694 文件有非零内容」一度是假希望：大于 1 簇的文件是簇被覆写后的乱码（ChatScreen.kt 43879B 看似黄金幸存者，实为垃圾数据）；只有 MFT 驻留小文件（<~1KB）为真，大于此的产物已全部回退，未混入重建树（部分细节 session history）。
4. **任何工具的转录里都没有 `cloud/*.kt` 的写入记录**（悬案）。cloud 四件套（AliyunAsrClient/VolcengineAsrClient/OpenAIAsrClient/OpenAIChatClient）08-12 20:24 前已存在，但所有会话转录与证据链均无其 Write/Edit 事件——这解释了为什么它们只能从 jadx 字节码人工回写，也是「转录重放不是万能的」的实证。
5. **并行重建树互有新旧，凭直觉取舍必错**（session history）：pi 会话并行重建的 207 文件树里 cloud 文件带 `cat -n` 行号污染；另一重建树里 ChatViewModel 停在 08-06 旧版。两树互有新旧，必须靠证据链 sha1 仲裁，不能凭树的新旧直觉取舍。

## Guidance

### 第一步：定验收锚，再动手重建

重建最大的风险是「拼出一堆看起来对的文件」。先把成功标准写成可证伪的两条：

1. **构建 + 既有测试基线全绿**：原仓库最后已知状态是 438 测 skipped=0/failures=0（有历史测试 XML 与 PROGRESS.md 双重记载），恢复树必须复现这个数——不是「能编译」，不是「大部分测试过」。
2. **产物对拍**：恢复树构建出的 APK 与事故前最后一版 APK（设备上还装着）在结构/资产层面一致。

有了锚，每一层重建损伤都会被构建/测试自然暴露；没有锚，损伤会以「行为 subtly 不对」的形式潜伏。

（**边界补注 2026-08-19**：这两条锚对**环境/密钥类**回归不可见——恢复树曾基线全绿且 APK 结构对拍通过，而 gitignored 密钥已整体缺席，直到真机语音轮才暴露。凡依赖本地秘密的链路，恢复验收须再加一条真凭证端到端。见 Related 盲区篇。）

### 第二步：按可信度分层取证，每层只信它能证明的

核心原则：**每个来源都标注「它能证明什么」，交叉验证后才采信**。本次实证的分层（L0 最可信，L5 最不可信）：

| 层 | 来源 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| L0 金档 | 事故当值会话上下文直写（发现事故第一时间落盘到**另一块盘**） | 6 个文件字节级精确（会话里刚 Read/Edit 过） | 只有上下文恰好持有的那几个文件 |
| L1 转录重放 | 全部历史会话 transcript（*.jsonl）重放 Write/Edit/Read | 「某时刻该文件内容是这样」；终态=最后一次成功写操作的叠加 | ①PowerShell 等不入转录的通道写的文件 ②转录被 compaction 截断的编辑 ③写后又被其他工具改过的文件 |
| L1b pi 转录 | pi agent 会话挖矿（write/edit/bash heredoc 三通道，相对路径需归一化） | 部分基线文件 | 与 L1 冲突时由 L2 仲裁 |
| L1c 陈旧快照 | `E:\review-s0`（S0 时代工程快照，session history） | 工程骨架（gradlew/settings/gradle.properties）与 sherpa AAR（48847529B 逐字节吻合） | 快照日后的任何演进 |
| L2 仲裁 | 证据链 sha1 对拍 | 「重建内容 == 当时真实落盘内容」（密码学级） | 只覆盖证据链记录过的文件（本次 hook 只挂 Bash\|Edit\|Write\|MultiEdit） |
| L3 APK 资产 | 设备/模拟器上已装的最终构建 | 二进制资产（音频/图片/模型/配置 JSON）逐字节保真 | 源码（APK 里是编译产物，不含测试） |
| L4 jadx 反编译 | 同上 APK 反编译 | 最终发布版的**逻辑结构**（类/方法/字符串全集，含 BuildConfig 常量——打包期注入的密钥也可从此取回，08-19 盲区篇实证扩展） | 注释、原始格式、测试代码、被优化掉的细节 |
| L5 winfr | 磁盘恢复 | 仅 MFT 驻留小文件（<~1KB）为真，且须 sha1 对拍确认 | 大文件内容（SSD TRIM 后数据簇为零或簇复用垃圾） |

落地动作（按序）：

1. **发现事故第一时间固化当值上下文**：事故时发现仓库空的那个会话，其上下文持有的文件是唯一的字节级来源——立刻把 Read 过的全文、刚做过的编辑、票据/验收证据写到**另一块盘**（仓库所在盘已不可信）。本次固化了 5 个源码文件 + 案卷 + 当日票据 + implementation-notes 新增条目。
2. **写重放引擎，不要手工抄**。836 份转录 → 47578 个工具事件 → 210 个文件，手工不可能。`reconstruct.py` 的两遍法：第一遍收集全部 Write/Edit/Read 工具事件（含嵌套 `{type:'text', file:{...}}` 形态——初版脚本漏认此形态，补后多回收 9 个 .kt），按时间戳排序；第二遍重放——Write 直接存 content；Edit 优先用 toolUseResult 里的 `originalFile` 全量快照做 old→new 替换（快照与 old 不匹配时退化为「至少保住编辑前快照」）；全量 Read 的内容作为该时刻终态候选；partial Read（带 offset/limit）跳过并记档。输出逐文件溯源报告（字节数/最后事件时间/最后事件类型）。
3. **用独立证据链做仲裁**。当有多个重建来源（本次：Claude 转录树 vs pi 转录树）冲突时，`arbitrate.py` 从证据链 events-\*.jsonl 取每个文件**最后一条** Edit/Write 事件的 sha1 作金标准逐文件裁决。本次：70 个文件经 sha1 验证「重建=当时真实内容」，16 个与 pi 树一致，0 冲突。没有 sha1 记录的文件列入 unknown 人工复核，不猜。
4. **二进制资产只从 APK 解包**：pet_states.json / spritesheet.webp / wozai\*.wav / KWS 模型 / mipmap 图标，直接解包最终 APK（`pm path` 拽出设备上的 base.apk），不经任何「重建」。
5. **jadx 补转录盲区**：转录没有的文件（cloud 四件套悬案）或方法（VadDetector.currentMaxLevel），从最终 APK 反编译人工回写为 Kotlin；顽固方法用 `jadx --comments-level debug` 拿寄存器级伪码（OpenAIChatClient.chat 首轮被跳过 742 条指令，debug 注释级重跑才解出）。回写后**逐行与字节码对拍逻辑**，注释只能重写（反编译无注释，须如实标注）。
6. **winfr 产物一律先鉴别再采信**：见 Examples ③。winfr 的交互确认也有坑：Git Bash/PowerShell 管道喂 `y` 均失败（winfr 直接读控制台），最终用 WScript.Shell SendKeys 模拟真键盘启动成功（session history）。

### 第三步：逐层修复重建损伤，让构建系统当探测器

重建树第一次构建必然失败——这是特性不是 bug。构建管线的每个阶段只暴露一类损伤，按暴露顺序修即可（本次五次构建尝试全记录见 Examples ②）。纪律：

- **每类损伤先判「正本在哪」再动手**：合并期脚本故障会产生「正本存在 + 垃圾副本」的重复定义（本次 OnceGate / XiaoduoDimens），修是删垃圾不是改垃圾。
- **测试文件损伤 = 忠实重写 + 测试数对账**：见 Examples ④。
- **行为断言失败 ≠ 重建错**：可能是重放到了**历史版本**而断言来自更早的行为约定。考古方法见 Examples ⑤。
- **保持基线不加测**：恢复期的目标是复原，不是改进。发现的测试覆盖缺口（本次：某 else 分支疑失覆盖）记 P3 票据留给后续，不加新测试抬高基线。

### 第四步：冻结与止血

- 全绿后立刻 `git init` + 基线提交（本次 `103a496`，本仓 main）——把恢复成果从「一堆易碎文件」变成「有锚点的历史」。
- **本地独占 = 单点是本次事故的直接教训**：恢复完成后第一件事是建远程（哪怕私有库）或异盘镜像，并把「每日 push」写进纪律。本次恢复当即将成果推送至 GitHub 私有库（wzejia/desk-pet-android，本仓 origin）——推送前做密钥泄漏扫描（无明文密钥、无敏感文件入库，`.scratch/`、`local.properties`、`secrets/` 均 gitignore）——**但 gitignore 同时意味着无恢复路径**：push/clone 天然不携带这些文件，灾难恢复清单必须把本地秘密文件逐项另列（异地备份/密码库/保全产物三选一），不能笼统「从 git 恢复」。见 Related 盲区篇。
- 回迁技巧（session history）：整树迁回原位置前，先把目标目录里残留的会话工件（`.claude/`、`.scratch/`）移到临时 hold 目录，用 `git clone` 回迁（不带 build 产物）后再归还工件，不直接覆盖。
- 环境事实随树归档：`local.properties` 的 `sdk.dir=E:/Android/Sdk`（开发机 Android 工具链在 E 盘、C 盘满载 (auto memory [claude])）；构建前必须 export JDK 17（`export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH"`，Git Bash 下 `./gradlew.bat`）。

## Why This Matters

- **本地独占仓库的整体丢失不是理论风险**。一次未知原因的清空（根因至今未定性）就足以抹掉全部历史；而「有会话转录 + 有设备上的 APK + 有证据链」这三件套，在没有任何传统备份的情况下把 231 个文件/32942 行恢复到 438 测全绿。方法论比运气重要。
- **分层可信度是防二次伤害的关键**。重建期最大的敌人不是缺来源，而是把不可信的来源当可信的混进树里（winfr 簇复用垃圾、合并期 cat 失败输出、历史版本断言）。每类混入都会产生「看起来对」的文件，只有验收锚（构建绿 + 测试数对账 + sha1 仲裁）能系统性逼出它们。
- **agent 工作流自带取证资产**：会话转录（全部工具调用全文）、证据链（落盘 sha1）、设备上的最终构建，三者构成传统备份之外的「意外冗余」。了解这些资产的存在与盲区（PowerShell 不入证据链、compaction 截断、转录无写入记录的悬案文件），事故时才能把恢复从「祈祷磁盘恢复」变成「工程学问题」。
- **测试基线是恢复质量的唯一硬通货**。438 这个数是原仓库用数周开发沉淀的行为规格；恢复树复现它，等价于证明「行为层面无损」。「编译过了」证明不了任何事（本次 #3 构建就编译全过，仍藏着一个历史版本断言）。（**边界补注 2026-08-19**：等价性只覆盖测试可见的行为——gitignored 密钥丢失时基线照常全绿、对拍照常通过，环境类缺口只有真凭证端到端能暴露，见盲区篇。）

## When to Apply

- 任何**本地独占仓库**（无远程/无备份）整体丢失或大面积损坏：直接套用分层表 + 验收锚。
- 部分文件损坏但 git 历史尚在时：不需要全套取证，用 git 恢复即可——但「测试数对账」和「构建当探测器」两条仍适用。
- 用了 AI 编码 agent 的项目：事故发生时就拥有转录/证据链资产，值得**事前**了解它们的位置与盲区（本次盲区清单：PowerShell 通道、compaction、hook 未覆盖的工具）。
- **事前预防（最重要）**：本条目的真正价值是劝你在事故前行动——本地独占仓库今天就建远程或异盘镜像；事故后方法论再好，恢复也是以天计的工作（本次从事故到全绿约一天，另加后续迁回/复跑）。

## Examples

### ① 五源分层决策表（本次实证的可信度排序）

| 层 | 来源 | 本次产出 | 采信条件 |
|---|---|---|---|
| L0 金档 | 事故当值会话上下文直写（`C:\Users\Administrator\desk-pet-recovery\2026-08-18\`） | EdgeDock.kt / EdgeDockTest.kt / PetOverlayService.kt / OverlayMenu.kt / OverlayClamp.kt + 案卷，共 6 件**字节级精确** | 无条件采信（发现事故第一时间固化到异盘，晚了上下文就随会话丢了） |
| L1 转录重放 | 836 份会话转录 → 47578 事件 → 210 文件（恢复工作区的 `reconstruct.py` → `reconstructed/` + 逐文件溯源报告） | 源码与文档主力 | 默认采信，但逐文件标注最后事件时间；与 L2 冲突时让位 |
| L1b pi 转录 | pi agent 会话挖矿（`mine_pi.py`） | 部分基线文件 | 与 L1 冲突时由 L2 仲裁（本次 16 文件与 L1 一致，0 冲突） |
| L1c 陈旧快照 | `E:\review-s0` S0 时代快照（session history） | gradle 骨架 + sherpa-onnx-1.13.4.aar（48847529B 逐字节吻合） | 与现行构建链对拍（wrapper 版本/插件版本一致才采信） |
| L2 仲裁 | 证据链 sha1（`arbitrate.py`） | **70 文件**「重建=当时真实内容」密码学级确认 | 金标准：证据链最后 sha1 匹配谁谁就是终态；都不匹配 → unknown 人工复核 |
| L3 APK 资产 | emulator-base.apk（S10 最终构建，`pm path` 拽出） | pet_states.json / spritesheet.webp / wozai.wav(25964B) / wozai-aliyun.wav(22124B) / kws-model 5 文件 5.3MB / mipmap 图标 | 二进制资产唯一可信源，直接解包 |
| L4 jadx | 同上 APK（另 device-base.apk=S9 对照） | cloud 四件套 + Message + VadDetector.currentMaxLevel 等转录盲区件 | 逻辑逐行与字节码对拍；注释标注「重写」 |
| L5 winfr | D: 盘已删文件 | 仅 App.kt / gradle 小文件 / local.properties / keywords.txt 等 MFT 驻留小件 | **必须过 ③ 鉴别**；大文件产物一律视为垃圾 |

### ② 五类重建损伤 → 修复映射（五次构建尝试全记录）

| 构建 | 结果 | 暴露的损伤层 | 修复 |
|---|---|---|---|
| #1 | FAIL mergeDebugResources | **编译产物误当源码**：`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` 是 APK 解包出的二进制 AXML | 重写为文本 adaptive-icon（background 指向 `@color/ic_launcher_background`(#FFFFFF)，foreground 指向 `@mipmap/ic_launcher_foreground`），与二进制版字符串池对拍确认结构一致 |
| #2 | FAIL compileDebugKotlin | **合并期垃圾副本**（脚本把 `cat` 失败输出写进目标文件，造成重复定义）×2 + **转录盲区缺失件**×2 | （以下源码路径均相对 `app/src/main/java/com/xiaoduo/deskpet/`）删 `voice/OnceGate.kt`（正本已在 `AudioCapture.kt:177` 的 `internal class OnceGate`，语义与 jadx 一致）；删 `ui/theme/XiaoduoDimens.kt`（正本已在 `Theme.kt:47` `object XiaoduoDimens`）；`cloud/OpenAIChatClient.kt:99` 补 `data class Message(val role: String, val content: String)`（jadx 元数据证实原本就声明在此文件内）；`voice/VadDetector.kt:52` 补 `fun currentMaxLevel(): Double = maxLevel`（调用点 `VoiceSessionManager.kt:340`） |
| #3 | FAIL compileDebugUnitTestKotlin（**主源码集已通过**） | **winfr 截断文件**：LogRotatorTest.kt 截断于 30 行（唯一来源，任何转录/pi/jadx 均无全文——APK 不含测试） | 忠实重写 + 测试数对账，见 ④ |
| #4 | FAIL（编译全过，438 测跑起，437 绿） | **陈旧断言**：重放得到 08-05 版测试，行为已于 08-10 回退 | 考古重写，见 ⑤ |
| #5 | ✅ GREEN | — | `BUILD SUCCESSFUL in 34s` + **51 suites / 438 tests / skipped=0 / failures=0**（逐 XML 亲算） |

模式总结：资源合并 → 主源码编译 → 测试编译 → 单测行为，四层损伤由构建管线**依序**暴露，每层修完才见下一层——不要试图一次找全。

### ③ winfr 产物真伪鉴别规则（实证所得）

- **规则一：MFT 驻留 ≈ 唯一真源**。小于 ~1KB 的文件内容直接驻留在 MFT 记录内，winfr 恢复出来为真；大于此的文件 winfr regular 只还原 MFT 元数据（文件名/大小对≠内容对）。
- **规则二：SSD 上「数据簇全零」是终局**。2694 条 MFT 记录数据簇全零 = TRIM 已物理清零，不必再试其他磁盘恢复工具。
- **规则三：非零 ≠ 真内容**。大文件恢复出的非零内容是**簇复用**（别的文件后来写占了该簇），混进重建树就是污染源。本次大于 1KB 的 winfr 产物已全部回退。
- **规则四：sha1 对拍是最终裁判**。`verify_winfr.py`：对恢复产物中前 512 字节非零的文件，与证据链该文件**最后一条** Edit/Write 事件的 sha1(12hex) 对拍——对得上才标 VERIFIED 采信，对不上一律不信（含「大小看着对」的：PetOverlayService.kt 30399B 尺寸与证据链吻合，内容全零，假）。

### ④ 截断测试的忠实重写 + 测试数对账（LogRotatorTest，6 测）

处境：`app/src/test/java/com/xiaoduo/deskpet/util/LogRotatorTest.kt` 唯一来源是 winfr 恢复版，截断于 30 行；APK 不含测试，jadx 帮不上；任何转录/pi 均无全文。

做法：

1. **幸存原文逐字保留**：前 3 个测试（`fileNameFor produces canonical name` / `dayOf formats epoch millis in given zone` / `isLogFile matches only canonical names`）按恢复版原样保留，第 3 个仅补全截断处的收尾括号。
2. **丢失部分按主类 KDoc 语义新写**：后 3 个（`expiredFiles deletes only days before keep window` / `expiredFiles honors custom keepDays` / `expiredFiles leaves non-log and unparseable names alone`）依据被测主类 LogRotator 的 KDoc 与实现语义重写。
3. **测试数对账（关键防自欺步骤）**：两条独立记载交叉锁定总数——PROGRESS.md:360 记「LogRotatorTest 6」+ 转录中历史测试结果 XML `tests="6" skipped="0"`。重写后恰为 6 测（文件现存 6 个 `@Test`，已核对），不多写不少写。
4. 结果并入基线：438 总数因此复原，无 skipped。

### ⑤ 陈旧断言考古（emptyAfterStripFiresNothing：转录里的 08-05 版 vs 08-10 行为回退）

现象：构建 #4 编译全过、438 测跑起、437 绿，唯一失败 `VoiceSessionManagerTranscriptTest.emptyAfterStripFiresNothing`。转录重放得到的是 **08-05 原版**：ASR 返回「小朵小朵」→ `awaitStatus("未检出有效指令")`。但仓库 08-10 做过二次根因修复：纯唤醒词 utterance 改走 `backToIdle("wake-only utterance")` 让 KWS 接管、不再重播「我在」（重播在真机 8 轮实证中形成「ASR→strip空→重播」死循环）——修复后该路径**无 status 可等**，旧断言永远等不到。

考古链（此类问题的通用排查顺序）：

1. **先怀疑「重放到了历史版本」而非「重建错了」**：转录重放的终态=最后一次**被转录捕获的**写操作；DEV_LOG 08-10 条目记「2 测试断言更新为新行为」，但该编辑本身不在任何转录中（疑落 PowerShell 盲区/compaction）——转录终态在这里停在 08-05。
2. **用最终 APK 仲裁行为侧**：apk-src2（S10 最终构建反编译）的 VoiceSessionManager 中文字符串全集证实最终分支与 merged 树一致——`VoiceSessionManager.kt:466-472` 现行为 `backToIdle("wake-only utterance")`（08-10 注释在码）。
3. **按幸存同族测试的惯用法重写断言**：同仓库 `VoiceSessionManagerWakeAckTest.pureWakeWordUtteranceReturnsToIdle`（:352）是 08-10 更新版且幸存，照其模式重写——`awaitState(IDLE)` + sink 零事件 + `requestCount==1`（含「删回 IDLE 分支→超时红」变异锚注释）。
4. **文档交叉确认**：`docs/solutions/logic-errors/strip-empty-wake-word-dead-end-replay-ack.md` L22 历史标注明确记载「本篇解法行为已被 2026-08-10 二次根因修复整体回退」——solutions 库的历史标注在考古时直接省掉一轮真机复现。

附带纪律：考古中发现的 else 分支（纯标点无唤醒词 → `status("未检出有效指令")`，`VoiceSessionManager.kt:473-477`）在 08-10 后疑失直接测试覆盖——属原仓库既状，恢复期不加测（保持 438 基线），记 P3 票据留后续。

---

**恢复完成定义（2026-08-19 终态）**：恢复树全绿 ✅；基线提交 `103a496`（本仓 main）✅；迁回 `E:\desk-pet-android`（git clone 回迁）并复跑全绿 ✅；GitHub 私有库推送 ✅（wzejia/desk-pet-android，本仓 origin）；事故调查结论落档仍为待办（详见 `C:\Users\Administrator\desk-pet-recovery\HANDOFF-RECOVERY.md` §6/§9）。

## Related

- `docs/solutions/workflow-issues/parallel-builders-single-device-mutex.md` — 同属事故类文档（设备串台事故）；其「运行中 agent 的 transcript 是 0 字节占位」记载与本篇的转录重放取证互为注脚（transcript 作为事后取证材料的能与不能）。
- `docs/solutions/logic-errors/strip-empty-wake-word-dead-end-replay-ack.md` — 本篇 Examples ⑤ 的行为考古锚点（其 L22 历史标注记载了 08-10 回退史实）。
- `docs/solutions/workflow-issues/platform-sse-zero-chars-server-side-diagnosis.md` — 证据链仲裁方法论近邻（三段证据判定真相 vs 本篇多源互证仲裁）。
- `docs/solutions/workflow-issues/wm-size-matrix-privacy-and-page-identity.md` — 取证纪律（证据采集/取舍/防污染）方法论相邻。
- `docs/solutions/ui-bugs/material3-121-modal-drawer-scrim-back.md` — 「反编译取 ground truth」微技术共享（javap vs jadx），弱相关。
- `docs/solutions/workflow-issues/unanchored-code-silent-loss-in-rebuild.md` — 同事故盲区家族姊妹篇（代码侧：无测试锚的常量/分支静默丢失双案，S11 修复）。
- `docs/solutions/workflow-issues/gitignored-secrets-lost-in-rebuild-recovered-from-evidence-apk.md` — 同事故盲区家族第三案·非代码变体（gitignored 秘密：git 路线恢复结构性不含，从保全 APK 反编译 BuildConfig 取回）。实证本篇五源清单与验收锚的边界——「五源」应读作「五源 + 已知盲区」。
