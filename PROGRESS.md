# 小朵 Android M1 进度

## 状态：入口功能——密集架基础设置直拉悬浮窗 + 菜单删「打开 App」 ✅（2026-10-09，755 绿，已推送）

- **用户令**：mijijia 系统设置加「打开小朵」（拉 APP）+ 基础设置按键名不变改直拉悬浮窗（mijijia 侧改动在 mijijia 仓，见其 DEV_LOG 2026-10-09）。
- **desk-pet 配套**：①`extra_launch_overlay_only` 直拉路径（MainActivity 策略纯函数 3 测；START→起 PetOverlayService+finish（setContent 前不闪 UI）；无权限→FALLBACK 常规 UI）；②长按菜单删「打开 App」行（`onOpenApp` 链全清，跳转类只剩「打开密集架」——入口职责归密集架设置页）。
- **部署**：生产板=审计修复版 23078d1c（10-08）；测试机离线待补装；**10-09 功能需 desk-pet+mijijia 双 APK 成对更新**（旧 desk-pet 不认新 extra，会退化成开 APP）。
- 状态：755 绿 skipped=0；提交 a913ad9（直拉）+ 962ab80（菜单删行）已推送。

## 状态：全量代码审计 + P2 修复工程 ✅ 完成（2026-10-03，审计 P1=0/P2=43/P3=410；修复 40 条处置毕，集成 752 绿）

- **审计**：双通道机扫（ocr 100/105 文件 462 条 + codex 全仓 215 文件 5 条）+ 双路人工（兜底 5 文件 + 非代码面）→ **P1=0 / P2=43 / P3=410**；风险集中「分发形态」三件套（debug 签名 + BuildConfig 明文密钥 + TLS 内网白名单旁路），打包成一个安全决策待拍板。产物 `docs/audit/2026-10-03-full-audit/`（入口 README.md）。
- **修复**（领导令：修除分发形态外全部 P2）：40 条三路子智能体并行修（worktree af-voice/af-ui/af-misc，TDD，跨包文件冲突 0）→ 集成 af-integration（37 修 + 2 证伪〔ROB-16 管理者实测仲裁 / SEC-6 runId 客户端生成实锤〕+ SEC-3 部分）+ FUNC-5 跨包接线，独立评审无 P1。**集成终验 752 绿 / skipped 0 / failures 0**（基线 665，+87 新测试）。
- **待拍板**：①SEC-2 登录切 443 反代（一行，需部署事实）；②SEC-3 EncryptedSharedPreferences 迁移（新依赖报批）；③createSession 崩溃 vs 降级口径；④af-integration 是否合 main；⑤分发形态三件套决策。处置全账见 `docs/audit/2026-10-03-full-audit/fix-phase-dispositions.md`。
- 工作区状态：main 未动（5ee8e20）；af-* 分支含全部修复；主仓未提交变更=审计目录 + PROGRESS/DEV_LOG/implementation-notes 文档。



## 状态：S50~S65 语音链噪声域加固 + 关键词治理 ✅ 已全合 main（2026-09-23，656 绿，代码至 1723f59，文档收口在其上）

**S50~S65 速览**（细节见 DEV_LOG 对应条目）：S50 弱音节判停拆线 → S51 续听窗（S55 后窗=1，09-23 拍板维持）→
S52 上攻停用 → **S54 多唤醒词/打断词** → S55 续听死锁 → S56 噪声底自适应 → S57 采麦源锁定 → **S58 上攻复活**
（回退二分定案）→ S58b 自回声丢弃 → S59 尾音衰减窗 400ms → **S60 判停尾垫**（句尾弱音节进 PCM）→
S61 好了阈值 0.5 → S62 首页导航（mijijia 同票）→ S63/S64 打断词全禁（keywords.txt 剩 3 纯唤醒词，
打断=说唤醒词或双击）→ **S65 回声纪元守卫**（盲窗回灌滤「我在」回声，「唤醒后听到我在就结束监听」根因修复）。

**合并状态**：S54~S64 已合 main（aedee33，652 绿）；S65 直推 main（2a09d44+1723f59，656 绿）；s54 分支/worktree 已清场。

**两板运行矩阵（2026-09-23 深夜，双板同版）**：测试机 192.168.137.76 = desk-pet S65（b2df043c）+ mijijia xiaoduo 1ef8614
（fb528b1e，MCP 链完整）；生产板 10.0.14.18 = **desk-pet S67c（f76ce9bc，663 绿）+ mijijia S67d（79bcddе6）**——
两板四包全部同版（09-24，S66 弹窗即时化 + S67 同窗追加/去重 + S67b 同轮不误杀 + S67c 回合级 ID + S67d 首帧定宽，真机验证通过）；
⚠️ 测试机 prefs 仍待用户重填（password + 三模态 platform）。生产板 mijijia 现为我们的 debug 签名（与同事包不兼容，换包需卸装回灌）。

**挂账**：①✅ S54 验收清单**归档不补跑**（用户拍板 09-24；清单存档 Temp/TEST-S54-shout-acceptance.md，多唤醒/打断词日常使用即持续回归）；②✅ 生产板双包升级（09-23 晚完成）；③mijijia tips⑧「停下」文案过时；④底噪 0.19 物理来源未明（对照实验未做）；⑤S55 快语速取证；⑥生产板语音自启观察；⑦**测试机 prefs 重填**（password + 三模态 platform——不填则走云端/登录失败，事故见 solutions/workflow-issues/adb-run-as-prefs-write-must-verify.md）；⑧**平台 chatAgent 故障**已由平台侧切 Agent 恢复（语音轮大面积 SSE 断流根因，全案见 solutions/workflow-issues/platform-sse-zero-chars-server-side-diagnosis.md）；⑨**S66/S67 全链真机验证通过**（09-24：结果卡即时弹、同句多 run 同窗追加、新句换窗、首帧定宽，用户确认）；⑩✅ **同轮第二调用 args=null 已修**（S68b，09-24：平台并行调用装在同条 START 的 rawEvent.tool_calls 数组、顶层 id 只对应 [0]——遍历全数组登记即可；SSE 取证日志常驻）。

