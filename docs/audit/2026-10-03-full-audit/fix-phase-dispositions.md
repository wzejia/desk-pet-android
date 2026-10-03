# P2 修复阶段处置记录（2026-10-03，继 unified-findings.md）

修复范围：P2 43 条 −「分发形态」3 条（SEC-1/SEC-4/SEC-5）= 40 条，三路子智能体并行修（worktree af-voice/af-ui/af-misc）→ 集成 af-integration（35+2 提交，65+1 文件，跨包文件冲突 0）。

## 总账

| 处置 | 条目 |
|---|---|
| 修复（含新测试锁行为） | 37 条（voice 13 / ui 8 / misc 15 + SEC-3 部分修） |
| 证伪跳过（附依据） | ROB-16、SEC-6 |
| 部分修 + BLOCKED | SEC-3（toString 脱敏已修；EncryptedSharedPreferences 迁移=新依赖须报批） |
| 待人工拍板 | SEC-2（修法一行：NetEndpoints.loginUrl 切 443 反代，前提=反代已路由 /login，代码侧无法验证） |
| 测试 BLOCKED（无 instrumentation 基建，禁加新库） | ROB-8、ROB-3（修复本体已落地，逻辑件与守护注释在） |

## 两处证伪的管理者终审（含实测）

- **ROB-16**（AmbientCalibrator 裸 CoroutineScope）：voice 工证伪「cancel() 必抛 ISE」。**管理者实测仲裁**：kotlinx-coroutines 1.8.1 下 `CoroutineScope(Dispatchers.IO).cancel()` 实测正常（工厂自动补 Job；一次性测试实证后删除）。reviewer 的质疑不成立，证伪维持。
- **SEC-6**（runId 注入）：misc 工证伪——runId 客户端生成（`"u_${System.currentTimeMillis()}"` @ ChatViewModel:777、VoiceSessionManager:116，AguiClient:753-754 将 consumer 回调 runId 覆写为该 roundId），平台污染前提不成立 → 降 P3（ShelfMcpBridge 手拼 JSON 的转义属卫生类，P3 清单已含）。

## 独立评审结论与处置（评审员：未参与修复的独立 agent）

- **P1：无**；测试质量抽查通过（87 新测试无空断言/凑数）；禁改项零 diff；无顺手重构。
- P2-1「3 条缺口」：ROB-16/SEC-6 实为证伪有据（见上），SEC-2 待拍板——本文件即书面转办记录，缺口关闭。
- P3-1 FUNC-6 测试 KDoc 陈旧（简报处方 close() 实际实现 cancel()）→ 已勘误提交。
- P3-2 createSession 4 个调用点无守卫（磁盘 degraded 时 VM init 崩溃 vs 静默降级）→ **开放点**：简报本意「拒写不装成功」，统一静默降级违背 DATA-1 初意；崩溃-降级取舍属产品决策，交领导拍板。
- P3-3 VoicePlayer 锁外 writeAll 窄窗 ISE → 既有 per-sentence catch 收口不崩，注释已声明，接受为残余。

## 测试账

基线 665（锚定跑 --rerun-tasks，skipped=0）→ 分支终态 voice 698 / misc 694 / ui 690 → **集成终验 752 绿 / skipped 0 / failures 0**（= 665+33+29+25，全量 --rerun-tasks）。

## 遗留待拍板（非阻塞）

1. SEC-2 登录切 443 反代（一行改动，需部署事实确认）
2. SEC-3 主迁移（EncryptedSharedPreferences = 新依赖 security-crypto，须报批）
3. P3-2 init 崩溃 vs 降级口径
4. af-integration 分支是否合入 main（当前未合，等指令）
