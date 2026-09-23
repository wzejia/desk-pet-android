# AGENTS.md — 小朵 Android 项目级规则

> 每个会话开工前先读本文件 + `PROGRESS.md` + `CONSTRUCTION.md`；**动模拟器前先读根目录 `EMULATOR.md`**。
> 执行环境：pi（无子智能体，质量循环降级为串行双角色）。Claude Code 会话用 `CLAUDE.md`（子智能体编排协议版）。
> 全局规则（代理/编码/技能路由）在 `C:\Users\Administrator\.pi\agent\AGENTS.md`，本文件只放项目事实。

## 项目一句话

小朵 Android 版：原生 Kotlin 单 App——**App = 对话式助手主界面**（平台 AG-UI 链路），
**悬浮窗 = 桌面陪伴**（手机精灵式形态 + 气泡回复 + 「小朵小朵」唤醒词语音对话，半双工）。
M1（悬浮桌宠本体）与「助手化」大切片（S0-S8）均已收口；后续工作见 `PROGRESS.md` 接手指引。

## 权威文档层级（冲突时以上层为准）

1. 产品行为：`docs/plans/2026-07-30-002-feat-xiaoduo-assistant-app-plan.md`（本轮 Product Contract）
   + `docs/plans/2026-07-29-001-...`（M1 基线，未推翻部分仍有效）
2. 协议/语音参数：桌面仓 `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（**只读**，镜像 `docs/m1-dev-kit/`）
   + `docs/voice-param-reconciliation-2026-07-28.md`（语音参数对账表，M3 验收依据）
3. 技术蓝图：`docs/spec/assistant-app.spec.md`
4. 施工清单：`CONSTRUCTION.md`（票据从这里出）
5. 机构解法：`docs/solutions/`（按类目组织 + YAML frontmatter，实现/调试前可检索同类既往坑）
   + 共享领域词汇：`CONCEPTS.md`（采麦流/KWS 常开/半双工四态/意愿登记制/变异锚等，ce-compound 增量维护）
6. 决策留痕：`docs/adr/`、`docs/MASTER-PLAN.md`、`docs/dev-handbook/06`

## 每次会话的工作循环

1. 读 `PROGRESS.md` + `CONSTRUCTION.md` 定位下一张票，写 ≤10 行开工回执再动工
2. TDD：纯逻辑（协议消费器/分句/VAD/帧表）一律纯 Kotlin + JVM 单测先行；Android 层只做 IO
3. 完成 = 可自动验证：单测数 / dumpsys / screencap / 日志原文，不接受「搞定了」
4. **四类事件立即追加 `implementation-notes.html`**：设计决策 / 有意偏差 / 权衡取舍 / 未决问题
5. 每日（或每会话）追加 `DEV_LOG.md`；每完成一项立刻更新 `PROGRESS.md`
6. Spec 漂移即改：实现偏离 `docs/spec/` 时，要么改代码对齐，要么改 Spec 并在 notes 里记原因
7. 跨会话交接：更新 `PROGRESS.md`「接手指引」段

## 环境硬事实（实测，勿重新调研）

- 构建：`export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH"` 后 `./gradlew.bat assembleDebug testDebugUnitTest`
- 真机：OPPO PJD110（ColorOS），adb serial `de61cd8`，1440×3168 @640dpi
- 模拟器：`xiaoduo_emu`（API34 google_apis x86_64，serial `emulator-5554`），WHPX 加速；SDK/AVD 在 `E:\Android\Sdk`/`E:\Android\avd`；**操作手册见根目录 `EMULATOR.md`（用前必读）**；免真机锁、多会话可并行；声学与 ColorOS 行为仍走真机
- adb：`/e/Android/Sdk/platform-tools/adb.exe`（SDK 权威已迁 E 盘）；Git Bash 下先 `export MSYS2_ARG_CONV_EXCL="*"` 防 MSYS 改写设备路径（比包引号可靠，2026-08-06 实证）——注意它连本地路径一起禁转，install/pull 的本地路径须写 Windows 风格 `E:/...`
- ColorOS：禁止 shell 授悬浮窗权限、禁止 `am startservice` 拉非导出服务——权限与开关走 App 内真实用户路径（可 `input tap` 配合 uiautomator dump 定位）
- 内网：平台 10.0.18.166（8080 登录 / 443 反代）、ASR 10.0.18.97:9802、TTS 10.0.18.97:9803；**必须 NO_PROXY 直连**；443 自签证书按 host 白名单信任
- 平台凭据：`.scratch/secrets/platform-account.json`（已 gitignore，不入库不引用明文）
- 桌面仓 `E:\desk-pet-migration\desk-pet` **只读**（仅知识包文档允许回写）

## 基线（不可退）

- 单测基线 **656 个**（2026-09-23 晚，S65 后；此前 652=S54~S64 时代）；skipped=0；新增只增不减；验证 `./gradlew.bat testDebugUnitTest --rerun-tasks`
- `assembleDebug` 绿；spritesheet.webp MD5 `53c81ce652518f7fc7bf70554f363f54`；pet_states.json SHA256 `60b45124…4366`（S13 起 Android 行为有意偏离桌面镜像，见 implementation-notes 2026-08-24）

## 当前状态（2026-09-23 晚，S65 后对齐）

- **S50~S65 已全部收口并全合 main**（代码至 `1723f59`，656 绿；文档收口提交在其上）：语音链噪声域加固 + 多唤醒词 + 关键词治理
  + **S65 回声纪元守卫**（盲窗回灌滤「我在」回声；s54 分支/worktree 已清场）。逐票速览见 `PROGRESS.md` 状态段。
- **关键词现状**：唤醒词=小朵小朵/你好小朵/小度小度；打断词已全禁（S63/S64）——打断走唤醒词（S12 链路）或双击。
- **两板矩阵**：测试机=desk-pet S65（b2df043c，⚠️ prefs 事故重建后**待重填 password+三模态 platform**）
  +mijijia xiaoduo 头；生产板=desk-pet S63+**mijijia 同事 replacement-skin 包（无 MCP 联动链，
  恢复需刷 xiaoduo 包待用户确认）**。
- **平台注意**：10.0.18.166 平台曾出现 chatAgent 故障致语音轮 SSE 断流（HTTP 200+控制事件后 ~2.2s
  空手关流）——**切换 Agent 即恢复**；全案（含双线对照/pktmon/tcpdump 方法论）见 `docs/solutions/workflow-issues/platform-sse-zero-chars-server-side-diagnosis.md`。
- 双板 adb：`C:/platform-tools/adb.exe`（SDK E 盘 adb 为历史路径）；MSYS 下 install/push 本地路径须 `E:/` 风格；
  **改板端文件用 run-as 相对路径（`shared_prefs/...`）+ 写回后读回校验**（prefs 事故教训见 solutions）。
- 挂账清单见 `PROGRESS.md` 状态段（验收/生产板升级/文案/底噪/快语速/prefs 重填）。
- 交接文档：`C:/Users/Administrator/AppData/Local/Temp/HANDOFF-s64-closeout.md`（Temp 易失，重要结论以
  DEV_LOG/PROGRESS/solutions 为准）。

## 历史状态

- 逐日收口记录（S13~S39 等）不再在此堆叠，权威历史见 `PROGRESS.md`（票级）与
  `DEV_LOG.md`（日级）；本节只保留最新一节。