## 历史：S68 弹窗区块参数摘要 + 并行 tool_calls 登记 ✅ 收口（2026-09-24，665 绿）

- **S68(Q1-B)**：同工具多区块无法区分 → 区块头追加 keywordOf(args) 摘要（四方案 HTML 原型对比，用户选定 B）；mijijia 单函数改动。
- **S68b(取证)**：测试板复测第二区块无摘要 → desk-pet 加 SSE 原始行日志 → 一次复测取证：平台并行调用装在**同条 START 的 rawEvent.tool_calls 数组**、顶层 toolCallId 只对应 [0] → [1] 从未登记。修复：遍历全数组登记 + null 不覆盖。665 绿。
- **沉淀**：跨端「字段缺失」类问题，先加原始事件日志取证再改代码——一次复测即可定位，避免按假设盲修。

## 历史：S66/S67 MCP 结果卡即时化与多结果同窗追加 ✅ 收口（2026-09-24，663 绿）

- **S66**：转发时机从 run 收尾提前到 TOOL_CALL_RESULT 到达即回调（用户报弹窗延迟≈30s：首 token 5.3s + 1079 字流式 + 播完才弹）；移除收尾二次回吐防双发。真机验收通过。
- **S67**：S66 后后到结果整体替换先到（mcpUirresult → McpResultDialog.updateContent）→ 弹窗区块化 + 去重（result 相等）+ 新回合整窗重置（grilling 八问定案 + HTML 原型沟通）。
- **S67b**：真机报「两次调用只见一条」——板端日志实锤 S53c dismissExisting 被同轮第二个 START 误触发；mcpUirsync 带 runId、runId 变化才让位。
- **S67c**：平台把复合查询拆**多个 run**（日志：项目/合同两个 runId + attempt_2 重跑）——run 级 ID 失效，升级「说话回合 ID」（u_<millis>，每句一次），同句追加、新句换窗；mijijia 端零改动。
- **S67d**：弹窗首帧铺满屏闪跳——宽度设置原在 onResume 的 view.post（下一帧才生效），提前到 onStart 同步执行；像素验证 692px/1080px 非铺满。
- **证据**：mijijia 仓 DEV_LOG + `E:/mjj/s67*.png` 截图组；工单 `.scratch`（mijijia 仓）s67-multi-result-popup/issues/01-03。

## 历史：S49 语音指示泡（聆听声纹/思考三点）✅ 代码收口 + 主链板端实证（2026-09-18，627 绿）

- **S49 定案**（grill 两轮 + HTML 原型逐轮纠偏）：聆听=镜像柱声纹泡（×13，HaloCyan，真实 RMS
  EMA 120ms 驱动）；思考=三点错相跳动泡（0.85s 升余弦）；两态共用恒定 132×40dp 泡框（与单行
  文字泡同高，切换尺寸零变化）；「我在」/错误/回复文字泡、背景、触摸穿透全不动；show_bubble
  开关随禁。原型：`designs/xiaoduo-motion-preview/悬浮窗声纹气泡预览.html`。
- **关键实施点**：指示泡 show 认领 VOICE round（原「在听…」接管语义，回复 delta 令牌基础）；
  工厂侧指示层/文字泡共用 voiceRound 登记；VAD 分支每块 RMS 直喂；Choreographer 帧循环
  plain View（无 S25c Compose 冻结病史）。
- **当日拍板二**：驻留 2.5s→800ms；**S5 多轮回听抑制废止**（多轮聆听期声纹必须弹，
  `ListeningHintPolicy` 纯逻辑 + 回归锚）。
- **验收**：627 绿（+21）；板端日志全链路实证（14:12 三轮 + 14:57 复测：多轮回听声纹
  ✓×2、800ms 驻留 ✓（813/858ms）、三点顶替零间隙、MCP 工具轮回归）；双板：测试板=最新包，
  生产板=09-17 包（无 S49）。

## 历史：S48 三票 + S46 本地导航 ✅ 双板部署收口（2026-09-17，606 绿，生产板已上线）

- **S46 本地导航**（用户拍板不走平台，S26-3 设备技能同款模式）：`ShelfNavIntentMatcher`（纯 Kotlin，
  保守匹配导航动词/页面名词+借/还/盘点目标词，执行对象词绝不拦截防劫持）→ `ShelfMcpBridge.navigate`
  同步 POST `mcpUiNavigate`（page 白名单 borrow/return/inventory）→ 文字轮 `runShelfNavTurn` /
  语音轮 VSM 注入 matcher/executor（不置工具轮标记，正常 TTS）。对侧 AppController 新 Op 分支。
- **S48-1 agent 全量**：`listAgents` 循环翻页 `{"pageSize":500,"pageNum":N}`（真机验证 PageHelper
  参数名生效，平台 14 个 agent 全量可见）；设置页选择器限高 320dp 滚动。
- **S48-2 闲置轮转**：会话最后一条消息超 10 分钟 → 下次交互时静默新建（`SessionRules.
  shouldRotateForIdle` 纯函数，空会话永不触发防循环；文字 send/语音 onUserUtterance 两入口）。
- **S48-3 悬浮窗「创建新会话」**：菜单替换「查看最近一轮」，Service→VM 双桥（chatBusyProvider/
  chatNewSession），不进 APP，成功气泡「已开启新会话」。板端日志实证 `menu: create new session ok=true`。
- **验收**：606 绿（+11）；真机 e2e 全绿（agent 全量截图/菜单 dump/日志实证）；双板部署完成
  （生产板坑与教训详见 D:\mijijia\docs\DEV_LOG.md 2026-09-17 三条目：签名冲突毁库/备份先行灌回）。

## 历史：S43 MCP 结果卡弹窗 ✅ 收口（2026-09-15；S46 期间三场景真机验收全绿）

