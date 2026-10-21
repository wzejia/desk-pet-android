# 姊妹仓索引 — desk-pet fork（rk3288 移植线）

> 新建于 port 4cap 战役（2026-10-20，port/t3-docs 车道）。

## fork 定位

- 路径：`E:/desk-pet-rk3288`（rk3288 移植线：minSdk25 + desugar，与上游 E:/desk-pet-android **非同一对象库**，提交 hash 互不解析）。
- fork HEAD：`7bef28c`（747 绿）。工作区**有未提交态**（probe4 三文件），**读取一律 `git show HEAD:<path>`**，勿当工作区文件读。

## 与本战役关系

port 4cap 战役（分支 `port/baseline-4cap-upstream-47b240a`）将 fork 侧 4 项能力反哺上游：采麦层三合一（ZeroBlockDetector/SourceDrift/自愈重启/SoftwareAgc）、Tap-to-Talk 交互、docs 验证档。**战役已于 2026-10-21 闭环**：评审+修复轮 800 绿，合入 main（merge `678ea8f`）并推送 origin。fork 侧关键锚点：

| 锚 | 内容 |
|---|---|
| `7bef28c` | fork HEAD（747 绿） |
| `63a79ca` | HOTFIX-2 采麦地基（ZeroBlockDetector） |
| `a0944a4` | S57 SourceDrift |
| `e0b5c36` | 自愈重启钩 |
| `13ec3f9` | MIC 第 4 档（②C 裁决不带入） |
| `04e8015` | SoftwareAgc |
| `e0e2eb2` | Tap-to-Talk（VSM 守卫 + VoiceTapRouter + UI 三处） |
| `315a860` | USB 音频诊断记账 |

## fork 侧债务（记录，不代修）

- fork AGENTS.md:48 基线数 618 过期（实际 747）。
- fork 侧 evidence 命令未带 JDK PATH 前缀（搬运档注记已声明，本仓口径见 PROGRESS.md「单测基线口径」行（验证必带 JDK PATH 前缀 + --rerun-tasks））。
- probe4 探针三文件未提交（战役资产，禁 checkout 回退）。
