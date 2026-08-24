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

- 单测基线 **460 个**，skipped=0；新增只增不减；验证 `./gradlew.bat testDebugUnitTest --rerun-tasks`
- `assembleDebug` 绿；spritesheet.webp MD5 `53c81ce652518f7fc7bf70554f363f54`；pet_states.json SHA256 `60b45124…4366`（S13 起 Android 行为有意偏离桌面镜像，见 implementation-notes 2026-08-24）

## 当前状态（2026-08-24）

- **S14 全屏语音页改版+「我在」可视化 ✅ 收口**（2026-08-24 真机现场验收全过）：「我在」气泡
  +全屏大字（不进消息流）；三段式（字幕开关进场/宠物 0.62×屏宽/状态区下 1/4）；底部抽屉双键；
  字幕富文本（共享 MarkdownContent）+滑动+贴底跟随；聆听声波帧时钟插值治卡顿。
  **白屏销案**（08-18 观察票）：configChanges+singleTask，旋转零 churn。460 测绿，证据 v19。
- **S13 形象动作改造收口**：平时/聆听/处理/出错 = idle 行第 0 帧完全静止，说话 = idle 行 6 帧
  @120ms/帧，唤醒跳/点击挥手全取消（用户逐项拍板，原型先行）；不换素材。455→457 测绿，
  模拟器静止/说话双证据（`docs/verification/v18-s13-pet-motion/README.md`）。

## 当前状态（2026-08-18）

- **S9 唤醒应答音色跟随收口**：应答「我在」四层兜底链（动态缓存→厂商资产→wozai.wav→直进聆听），播放零网络，预取=保存即合成+启动补漏；真机六 Case 用户试听全过（证据 `docs/verification/v15-wakeack-voice-follow.md`）；372→427 测。遗留：全屏模式概率性白屏观察票、volc/openai 资产待 key/端点——见 `PROGRESS.md` 接手指引。

## 当前状态（2026-08-17）

- **模拟器调试基建完成（不接真机可用）**：SDK 迁 E 盘 + AVD `xiaoduo_emu` + abiFilters 双 ABI（arm64-v8a+x86_64，用户批准）；模拟器验收全通（悬浮窗/KWS x86_64 库零异常/云端对话 1099ms），372 测绿。手册 `EMULATOR.md`，证据 `docs/verification/emu-2026-08-17/`。

## 当前状态（2026-08-14）

- **S0-S8 全部收口**：代码+真机验收+分票提交三清；多厂商云端 ASR/TTS（OpenAI 兼容/火山/阿里）+ TTS 流水线/AudioTrack 复用已收口提交（见 HANDOFF.md）
- **08-14 已验收提交**：①悬浮窗长按菜单「打断」（VSM 非 IDLE 门控→cancelCurrentTurn）；②云端密钥内置包（secrets/cloud.properties→BuildConfig；NetConfig 默认 cloud 开箱即用，三 key SP→内置回退）。372 测绿，真机双验收过（证据 docs/verification/v14-interrupt-menu-and-cloud-builtin.md）
- **剩余**：观察项（B2 主观项/盲测组 D 挂起/push-to-talk 冷启动低优）——详见 `PROGRESS.md` 接手指引段
- 详细架构/包结构/子智能体编排见 `CLAUDE.md`