- **S43 需求**（原型 `E:\mjj\prototype-s43-mcp-result-card.html` 确认）：语音 MCP 借/还/查结果只在
  助手语音里，屏上无视觉结果——小朵把平台 **TOOL_CALL_RESULT**（SSE 抓包实证 content 为结构化
  JSON）转发密集架 `Op=mcpUiResult`，密集架弹**只读结果卡**（只显示结果不显示参数、与自带弹窗
  同族样式、全中文、防双控无执行按钮、查询卡带「去检索页操作」按键）。
- **S43 交付（本侧）**：`AguiSseConsumer` 新增 ToolCallOutcome（toolCallId→名字映射 +
  rawEvent.args 提取 + 最后一次 RESULT 覆盖语义，澄清轮次自然被盖）；`AguiClient.chat/runOnce`
  新增 onToolCallOutcome 回调（仅 Success 回吐）；`ShelfMcpBridge.resultBodyFor/resultAsync`
  （org.json 组装防嵌串引号，守卫口径同 sync）；VSM/工厂/TextChatRouter/ChatViewModel 全链接线。
- **S43 验收**：595 绿 skipped=0（+8：消费者捕获 5 测 + 桥转发 3 测；基线 587 只增不减）。
  对侧（D:\mijijia）L2 全绿：归还成功卡/查询空卡原地刷新/查询带行卡（档号位置徽标齐全）/
  「去检索页操作」跳页+关键词回填+自动检索/needs_clarification 不弹卡/mcpUiSync 回归。
- **待用户**：L3 整链喊话终验（「帮我查询一下《XX档案》」→ 助手回复同时结果卡弹出）。

## 历史：S41 密集架 MCP 动画联动桥 ✅ 代码收口（2026-09-15，587 绿，L3 语音链实锤）

- **需求**（用户拍板，全链已在 D:\mijijia 侧落地 commit 354fe8e）：平台执行 C款密集架 Agent 的
  10 个 MCP 工具时，SSE `TOOL_CALL_START{toolCallName}` → 本地桥 POST JSON
  `{"Op":"mcpUiSync","tool":"<name>"}` → 密集架 App 播动画页/Toast。**UI-only 防双控**——
  平台 MCP 已执行真实硬件，本地绝不再发硬件 Op。
- **背景**：E:\mjj 副本（原 S40-MCP 联动）已丢失仅余执行文档；本票按
  `E:\mjj\EXECUTION-S40-MCP动画联动.md` 蓝图在最新 main 上重建（该仓库 S40 号为唤醒盲区占用，
  本票顺延 S41）。
- **交付**：`device/ShelfMcpBridge.kt` 新增（SYNCED_TOOLS 恰 10 契约锚 / callFor 纯映射 /
  Client OkHttp NO_PROXY 异步 POST，baseUrl provider 每轮现读，失败/非 2xx 仅日志不抛管线）；
  onToolCallEvent 无参→透传工具名（S20 静音语义不动：任何形态先置位再转发）；
  VSM shelfMcpSync 回调 + VoiceClientFactory 装配单例；**文字轮补接**（S40 副本缺口，Q8 拍板）；
  Prefs `shelf_base_url`（默认 `http://127.0.0.1:8848`，显式空串=禁用）+ 设置页「密集架同步地址」。
- **验收**：571→**584** 绿 skipped=0（+13：桥 8/SseConsumer 1/AguiClient 1/Router 1/PrefContract 1/
  McpMute 1）；critic（opus）**不逊**——亲跑复算属实、变异双抽查红绿齐（删过滤双红/per-call
  baseUrl 红）、对端接收点 D:\mijijia AppController:254 逐字对账、泄密零命中。P3×3 留痕 notes。
- **板上 L2 已证**（D:\mijijia 侧）：curl 直调 10/10 `操作成功` + 动画页/toast 截图 + 反例。
- **2026-09-15 补丁**：装机 L3 首测实锤 `CLEARTEXT to 127.0.0.1 not permitted`——白名单
  加回环两域 + `NetworkSecurityConfigContractTest` 3 测钉死（587 绿），修复版已装板。
- **2026-09-15 对侧映射升级**（HTML 原型先行，用户确认；本侧零代码改动，D:\mijijia `42dc266`/`89472d4`）：
  盘点/盘存→跳 RFID 盒定位盘点页+toast；借阅/盒借→「档案借阅」页；归还/盒还→「档案归还」页；
  查询→检索页（「档案利用」all 模式）；全部幂等不叠页。同日**端口对齐生产**：板 192.168.137.46
  按生产板 10.0.14.18 配置（AndServer=8849），本机 SP `shelf_base_url=http://127.0.0.1:8849` 已写。
- **L3 进展**：语音链已实锤（「帮我盘点档」→ ASR → 平台 `TOOL_CALL_START archives_inventory` →
  桥触发）；cleartext 修复后整链喊话终验 + 通风/开闭架档待用户现场口令。
  话术须知：查询/借阅须带具体档案名（否则 Agent 只反问）；盘点/通风/开架类可直接触发。

## 状态：S40 唤醒应答期盲区修复 ✅ 代码+单测收口（2026-09-12，570 绿，装机 14.18）

- **用户报障「唤醒后在『在听』前说话没检测到」定位收口**：①唤醒→「我在」播完→vad.start
  构成 ~0.9s 采音盲区（SPEAKING(wozai) 麦块只投 KWS + vad.start 清空无回填）；②消费停顿后
  ack 时代陈旧块突发直灌 VAD（数学实锤：533ms 窗口产出 1.2s 音频 utterance；UNPROCESSED
  无硬件 AEC 含「我在」回声 RMS 0.35-0.55 抬 S19-1 阈值吞话头/伪轮）。当日取证：125 唤醒
  序列 = 6 次 30s 超时 + 99 次陈旧突发签名 + 句头截断（ASR「天天气怎么？」等）。
- **修复（A+B，用户拍板）**：采麦块 producer 侧盖戳（StampedBlock/nanoTime）→ 接缝水位
  策略（VadSeamPolicy 纯逻辑）：ack 时代块改道 WakeBlindWindowBuffer（3s 环形，不直灌
  VAD），接缝后首块触发回灌（FIFO 时序完整）→ 回灌过 NLMS（新增 WakeAck 参考钩子推「我
  在」参考）喂 VAD——盲区话头找回 + 陈块污染治理，一次闭环。
- **代价/观察项记 notes**：回灌大停顿退化为带回声回灌（时序仍对，S19-2 兑底）；「我在」
  话头残余可能进 ASR 文本（无前缀剥除）；改道块跳过 S33 旁路防 KWS 双喂重唤醒环。
- 基线 556→**570** 绿 skipped=0；装机 14.18 ✓（新包 KWS source=9 健康 diff=0）。
  **现场喊话验收待用户**：唤醒后紧跟说指令应检出（日志 `S40 blind-window backfill` 行），
  30s 超时形态应归零。取证脚本 `.scratch/analyze-wake-blindwindow.py`。

## 状态：S39 悬浮气泡滑动查看 ✅ 收口（2026-09-11，556 绿，用户验收过）

- 气泡窗口 NOT_TOUCHABLE 改动态切换（显示开触摸/hide 还原穿透）+ BubbleScrollPolicy
  拖动仲裁（拖动挂起打字机拽底/松手贴底恢复，5 测）+ ScrollingMovementMethod 接线。
- 代价记 notes：气泡可见期其区域点击不透传；可触摸层免 0.8 alpha 惩罚（视觉提升）。
  用户长文本真机拖动验收确认 ✓。

## 状态：S38 静音判停 600ms ✅ 收口（2026-09-11，551 绿，板子装机）

- silence_end_ticks 4→6 块（400→600ms）：用户拍板放宽——400ms 提速的代价（句中停顿
  截断成多句、半句进 LLM 答非所问）实际使用频发，600ms 折中。TDD 5 锚同步先红后绿。
- KWS 挂起窗 GATE_HANGOVER 8 不联动（保护对象=唤醒词字间气口，语义不同）；
  对账表补注 4 演进史 8→4→6。装机 14.18 ✓，听感（停顿截断 vs 响应速度）留日常。

## 状态：S37 语音轮 thread 跟随当前会话 ✅ 收口（2026-09-11，551 绿，板子装机）

- **用户报障定位**：App 一个会话聊天但平台分两个会话缺上下文——非 bug，是 P1 拍板
  「语音轮与文字轮同一会话的不同输入方式」只做了本地统一，平台侧语音恒 default
  thread、文字走 session.id（SP 实况：default + 9×session thread）。
- **修复**（本次拍板：语音跟随当前会话）：VSM 加 threadKeyProvider（null/空白回退
  default 兜底纯悬浮窗），ChatViewModel attach 挂当前 session.id。TDD 3 测
  （MockWebServer 断言 stream body threadId），548→**551** 绿；装机 14.18 ✓。
- **代价（notes 存证）**：旧 default thread 语音历史不迁移（无 merge API），语音上下文
  从当前会话重新积累；删会话=连语音记忆一起删。板子现场跨通道上下文实测待用户。

## 状态：S35 唤醒撕裂修复 ✅ 收口 + S36 削波验证/阈值观察 立票（2026-09-11，548 绿，板子装机）

- **取证驱动闭环**：全天日志取证实锤四问题共根因——「对话期 kws-decode（MIN_PRIORITY）被饿
  → 队列满丢最旧 → 时间轴撕裂 → 唤醒/打断必漏」。diff 实测：空闲≈0，对话期 +940/+283 块
  （≈8.8s/2.8s 音频被撕）；解码 7.5 块/s < 喂 10；19:26:11/15 IDLE 连喊 2 次不中现行
  （影子 0.15 中、主 0.3 不中）。gate 吞块假设证伪（gated=10/19368）。
- **修复**：主引擎解码线程提档 MIN→6（ART nice 实证 -2，UI -4 之下），影子保持 MIN。
  KwsCoreTest 新增优先级测试+变异锚，基线 547→**548** 绿。
- **装机双验收**（14.18）：①忙环 2 核争抢下 diff 稳定 0-1（修复前 944/1224）；
  ②负载期 Choreographer 零报警（小朵），S25b 白屏未回归；悬浮窗动画长期观察留日常。
- **假设证伪×2**（notes 存证）：①「人声削波」收回——maxLevel 有 minOf(1.0,·) 显示封顶，
  1.0=说话 RMS≥24% 满幅正常电平；②阈值 0.2 暂不动——影中主不中样本发生在撕裂时段，
  归因不洁。观察口径见 CONSTRUCTION.md S36。
- **另有发现**：阿里云 ASR「Access denied」×2（18:43/18:44）——账号欠费/限流，待用户自查；
  「说完话被吞」形态（抢话唤醒词 94ms 后打断链 seal 静默）by design，体验优化另议。
- 取证快照：`.scratch/forensics-20260911/`（SHA256 1c9407…a91d，8029 行）。

## 状态：S34 近漏检观测 ⛔ 回退转 BLOCKED（2026-09-11，rk3568 双引擎吞吐红线实测）

- 改动（影子全时段喂流）装机 3 分钟即实锤性能红线：App CPU **202%**、主+影子解码吞吐上限
  ≈10 块/s，主引擎被挤到 decoded 4.4/s（队列 64 块封顶丢最旧=时间轴撕裂 56%，唤醒必漏）
  ——**即时回退**（保留 S22 口径），复验 decoded/fed=1090/1092 恢复实时 ✓。
- 教训：观测通道共享 CPU 必须装机实测负载，「不打断功能」≠「无代价」。数据与重开条件
  （换硬件/小模型/离线批量）存 BLOCKED。漏检观测空缺期以受控喊话人工对账过渡。

## 状态：S33 聆听盲区修复 ✅ 代码+单测收口（2026-09-11，547 绿，板子现场喊话待用户）

- **修复**：LISTENING 期唤醒词可达（此前单投 VAD 100% 必漏）。两处最小侵入：
  ①routeBlock VAD 分支旁路喂 kwsSink（走 NLMS 消「我在」尾回声，零参考直通）；
  ②startListening 开 LISTENING 口（仅唤醒路径：cancelCurrentTurn 终止当前聆听轮→
  重播「我在」→重新聆听，与 S12 打断接续同构）；按住路径保持 no-op（最小侵入）。
- **测试**：VoiceSessionManagerS33BlindSpotTest 4 测（旁路/重唤醒链/按住锁定/
  SPEAKING 单投防双投）+ 变异锚齐；基线 543→**547** 绿 skipped=0；装机 14.18 ✓
- **验证话术**：唤醒「小朵小朵」→（聆听中，不说指令）再喊一次 → **应重播「我在」重新聆听**
  （修复前必漏）。
- **同日发现**：14.18 被用户切 UNPROCESSED 采麦源（mic_source_mode=2）+gate=0.015 双变量
  生效，4.3h hits=98（对比 41h/39），成分待用户确认；A/B 归因污染记 notes。

## 状态：S32 采麦零馈入诊断 ✅ 收口（2026-09-11，18.47 板语音全断因 ROM 缺陷，App 层无解）

- 语音链路优化开工侦察发现：整理台网关板 10.0.18.47 采麦零馈入（AutoCalib n=0 /
  dead IAudioRecord 风暴 / kws stats 零落行），改道先行诊断。**根因链五步切分坐实 ROM 层**：
  ①rk USB audio HAL fd 泄漏（构造期 probe open 不 close → EBUSY 自锁死循环）；
  ②板载 rk809 capture 未登记 audio policy（拔 USB 后 `could not find device for source 9/1`）
  ——两头堵死，USB 插着死、拔了也死。
- 旁证：USB 声卡 09-11 10:39 热插后全死；该板语音自 S26 起从未验收过=从未能工作；
  14.18（rk3568_r）同 APK 39h 健康（fed=1.4M hits=39）排除 App 回归。
- 板子动作全部可逆留痕（notes 偏差条目）：mic_source_mode 0→2（保留 UNPROCESSED，唯一能
  capture start 成功的源）；UNPROCESSED 校准歪打正着写入 gate=0.003/vad=0.05/done=true
  （钳位下限，保守无害）。14.18 主力板零写入零影响。
- 证据 `docs/verification/v23-s32-mic-zero-feed/README.md`；learning 新类目
  `docs/solutions/hardware-environment/`；App 层防御票 S32 入 CONSTRUCTION.md。
- **待用户决策**：①固件问题报设备供应商/换 ROM；②现场物理重插 USB 麦/换口/换声卡试验；
  ③S32 防御票（零馈入自检告警）时机。语音优化主线（唤醒率+打断率）转 14.18 继续，不受阻。

## 状态：S31 导航行键+密集架跳转+双击打断 ✅ 全收口（2026-09-09，用户板子/模拟器双测确认）

- 密集架跳转：真主应用 com.yinduo.compactShelf（ys_receiver=system uid 看护壳陷阱），
  端到端 ok=true；跳转目标设置页可自定义（KEY_JUMP_PACKAGE，空=默认密集架）
- 双击打断两轮修复：窗口 400ms + TapGesture 净位移≤32px 视为点按（1x 密度
  touchSlop=1.7mm 陷阱：真人按压漂移必超致 tap 被吞），板子三组复验全过
- 24px 上下间距对称用户验收 ✓；双击打断用户实测恢复（文字轮+语音轮统一门控桥）
- 三坑沉淀 docs/solutions/：ui-bugs/low-density-touchslop-drift-swallows-tap、
  logic-errors/overlay-interrupt-wrong-facade-vsm-vs-chatviewmodel、
  workflow-issues/kiosk-guard-shell-vs-real-app-package
- 538 测绿；提交 7610d0d/c94cac7 + 收口 4 文档
- **S31 追加（10.0.18.47 设备）**：①rk ROM insets 派发断裂——导航行被三键栏盖下 1/3，
  NavInsetPolicy 三键资源兜底（max(派发,资源)）复验净空 28px；②思考三点板子不可见
  （160dpi 6dp=6px）→ 点径屏宽 1.5% 钳制，用户实测恢复。543 测绿；
  提交 71e0927/cf0253d；learning×4 入 docs/solutions/

## 状态：S30 文字输入设备控制 ✅ 代码收口（2026-09-09，中文端到端留板子）

- 检查发现 S26 设备技能只接语音轮（VSM ASR 后拦截），文字输入直达 LLM——补齐：
  DeviceTurnRules.gate 纯函数分流 + ChatViewModel.runDeviceTurn（直连执行→speech 上屏
  不播报，失败降级 Fail 播报）。普通提问零劫持（须含设备名词）。526->530 测绿；
  模拟器零回归实证（ASCII hello 走 CloudLLM 正常）。

## 状态：S29 环境音首次自动校准 ✅ 代码+模拟器收口（2026-09-09，板子噪声底待回收）

- 澄清：校准一次永久生效（SP+provider 现读），无需每次配置。新增：语音服务启动时未校准过
  → 静默采 5 秒自动写 KWS 门+VAD 阈值（stableEnough 拒绝人声/突发污染样本，失败不标记下次
  重试）；手动校准仍可用且让位自动。520->526 测绿；模拟器端到端三证据（written/重启不重复/
  校准值跨重启生效）。

## 状态：S28 双击打断输出 ✅ 代码+模拟器收口（2026-09-09，板子现场待回收）

- 悬浮窗宠物 + 全屏页宠物**双击=打断输出**（cancelCurrentTurn，与菜单「打断」同通道，IDLE 安全 no-op）。
  悬浮窗 View 手势机自加双击判定；全屏页 detectTapGestures（坑：pointerInput 挂 AndroidView 同节点
  收不到 interop 事件，改纯 Compose Box 包裹解决）。520 测绿，两入口模拟器日志实锤。

## 状态：S27 底部导航栏改版 ✅ 代码+模拟器收口（2026-09-08，板子包名实证待回收）

- **交付**：顶栏 PetStatusBar 移除（宠物头像/相位文案退役，消息流顶到状态栏下）；
  底部导航行贴底三段式（汉堡｜密集架键居中｜全屏四角键，手绘 2 ImageVector）；
  AppLauncher 跳转（决策纯函数 6 测 TDD）+ manifest queries LAUNCHER 通配
  （包可见性一次解决，包名实证后只改常量）；悬浮窗菜单第 2 位纯文字「打开密集架」
  （与导航行共用 startWithToast，失败 toast 兜底）。**514->520 测绿**。
- **模拟器验收全绿**：三键落位（中键真居中 540/1080）+ 失败兜底（toast+ok=false）
  + 两入口真跳（临时常量指 settings，前台变 Settings+ok=true）+ 全屏键进 S14 页
  + 深浅色像素断言。遗留：密集架包名（板子 `pm list packages | grep -i ys` 实证改
  AppLauncher.TARGET_PACKAGE）+ 板子现场复核；EMULATOR.md 锚点已更新（顶栏坐标全变）。

## 状态：S26 设备技能（整理台/净化柜本地意图） 🔨 代码收口（2026-09-04，板子语音现场待回收）

- **B 路线**（用户拍板）：ASR 文本→本地意图匹配（须含设备名词，不劫持聊天）→直连
  HTTP（NO_PROXY）→命令+延时回读验证→结果走原分句/TTS 管线；设备轮=工具轮待遇
  （设备自带播报 playFlag→S20 静音+S21 重唤醒自动适用）。
- 交付：DeviceIntentMatcher（14 测）+ DeviceClient（7 测）+ VSM 接线（e2e 1 测：
  零 LLM 请求）+ 设置页设备地址（默认 10.0.18.47:8080，空=禁用）。496->**514 测绿**。
- 板子可达已验证（ping 0% 丢包 + 设备 API 活体）。现场验收话术：打开整理台 /
  净化柜强力净化 / 整理台风速二档 / 整理台什么状态。

## 状态：S22/S23/S24 播报期打断治理 ✅ 全收口（2026-09-02 深夜，四轮板子数据闭环）

- **根因链**（三轮假说迭代+一次控制实验）：媒体音量满格→扬声器贴麦克风→**ADC 削波**
  →线性消回声三假设全违反。音量 -7dB 控制实验一锤定音（唤醒 0/2min→7/90s）。
- **S24 收口**：App 内播报音量（默认 45%，滑杆，TTS+wozai 双路径；系统音量不动=密集架
  自播不受影响）。终验 90s/9 中（含 SPEAKING 态 barge-in 链）。
- **S22 收口**：影子 0.15 四轮与主引擎命中重合、零误触——不转正，维持观测位。
- **S23 收口**：NLMS 保留实验开关默认开（线性区微正贡献+发散守卫最坏无害）；
  板子 head ±600ms 对齐抖动为已知边界。v1-v8 工程迭代全记录 notes。
- **方法论沉淀**：软件调参三轮不如一次物理变量控制实验——「先测能量，再调算法」
  应成声学排障第一律（notes 三条目完整留痕）。

## 状态：S22 影子低阈值 KWS + S23 NLMS 回声消除 🔨 代码收口（2026-09-02，板子数据待回收）

> 首晚数据（09-02 晚）：主引擎播报期打断 10/10 全中；影子与主引擎命中重合、回声
> 自误触=0 → **S22 低阈值不转正**（数据轨收口）。可疑漏检窗 22:58-23:13 待用户复测
> 确认。NLMS 运行遥测已补（`echo cancel active` 行，20bffe6），复测时验证真在跑。

- **背景**：板子播放期「小朵小朵」打断受限=无 AEC 回声掩蔽+AGC 压缩（手机无此问题）。
  用户拍板 A（影子测量）+C（NLMS 根治）并行。
- **S22**：第二 spotter 阈值 0.15 仅播放/处理期喂流，命中仅记日志（tag=KwsShadow，
  `S22 shadow hit` 行）——今晚数据定转正阈值（真打断补给率 vs 回声自误触率）。
- **S23**：NlmsEchoCanceller 纯 Kotlin（TTS 自产 PCM=完美参考，NLMS 自适应滤波减回声
  再喂 KWS 主+影子）；设置页「播放期回声消除（实验）」开关默认开可 A/B；单测锚
  ERLE≥10dB/目标保真/零参考直通。wozai 短应答独立 player 未接参考（notes 挂账）。
- **验证**：488->**493 测绿**（影子锚+1 / NLMS+4），板子已装。

## 状态：S21 工具调用后需重新唤醒 🔨 代码收口（2026-09-02，真机/板子现场待回收）

- **背景**：板子多轮续听窗把密集架自带播报（取档/归还）当用户语音收下 → 幽灵对话
  （甚至再触发 MCP 调用）。用户提案+拍板：工具轮后需重新「小朵小朵」唤醒（独立开关）。
- **交付**：设置页「工具调用后需重新唤醒」开关（默认开）；含 TOOL_CALL 轮结束后
  backToIdle（KWS 守门）不走多轮续听；会话线程服务端保留，重唤醒不丢上下文。
- **验证**：486->**488 测绿**（e2e×2：开=终态 IDLE / 关=多轮 LISTENING 重启）。

## 状态：S20 MCP 调用结果静音开关 🔨 代码收口（2026-08-31，真机/板子现场待回收）

- **需求**（用户拍板）：密集架自带语音播报，含 MCP 工具调用的回复手机只上屏不播音。
- **交付**：设置页「MCP 调用结果播报」开关（默认开=现状）；TOOL_CALL_START 标记
  （探针实测先于文本到达）-> TTS 生产者跳过合成，文字照上屏/上泡，多轮重启正常。
- **验收**：482->**486 测绿**（SseConsumer 回调 + e2e 双开关路径：开关关=零 TTS
  请求+文字上屏+重启聆听 / 开关开=TTS 正常）。排障：runOnce 调用点漏传参
  （默认参数静默吞回调，chat 层二分测试定位）。

## 状态：S19 自适应 VAD 阈值 + 空 ASR 续听 🔨 代码收口（2026-08-31，板子现场待回收）

- **P1**：VadDetector EMA 双轨噪声底（慢跟踪+条件上攻：半峰/平台双通道），
  泵态噪声判停自洽、连续语音不误杀、手机零回归；设计迭代留痕 notes（两轮窗口法死局）。
- **P2**：ASR 空文本（纯噪声幽灵轮）重进聆听而非落 IDLE，上限 2 次。
- **验收**：475->**482 测绿**（P1×4+P2×3+VAD 既有 16 全绿）。板子今晚现场回收：
  泵态 utterance <2s + 幽灵轮不再断链（vad diag 轨迹核对）。

## 状态：S18 VAD 阈值校准联动 + 板子声学诊断 🔨 S18-1/2 收口（2026-08-31，板子终验待回收）

- **诊断**（板子 rk3568「打断不灵敏」三层排查）：打断检测正常（KWS 全中），病灶=**板子无
  AEC（effects 空）+ 底层 AGC 慢释放**，聆听期噪声底 0.08-0.34 顶住 VAD 判停拖满 8.3s。
  证据链 `docs/verification/v22-s18-vad-calibration/`（含 board-logs.md 存证）。
- **交付**：VAD 阈值校准联动（provider 注入+校准双值写入，手机零变化）；采麦源三档
  （通话/识别/原始，重启生效）；聆听期电平轨迹诊断。472->**475 测绿**。
- **接手指引**：① 板子切「采麦源：原始」-> 强杀重启 -> 重新校准（看分布是否分散=摆脱
  AGC）-> 受控测试（喊唤醒只说「你好」，utterance 应 ~1 万级非 132800）；② 无效则上
  **自适应 VAD 阈值**（聆听期 EMA 跟踪噪声底，vad diag level 日志已备数据）；
  ③ AguiClientTest.blankExternalMessageIdFallsBackToGenerated 偶发失败一次（单跑绿，
  观察）。

## 状态：S17 全屏字幕三合一适配 ✅ 收口（2026-08-29 模拟器+板子验收）

- **交付**：全屏页布局统一走纯 Kotlin `FullscreenVoiceLayout` 计算器（99 组设备矩阵
  不变量测试）：宠物 min(0.62×宽, 440dp, 0.45×高等比)；字幕跨区（宠物底+16dp->
  状态区顶-12dp，clamp [64dp, 28%H]）；列宽钳 520dp；字号 [1,1.3]× 缩放；
  上让位规则保横屏。**手机零回归**（黄金值单测锁定）。
- **验收**：467->**472 绿**；模拟器横屏无重叠（S14 遗留破损销案）+ 竖屏与 S14 一致 +
  旋转零 onCreate；rk3568 板子（1080×1920dp 大屏）caption 落位与公式一致。
  证据 `docs/verification/v21-s17-subtitle-fit/`。

## 状态：S16 平台智能体选择 ✅ 收口（2026-08-29 模拟器验收，真机风格感受留日常）

- **交付**：设置页「平台智能体」选择器（POST /agent/list 443 反代实测，列表单选 9 agent）；
  AguiClient agentId 常量->动态 provider（切换即时生效）；Prefs 持久化（id+显示名）。
  语义（用户拍板）：agentId 仅请求头路由（提示词/MCP 不同），thread 连续性与本地历史不动。
- **验收**：461->**467 绿 skipped=0**（AgentList ×4 + NetEndpoints +2）；模拟器实证
  列表拉取/选择保存/强杀重启保持/换 agent 对话正常（thread 复用+回复知识域吻合）。
  证据 `docs/verification/v20-s16-agent-selection/`。

## 状态：S15 聆听期反馈泡+「我在」驻留 ✅ 收口（2026-08-25 真机验收，改进盘点 1+2）

- **交付**：①进聆听悬浮窗显「在听…」提示泡（S13 静止化后聆听零反馈缺口），离聆听即收，
  多轮回听不显（S5 回复泡驻留语义不动）；②「我在」播完驻留 2.5s 后内容顶替为「在听…」
  （无 hide 间隙，防一闪而过）；世代+在聆双守卫防打断轮补枪。
- **验收**：460→**461 绿 skipped=0**（+1 新增 2 改写）；模拟器按住说话实证日志链
  「LISTENING→show 3 chars→release→hide→IDLE」+提示泡截图；真机唤醒路径连贯感 08-25 现场过。

## 状态：S14 全屏语音页交互改版+「我在」可视化 ✅ 收口（2026-08-24，真机现场验收全过）

- **起因**：用户报应声词「我在」无气泡无动作 + 全屏页交互差。流程：根因盘点 → 真机调研
  豆包/通义 → 逐项拍板 → HTML 原型 v1-v6 迭代 → TDD 施工（CONSTRUCTION.md S14-1~4）。
- **拍板**：「我在」纯现场反馈不进消息流；只改全屏文字区；打断+退出双键抽屉式；双退出入口保留。
- **交付**：VSM「我在」气泡（播完即收，三保险）；全屏三段式（顶部栏+字幕开关进场/宠物 0.62×
  屏宽居中 42%/状态区下 1/4，caption 17sp+「我在」19sp 青色）；聆听 7 柱声波（帧时钟插值 60fps）/
  思考三点；底部抽屉双键；字幕富文本（共享聊天页 MarkdownContent）+滑动+贴底跟随。
- **顺手销案**：概率性白屏（08-18 观察票）——无 configChanges 配置变化即重建 Activity（主因）
  + standard launchMode 多实例叠加，Manifest 两行结构性修复，模拟器旋转对照实验+真机现场过。
- **验收**：457→**460 绿 skipped=0**；模拟器三段式/抽屉/旋转零 churn 截图；真机现场验收全过
  （含首轮返修：字幕重叠+只露开头）。证据 `docs/verification/v19-s14-fullscreen-interaction/`。

## 状态：S13 形象动作改造 ✅ 收口（2026-08-24，静止 idle + 说话才动）

- **定案（用户逐项拍板，HTML 原型过目后确认）**：idle/waiting/review/failed 四循环态全部
  静止 = idle 行第 0 帧；说话 = idle 行 6 帧 @120ms/帧；唤醒跳、点击挥手全取消；
  不换素材（hd-v2 2x 方案经原型对比被否决）。
- **改动**：PetFrameTable/pet_states.json 帧表重映射（pet_states 偏离桌面镜像，notes 记档）；
  唤醒 JUMPING→直接 WAITING（门控保留）；单击挥手/playPetOnceThen/playOnceThenIdle 死链全清。
- **验收**：基线 455→**457 绿 skipped=0**（+2 新增零删除）；模拟器悬浮窗双截图裁剪 MD5
  逐字节一致（静止铁证）+ 聊天轮连拍说话期≠静止帧（diff 限面部）。
  证据 `docs/verification/v18-s13-pet-motion/README.md`。语音轮观感留真机日常回收。

## 状态：S12 唤醒词打断 ✅ 收口（2026-08-21，spike 双合格 → 转正 → 真机 8/8）

- **唤醒词打断已上线**：TTS 播放/处理中喊「小朵小朵」= 打断并接续（停播→「我在」→聆听新命令）。
  路由转正（SPEAKING/PROCESSING 恒进 KWS）+ startListening 唤醒路径打断接续（与 S8-3 按住打断
  同一 cancelCurrentTurn 分支）+ wozaiEpoch 世代守卫（唤醒打断唤醒竞态）。
  spikeFeedKws 调试设施退役（生产行为取代）；AecSpikeSection 仅留 ③ 延迟探针。
- **验证链**（证据 v17 README §6）：spike 双合格（误触发 0/4min 带喂流佐证 + 检出 8/10）→
  真机两轮 8/8（正常+大音量）。基线 455 绿 skipped=0（454−2 退役+3 新增）；critic 不逊（F1/F3 已修）。
- **⚠️ 观察票（S12 遗留）**：AEC 偶发失效态——2026-08-21 上午首轮打断零命中，该窗回声持续过门
  一分钟（fed +600/gated +0，与常态「回声在门下」相反），疑与刚重装首启有关，重启后消失未复现。
  **复发时先查该窗 fed/gated 形态即可定性**；频发再评估状态感知门控（S12-2，本轮不盲修）。

## 状态：S11 唤醒词准确率排查 ✅ 收口（2026-08-20，一声唤醒 10/10）+ 唤醒词打断核查（spike 真机观测待做）

- **三层根因叠加，全部修复+上锚**（全证据链 `docs/verification/v17-kws-accuracy/README.md`）：
  ①**阈值 0.5 事故回归**（S11-1）：08-10 的 0.3 无锚，转录重放静默回退 → KwsDefaults 常量+精确锚
  （CONTEXT/spec/对账表三处 0.5 假事实修正）；
  ②**文字轮云端分支事故丢失**（S11-5）：ChatViewModel 无锚内联分支被重建丢弃 → jadx 双源取证复原，
  下沉 TextChatRouter+双 MockWebServer 锚（取证产物入库 docs/verification/forensic-s11-cloud-branch/）；
  ③**真凶=门控丢弃语义碎裂 KWS 时间轴**（S11-6 探针定位 → S11-7 修复）：「整块不喂不补静音」
  挖字间气口压分（第一声必漏）+ 冻结部分分不冲刷（第二声秒中假象）→ 挂起窗 800ms+gap-reset
  （sentinel 保序/消费线程单点 reset/幂等沿）。**超越桌面 parity 的增强**（sidecar.py 取证桌面同缺）。
- **验收**：真机 de61cd8 一声唤醒 **10/10**（修复前第一声 0/10、第二声 10/10）；
  基线 438→**454** 绿 skipped=0（56 套件）；builder×5+critic×4 全不逊。
- **唤醒词打断核查**：当前结构不支持（VoiceStateRouter SPEAKING/PROCESSING 恒 DROP + kwsPaused +
  startListening 非 IDLE 早退三重屏障）；**08-04 AEC spike「零误触发」= 空证据**（旁路 no-op，KWS 全程
  没收到音频，BLOCKED.md 已更正）；S11-3 已重做真放行 spike（`spikeFeedKws` + fed 有效性闸 +
  AecSpikeSection ③ 延迟探针旁置）；sherpa 1.13.4 无 AEC（路封），VOICE_COMMUNICATION 硬件 AEC 已在用。
- **⚠️ 欠账/待做**：spike 真机观测（误触发 ≥5min 0 命中 + TTS 中真声检出 ≥8/10，双合格才立项实现）；
  S10-1/S10-2 critic 补审欠账（08-21 配额恢复后）。

### 接手指引
先读 `AGENTS.md`（项目规则+环境硬事实）→ `PROGRESS.md` 本段 → `docs/DEV_LOG.md` 顶部最新 3 条 →
`C:/Users/Administrator/AppData/Local/Temp/HANDOFF-s54-multi-wake.md`（活跃交接，含全票据台账与挂账）。
构建：worktree `E:/desk-pet-s54`，`GRADLE_OPTS="-Djava.net.preferIPv4Stack=true" ./gradlew.bat --no-daemon testDebugUnitTest`。
双板 adb：`C:/platform-tools/adb.exe`，测试机 192.168.137.76:5555 / 生产板 10.0.14.18:5555（生产改动须用户确认）。
