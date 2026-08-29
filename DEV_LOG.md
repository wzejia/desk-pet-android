# DEV_LOG.md — 小朵 Android 开发日志


### S16 平台智能体选择（2026-08-29）
- 设置页「平台智能体」列表单选（/agent/list 443 反代）；AguiClient agentId 动态 provider；
  Prefs 持久化。语义（用户拍板）：thread 连续性与本地历史不动。461->467 测绿；
  模拟器实证列表/选择/强杀重启保持/换 agent 对话（thread 复用+回复知识域吻合）。

### S17 全屏字幕三合一适配（2026-08-29）
- 用户报字幕跨屏适配不一致 -> 原型四方案对比 -> 拍板「跨区+比例+钳制」。布局抽纯 Kotlin
  计算器（99 组设备矩阵不变量），手机零回归；横屏宠物压字幕破损销案（上让位规则）。
  467->472 测绿；模拟器横竖屏 + rk3568 板子落位验收。

## 2026-08-24（S14 全屏语音页交互改版 +「我在」可视化）

> 起因：用户报「我在」无气泡无动作 + 全屏页交互差。流程：问题盘点（四层根因代码实证）→
> 真机调研豆包/通义（.scratch/research-voice-ui/ 实拍）→ 逐项拍板 → HTML 原型 v1-v6 → TDD 施工。
> 全证据：`docs/verification/v19-s14-fullscreen-interaction/README.md`。

### 根因盘点（用户确认后立项）
- 「我在」零视觉：bubble.show 只挂 LLM 回复/错误；wozai 是资产播放不碰气泡/消息流；
  S13 后 SPEAKING(wozai)=WAITING 静止与聆听同帧；statusText 唯一消费者是设置页 debug。
- 全屏：caption 15sp 挤宠物正下方 + 16dp 三柱弱指示 + 无打断手势 + 字幕开关要退出到设置页。

### 调研（真机实拍）
- 豆包「打电话」/ 通义语音通话同构：三段式「顶部功能条→中央大球（律动主反馈）→底部
  •••+状态字+常驻控制栏（红挂断=退出）」；字幕开关都在场内。「三个点」行业通行但只是弱辅助。

### 收口（晚，真机现场验收全过）
- 全屏五相位（「我在」气泡/大字/声波跟手/三点/打断键）+ 字幕富文本滑动 + 白屏修复
  （旋转/键盘/通知回拉不闪白）现场全过；S14-4 全勾，S0-S14 大盘全收口。

### S15 聆听期反馈泡 +「我在」驻留（改进盘点 1+2，同日追加）
- transition 中心化管理提示泡生命周期：进 LISTENING 显「在听…」（多轮回听 from==SPEAKING
  刻意不显，S5 回复泡驻留语义不动）；离聆听即收。wozai「我在」驻留 2.5s 后 swap job 内容顶替
  （一次性抑制标+世代/在聆双守卫）。461 绿；模拟器按住说话日志链+截图实证；
  真机唤醒路径连贯感 08-25 现场验收过，收口。

### 概率性白屏销案（第一性原理+实验复现）
- 白屏三层拆解（视图树销毁/组合为空/渲染不绘）→ 落盘日志+模拟器实验锁定机制 A：无 configChanges
  →配置变化即重建 Activity（08-18 当天 10 次 onCreate 夹在进全屏之间；强制旋转修复前必现 churn）；
  机制 B：standard launchMode + 三处 NEW_TASK 拉起点可叠加重复实例（08-24 双 enter 无 exit 实锤）。
- 修复：Manifest 补 configChanges 13 项 + singleTask；onNewIntent 加取证日志。
- 验证：修复后全屏中强制旋转零 churn（横屏截图内容完好）；重复拉起走 onNewIntent 复用实例。
- 遗留：横屏全屏布局未优化（edge）；真机包待设备回连后装。

### 施工（S14-1~3 全绿）
- VSM playAckThenListen 挂 bubble.show("我在") + 正常播完 hide（三保险收泡）；WakeAckTest +3 测。
- 全屏三段式重排 + 字幕开关进场 + caption 17sp/「我在」19sp 青色 + 7 柱声波 + 思考三点 +
  底部抽屉（把手一体移动，双键灰底无文字，打断=cancelCurrentTurn 非 IDLE 可点）。
- 基线 457→460 绿 skipped=0；WakeInterrupt ×2 快照点后移适配（wozai 完成收 hide 属预期）。
- 模拟器：三段式+抽屉开合截图入 v19；语音相位（气泡/大字/声波/打断）留真机用户现场验收。

## 2026-08-24（S13 形象动作改造：静止 idle + 说话才动）

> 流程：交接文档（08-24 优化棒）→ 用户提需求「平时不动，说话才动」→ 三轮 ask_user 拍板 →
> HTML 原型 v1（hd-v2 素材）被否 → v2（现 APK idle 行做说话动画）逐项拍板 → 实现。
> 全证据：`docs/verification/v18-s13-pet-motion/README.md`。

### 定案（用户逐项拍板）
- 说话 = 当前 APK idle 行 6 帧 @120ms/帧；idle/waiting/review/failed 静止 = idle 行第 0 帧；
  唤醒跳、点击挥手全取消；hd-v2 2x 素材方案经原型对比被否决（不换素材）。

### 改动
- PetFrameTable/pet_states.json：四循环态 row0 frames=1；TALKING=row0 6帧 720ms；
  pet_states.json 偏离桌面镜像（SHA 60b45124…，notes 记档）。
- 接线：VSM/FullscreenVoiceScreen 唤醒 JUMPING→直接 WAITING（wakeAckActive 门控保留）；
  PetOverlayService 单击挥手移除+playPetOnceThen 删除；ChatScreen 顶栏点击挥手移除；
  PetView playOnceThenIdle/playOnceThen 死代码删除。
- 测试：455→457 绿 skipped=0（+2 新增：静止永不推进/S13 契约；「行号唯一」契约改为 row0 有意共享）。

### 验收
- 模拟器：悬浮窗宠物 1s 双截图裁剪 MD5 逐字节一致（静止铁证）；聊天轮连拍
  SENDING=静止同 hash / STREAMING≠静止帧（diff 局限面部嘴眼区）/ 完成回静止。
- 语音轮主观观感留真机日常回收（模拟器声学边界）。

## 2026-08-21（S12 唤醒词打断转正：spike 双合格 → 实现 → 真机 8/8）

> 承接 08-20 S11 收口后的 spike 观测与立项拍板。全证据：`docs/verification/v17-kws-accuracy/README.md` §6。

### spike 真机双合格（08-20 下午）
- 误触发：TTS ~4min 零命中，且**有效性有喂流佐证**（起始 3 块过门 max rms 0.15，其后回声全在门下）——
  硬件 AEC 把回声压到能量门以下；与 08-04 空证据（没喂）本质不同。
- 检出率：播放中真声 8/10（≥8/10 达标）；AEC 近场人声损耗 ~2 成为固有成本。
- 用户拍板立项实现。

### S12 实现（builder+critic 不逊）
- 路由转正：PROCESSING/SPEAKING 默认进 KWS；spikeFeedKws 调试设施退役（①② 按钮/委托属性/测试随删，
  生产行为取代）。打断接续=cancelCurrentTurn（与 S8-3 按住打断同一分支）→「我在」→LISTENING。
- builder 抓到并修复自引入竞态：wozaiEpoch 世代守卫（唤醒打断唤醒，旧 wozaiJob finally 覆盖新轮信号）。
- 455 绿（454−2+3）；三变异锚亲验；critic 3 P3（F1 注释漂移已修，F2 全屏连喊视觉塌缩记 notes，F3 过时 KDoc 已修）。

### 真机验收（08-21）
- 正常条件 4/4 + 大音量 4/4 = **8/8**（播放中打断链 hit→barge-in cancel→「我在」→LISTENING 日志完整）。
- **未解偶发窗（观察票）**：上午首轮零命中——该窗回声持续过门一分钟（fed +600/gated +0），
  指向 AEC 偶发失效态（疑与刚重装首启有关），重启后消失。复发时先查该窗 fed/gated 形态即可定性；
  频发再评估状态感知门控（S12-2，本轮不盲修）。
- 真机两次掉线插曲（08-20 晚/08-21 晨）均为 USB 连接问题，重插即恢复。

```evidence
verify_cmd: ./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks（XML 亲算：56 suites / 455 tests / skipped=0 / failures=0 / errors=0）；真机 de61cd8 两轮打断实测 8/8（日志 hit #4 大音量轮 18:09:51 全链 + 正常轮 09:24:21 全链）；spike 双合格（误触发 0/4min + 检出 8/10）
verify_claim: 唤醒词打断生产行为验证通过（播放中喊→停播→我在→聆听）；误触发结构性近零（AEC 压回声入门下）；唯一未解窗为 AEC 偶发失效态（观察票）
files_touched: [voice/VoiceStateRouter.kt, voice/VoiceSessionManager.kt, voice/VoiceForegroundService.kt, ui/screens/AecSpikeSection.kt, tests(+WakeInterrupt −SpikeFeedTest −废用例), CLAUDE.md, docs/verification/v17-kws-accuracy/README.md]
```



## 2026-08-20（S11 唤醒词准确率排查：三层根因全修 + 一声唤醒 10/10）

> 接续交接文档 `handoff-desk-pet-android-kws-accuracy-2026-08-19.md`（任务：唤醒准确率诊断 + 唤醒词打断核查）。
> 全证据链：`docs/verification/v17-kws-accuracy/README.md`。执行形态：主编排持票，builder×5 + critic×4（全不逊）。

### 根因链（三层独立叠加，逐层有日志/实验石锤）

1. **阈值 0.5 事故回归**（S11-1）：08-10 验证的 0.3 无测试锚，08-18 事故转录重放静默回退 0.5；
   真机 08-19 重装的正是 0.5 构建。修复=KwsDefaults 常量+精确锚点测试；CONTEXT/spec/对账表三处 0.5 假事实修正。
2. **文字轮云端分支事故丢失**（S11-5）：ChatViewModel 的 llmIsCloud 分支丢失（无锚），
   症状=文字轮「账号或密码未配置」而语音正常。jadx 反编译 device-base/emulator-base 双源取证复原，
   下沉 TextChatRouter 纯逻辑+双 MockWebServer 锚（取证产物入库 docs/verification/forensic-s11-cloud-branch/）。
3. **真凶：门控丢弃语义碎裂 KWS 时间轴**（S11-6 探针 → S11-7 修复）：「整块不喂不补静音」挖掉
   字间气口（打分压到阈值下=第一声必漏）+ 冻结部分分永不冲刷（被下一声补完=「第二声秒中」假象）。
   探针证伪音频延迟论（cap lag=1-4ms）。修复=挂起窗 800ms（VAD 判停同源）+ gap-reset（sentinel 保序、
   消费线程单点 reset、幂等沿）。桌面 sidecar 取证确认桌面同缺第二步语义=超越 parity 的真机证据增强。

### 验收（真机 de61cd8）

- **一声唤醒 10/10**（修复前：第一声 0/10、第二声 10/10）；命中即时，每轮后 gap reset 准点冲刷。
- 基线 438→**454** 绿 skipped=0（+1 阈值锚 +2 spike +3 文字路由 +5 探针 +5 门控；56 套件）。
- 唤醒词打断核查结论：当前结构不支持（三重屏障）；08-04 spike「零误触发」= 空证据（旁路 no-op），
  S11-3 已重做真放行 spike（spikeFeedKws+fed 有效性闸），真机观测待做（见 PROGRESS 接手指引）。

### 执行偏差留痕

- 编排层一度判读错误：把「第二声秒中」归因音频管道秒级延迟（单声等待实验的表象），S11-6 探针数据
  证伪后才锁定门控语义——教训=「第二声必中」类确定性模式是状态机/缓冲问题的指纹，不是检出率问题。
- builder 票号注释误写 S12（实为 S11-6），编排层收口统一更正；critic P3 文档漂移（KwsEngine/AudioProbe KDoc）同轮修正。

```evidence
verify_cmd: ./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks（XML 亲算：56 suites / 454 tests / skipped=0 / failures=0 / errors=0）；真机 de61cd8 装机后：logcat KWS config dump 受 4KB 截断改以行为对拍；探针三声对拍（cap lag=1-4ms）；10 轮单声唤醒 10/10（日志 hits #7-#11 + 每轮 gap reset 冲刷行）
verify_claim: 阈值 0.3 在机生效（行为对拍：一声 10/10）；文字轮云端分支复原（路由锚变异双红）；门控挂起窗+gap-reset 上线后首声检出 10/10、无冻结补完假命中
files_touched: [voice/KwsEngine.kt, voice/KwsDefaults.kt, voice/KwsCore.kt, voice/AudioCapture.kt, voice/AudioProbe.kt(新), voice/VoiceStateRouter.kt, voice/VoiceSessionManager.kt, cloud/TextChatRouter.kt(新), chat/ChatViewModel.kt, ui/screens/AecSpikeSection.kt, tests +5 文件, EMULATOR.md, CONTEXT.md, docs/spec/assistant-app.spec.md, docs/voice-param-reconciliation-2026-07-28.md, docs/verification/v17-kws-accuracy/, docs/verification/forensic-s11-cloud-branch/, docs/solutions/(3 新篇)]
```


## 2026-08-19（S10-3 验收 + 密钥取证恢复）

> 背景：仓库 08-18 整体删除事故后于 08-19 从恢复树克隆回迁（git log 103a496 恢复基线起）。
> 本条目接续交接文档 `handoff-desk-pet-android-s10-3-2026-08-19.md` 执行。08-10~08-18 条目随事故灭失，
> 当日事实见 git 历史与 docs/solutions/workflow-issues/repo-wipeout-five-source-forensic-rebuild.md。

### 任务 A：设备 APK 保全（收口）
- 三指纹亲算落证据 `docs/verification/v16-edge-dock/apk-preservation-2026-08-19.md`：
  device-base.apk sha1=f018793f…（S9 最终构建 90,876,784B）/ emulator-base.apk sha1=5d7447ae…
  （90,703,733B 与交接口径逐字节一致）/ 恢复树新构建 sha1=eb8ec300…（90,703,737B，与 emulator-base
  4B 漂移属重建非确定性）。

### 任务 B：S10-3 剩余 Case 验收（模拟器 D/E/F/G + 真机一轮，全绿）
- 模拟器 emulator-5554（装新构建后逐 Case 取证，v16 目录重建续写）：
  B 反例屏中松手零 slide ✅ / A 左吸 x=-126 ✅ / A2 右吸 x=954 ✅ / C·C2 单击滑出 0·828 ✅ /
  D 贴边长按菜单全项左缘=0 钳回+点「打开 App」拉起 MainActivity ✅（打断/查看最近一轮 IDLE 置灰=门控正确）/
  E 左贴+右贴气泡本体完整可读 ✅（显示层容器可越缘、气泡内容钳屏内）/
  F force-stop→自愈 `dual windows added ... at (954,617)` 逐像素恢复 ✅ /
  G wm size 720 收窄 `config changed -> reclamped to (594,617)`=dockedX(RIGHT,252,720) 不消失 ✅。
- 真机 de61cd8（device.lock 纪律）：RM-1~RM-5 几何全绿（dockedX ±468/972、slideOutX 0/503 逐值命中）+
  RM-6 贴边态语音一轮全链 ✅（wake→ASR→LLM firstToken 456ms→TTS→气泡，全程 x=-468 不变）。
- `git diff --stat HEAD -- voice/` 为空（红线亲证）；438 基线 `--rerun-tasks` 复跑 51 套件全绿 skipped=0。

### 顺带修复（验收揭露的恢复遗留缺口，P1 级）
- **症状**：RM-6 首轮 `pipeline abort at ASR: 阿里 ASR 未配 dashscope key`。
- **根因**：`secrets/cloud.properties`（gitignored）不随 git/恢复树回迁 → 恢复树构建三密钥全空。
- **修复**：从 device-base.apk 反编译 BuildConfig（D:\desk-pet-recovery\apk-src\…）取证提取
  CLOUD_KEY/ALIYUN_SPEECH_KEY（len=116，VOLC 原状空）→ 写回 secrets/cloud.properties →
  重建（APK sha1=070d4530…）→ 双端重装 → 真机语音轮实证 ASR/LLM 恢复。
- **教训**：gitignored 本地文件是恢复树的天然盲区——本机秘密类文件的事故保全清单应含 secrets/（已入证据文件备注）。

### 执行偏差留痕
- RM-5 首拍截图误含前台聊天内容 → 即删重拍（HOME 后只留左缘裁切区）；临时全屏原图 rm*_raw.png 已全部删除。
- 真机截图只入库左缘裁切条（桌面图标属通用启动器内容，无私人信息）。

```evidence
verify_cmd: ./gradlew.bat testDebugUnitTest --rerun-tasks（XML 亲算：51 suites / 438 tests / skipped=0 / failures=0 / errors=0）；模拟器 D/E/F/G + 真机 RM-1~RM-6 dumpsys/logcat/截图像素断言全绿；git diff --stat HEAD -- voice/ 为空
verify_claim: S10-3 全 Case 过；贴边几何 dockedX/slideOutX 双端逐值命中；force-stop 自愈逐像素恢复；wm size 收窄重钳不消失；贴边态语音一轮全链（firstToken 456ms）；密钥恢复后 ASR/LLM 实证恢复
files_touched: [docs/verification/v16-edge-dock/（31 证据文件+README）, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md, implementation-notes.html]
```

## 2026-08-07（全屏语音模式精简 + 唤醒应答去「哎」）

### 需求与定标（wayfinder grilling 两轮锁定，用户逐项拍板）
- ① 删相位光环 PhaseHalo（状态反馈由 caption 文字+迷你声波+宠物四态承担，三层冗余本就在）；
  ② 删红色挂断钮（实测与右上角关闭同=退出全屏，零功能损失）；
  ③ 删长按菜单三项+底部提示（退出全屏/打开设置/隐藏悬浮窗均与既有入口重复）；
  ④ 唤醒应答「哎,我在。」→ 纯「我在」。
- 禁改边界：悬浮窗手势集的长按菜单不动；KWS/语音链路逻辑不动。
- 反例样本：长按小朵不弹菜单；四态无光环绘制；wozai 开头无「哎」。

### 施工
- FullscreenVoiceScreen.kt：删 PhaseHalo + 其无限相位动画 + 挂断 Surface + 长按
  DropdownMenu + 底部 hint；haloRms→listenRms（迷你声波仍消费，RMS EMA 管道保留）；
  底部转写区改 `if (transcriptOn)` 包裹；降级提示连带义务：「可长按进设置」→
  「请退出后到设置页…」；签名去 onOpenSettings，AppNavHost 同步。
- wozai.wav 重制（双路线候选，脚本 `.scratch/wozai/remake_wozai.py`，stdlib，
  后处理口径沿用 08-06：去头尾静音+峰值归一 26000+44B RIFF）：
  a) 平台 TTS 重合成「我在」（cosyvoice3/default，0.95/1.0 两速）；
  b) 包络分析找「哎，」与「我在」间静音缝（@0.31s/220ms）裁剪现行 e3。
  用户试听选定 **b-trim**（0.54s，24k/mono/16bit）。
- 备注：08-06「哎,我在。」选型（小米 wakeup_ei 调研驱动，implementation-notes 有记录）
  被 08-07 用户改判推翻——该条 notes 语义已过期，仅作历史。

### 验证
- JVM 单测 372 绿 skipped=0（基线持平；被删 UI 组件无测试引用）。
- 真机 de61cd8：install -r → 全屏语音模式截图=只剩小朵+caption+右上角 X
  （无光环/挂断钮/底部提示）；长按 1.5s 后 uiautomator dump 无菜单项（反例①过）；
  降级 hint 新文案实测显示。
- 唤醒应声新音频待用户真声轮主观确认（验证时设备 KWS 开关为 OFF，未触发唤醒）。
- **2026-08-08 用户确认**：真机唤醒后听到的应声为纯「我在」，无「哎」前缀，b-trim 定稿生效。

```evidence
verify_cmd: gradlew testDebugUnitTest（372/0/0）+ adb install -r + exec-out screencap + uiautomator dump（长按反例）+ wav 头校验
verify_claim: 截图无光环/挂断钮/底部提示；长按后 dump 无「打开设置/隐藏悬浮窗」菜单项；wozai.wav=24000Hz/mono/16bit/0.54s
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/navigation/AppNavHost.kt, app/src/main/assets/wozai.wav, CONTEXT.md, DEV_LOG.md]
```

## 2026-08-06（续 3：真机回归 — KwsCore 重接线 + 孤儿空采修复）

### 真机验证（de61cd8，install -r debug 包，全链路日志取证）
- **重接线首跑**：install 后自愈拉起，`always-on KWS started, gate=0.015`——SherpaNative
  薄适配层真机加载 native 模型正常（review 残余风险「适配层零 JVM 覆盖」此条闭环）。
- **检出率日志真机生效**：`kws stats: fed=137 gated=464 decoded=137 hits=0 uptime=60s`
  每 60s 音频活动一行，四计数器全部实采。
- **唤醒链路端到端**：`keyword hit: 小朵小朵 (#1, fed=552, gated=2110)`（新流水号格式）
  → wozai → LISTENING；第二轮「小朵小朵」走 strip-empty 重播我在（真机再次实证该修复）；
  空 utterance → IDLE。
- **release 路径**：设置页关 KWS → `released, fed=896 gated=8013 decoded=896 hits=1
  uptime=905s` 汇总行 → `capture stop`（孤儿空采修复真机生效：收得干净）。
- **开关循环 ×6**：start/released 全成对（uptime 3s×6），进程 22435 全程存活，
  无 skip native free / native free failed / decode error；tombstones 无新增
  （目录内仅 2025-09 QQ/dex2oat 遗留）。
- **检出率首份真实数据**：用户连喊两次「小朵小朵」→ hits=1（第二次在 LISTENING 态
  被作 utterance 消费，属设计行为非漏检）。漏检率暂无可报告样本。
- 验证后 KWS 已恢复 ON，设备锁已还。

```evidence
verify_cmd: adb install -r + 设置页 UI 开关循环 + run-as tail files/logs/xiaoduo-2026-08-06.log
verify_claim: 唤醒命中 #1 新格式/stats 周期行/released 汇总行/capture stop 均见日志原文；6 循环 start-release 成对；pid 22435 全程存活；tombstone 无新增
files_touched: []（设备侧验证，无代码变更）
```

## 2026-08-06（续 2：ce-code-review 五修复 + 麦克风孤儿空采 P1）

### 背景
- KWS 修复两 commit 后跑 /ce-code-review（7 本地 reviewer + validator 独立复核；
  跨模型 peer 跳过=codex CLI 无路由，adversarial 同进程兜底），5 条 actionable 全 validated；
  随后修调研遗留 P1：对话中销毁 KWS 服务 → 麦克风孤儿空采。

### review 修复（findings #1-#5，同批 TDD）
- #2 P2 release 期命中不派发：KwsCore onKeyword 前 running 复查（命中只记 dropped 日志）+
  VoiceForegroundService.onWake 加 destroyed 早退（第二道幻影唤醒防线）。
- #1 P2 skip-free 分支补测：gate 永不开闸 → freeCount==0 + skip 日志；假身
  awaitUninterruptibly 修返回值检查（10s 超时自开使「卡死」不可建模）。
- #3 P2 文档同步：CLAUDE.md/AGENTS.md「剩余」行删 KWS 项 + AGENTS 补 08-06 续句。
- #4 P3 free 失败不吞：KwsCore.release + KwsEngine.SherpaNative 双 runCatching 加 onFailure 日志。
- #5 P3 删测试重言式死行；#6 advisory 顺带：stats 测试改单块确定性设计（除竞态窗）。

### 孤儿空采修复（审计 P1，与 review #2 同根=采集流归属权无单一 owner）
- 根因：stopIdleCapture 在 state != IDLE 时直接 return；服务 onDestroy 先 kwsSink=null
  再 stop → 对话结束回 IDLE 后 collectJob 挂活收块、路由到 null sink 全丢，AudioRecord
  永不收（麦绿点常亮、耗电、无日志）。
- 修复=意愿登记制：idleCaptureWanted 标志；start/stop 只登记意愿；回 IDLE 的 transition
  统一按意愿 teardownIdleCapture（采集流归属单一裁决点）。
- 新增 VoiceSessionManagerIdleCaptureTest 3 测：对话中 stop 延迟至 IDLE 收 / IDLE stop
  立即收（旧行为保持）/ KWS 意愿仍在时对话结束不收（非回归）。
- 测试缝：VSM internal val collectLoopActive。
- 变异锚实测：删 transition teardown → 孤儿测试红；删 running 复查 → hit-dropped 测试红
  （同轮亲见 BUILD FAILED 后还原复绿）。

### 验证
- `./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL，
  XML 亲算 tests=372 failures=0 errors=0 skipped=0（44 套件，366+6）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL；XML 亲算 tests=372 skipped=0 failures=0 errors=0（44 套件）；变异锚=删 transition teardown / 删 running 复查各红对应测试（亲见 FAILED 后还原）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsCore.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsEngine.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerIdleCaptureTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/KwsCoreTest.kt, CLAUDE.md, AGENTS.md]
```

## 2026-08-06（续：KWS 检出率日志 + release native 竞态修复）

### 背景
- 四路 subagent 并行深度调研（测试真实性/架构脆弱点/文档漂移/产品运维风险）后，用户选定
  架构路 P1 项开工：`KwsEngine.release()` 未 join decode 线程即 free native
  （in-flight decode → use-after-free → SIGSEGV 无 Java 堆栈）+ 观察项「KWS 检出率无日志」。

### 改动（TDD，主会话直接执行）
- 新增 `voice/KwsCore.kt`：native 边界抽 `KwsCore.Native` 接口，门控/队列/decode 线程/
  统计/release 时序全部下沉 JVM 可测；`KwsEngine` 瘦身为 sherpa 构建 + SherpaNative
  薄适配（对外构造签名不变，VoiceForegroundService 零改动）。
- release 时序（竞态修复）：CAS 单次放行 → running=false → interrupt → join(2s) →
  确认线程退出才 free；join 超时宁可泄漏 native 句柄不硬 free（崩溃规避 > 内存泄漏）。
- 检出率度量：fed/gated/decoded/hits 四计数器；命中行带流水号 `#N` + 分母；
  每 60s 音频活动落 `kws stats:` 行，release 落汇总行——检出率可由日志原始数据回算。
- 新增 `KwsCoreTest.kt` 8 测：release 竞态/幂等单 free/命中计数/stats 四计数/汇总行/
  单块异常续跑/release 后 feed 丢弃/空载速放。
- 变异锚实测：删 join → release 竞态测红（BUILD FAILED 亲见后还原）。
- 测试假身教训：FakeNative.decode 初版用可中断 latch，被 release 的 interrupt 提前唤醒
  造成假阳性红——native JNI 调用不响应线程中断，假身须 awaitUninterruptibly 同构。

### 验证
- `./gradlew.bat testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL，XML 亲算
  tests=366 failures=0 errors=0 skipped=0（43 套件，358+8）。
- KwsEngine 重接线后 `./gradlew.bat assembleDebug testDebugUnitTest` BUILD SUCCESSFUL。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks && ./gradlew.bat assembleDebug
verify_claim: 均 BUILD SUCCESSFUL；test-results XML 亲算 tests=366 skipped=0 failures=0 errors=0（43 套件）；变异锚=删 join 后 release 竞态测红（亲见 BUILD FAILED，已还原复绿）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/KwsCore.kt, app/src/main/java/com/xiaoduo/deskpet/voice/KwsEngine.kt, app/src/test/java/com/xiaoduo/deskpet/voice/KwsCoreTest.kt, CLAUDE.md, AGENTS.md, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md, implementation-notes.html]
```

### 基线同步（358 → 366，43 套件）
- CLAUDE.md（基线行 + 当前状态段 + 包结构 voice 16→17 文件）/ AGENTS.md /
  PROGRESS.md（状态行 + 接手指引）/ CONSTRUCTION.md 纪律条。
- 观察项「KWS 真实检出率无日志」收口（PROGRESS 接手指引划除；implementation-notes 对应 open 条后注）。

## 2026-08-06（S8 真机逐票验收 + 真机期四修复 + 分票提交）

### 会话背景
- 交接文档恢复（s8-handoff-2026-08-05），主会话直接执行（用户拍板不派 builder/critic）
- 基线复跑 356 绿 → 收口时 358 绿 skipped=0（+2：P2 竞态测 + strip-empty 重听测）

### S8 真机逐票验收（真机 de61cd8，证据 docs/verification/）
- S8-1 ✅ 双态截图（s8-1-text-state*.png）；S8-2 ✅ 深色壁纸（s8-2-dark-wallpaper.png，浅色项用户跳过）
- S8-3 ✅ 日志链 19:03:56 barge-in→IDLE→LISTENING→ASR→LLM 一条线
- S8-4 ✅ 气泡截图零裸控制符（s8-4-bubble-a/b.png）
- S8-5 ✅ bubble show 12.096 早于 first audio 13.950 约 1.85s（解耦铁证）
- S8-6 ✅ 菜单项非置灰+App 定位最近一轮（s8-6-menu2/last-round.png）
- S8-7 ✅ 单测 3 变异锚+日志状态链+用户多轮目视（连帧截图三轮连抓差数秒未中，用户拍板组合证据收口）
- S8-8 ✅ 三点等候两相位帧+首 token 后消失（s8-8-*.png）

### 真机期四修复（用户反馈驱动）
- **S8-5 P2 竞态窗** ✅：delta callback 内 bubble.show/append 前 double-check sealed（cancel 可在入口检查
  与 bubble 间过境→幽灵气泡）。TDD：BlockingFirstDeltaSink 首 delta 内挂起撑开竞态窗，未修时
  `[hide, show]` 实红；变异锚删守卫→红→还原绿。+1 测
- **B strip-empty 死路** ✅：纯唤醒词 utterance（剥离后只剩标点）旧行为静默回 IDLE=「唤不醒」元凶
  （19:09 四轮实证）。修为重播「我在」继续聆听（playAckThenListen() 抽取共用；sealed/旧轮守卫）。
  真机复验：12:17:03 日志「重播我在继续聆听」→用户接续提问成对话。+1 测（变异锚实测）
- **D 多功能键有概率不显 STOP** ✅：根因=voiceActive 边沿事件，VM 在语音轮中途创建错过边沿
  （19:46:49 onCreate 晚于 19:46:44 onUserUtterance，日志实锤）。修=keyStopActive=voiceActive∪
  VSM.stateFlow非IDLE（状态源，与 cancel 门控同源）。真机复验：唤醒轮流出中打开 App，停止键在，
  12:17:22 用户按停止键 barge-in 成功
- **wozai 应答音重制** ✅：调研（小米原厂应答=wakeup_ei「哎」，非陈述句朗读）→ 两轮 13 候选合成
  试听 → 用户选定 e3「哎,我在。」(speed 0.95，去静音+峰值归一 26000)。真机确认听感提升
- **C 输入框单容器** ✅：S8-1 两层独立 Surface 观感不佳 → 用户拍板单圆角容器+内部横线
  （c-inputbar-*.png 双态）。R3 功能修复第三次迁移全保留

### 事故/教训留痕
- 验收首日 19:00 让用户喊唤醒词时 KWS 开关尚未生效（编排顺序失误）→ 语音项验收先核服务态再让用户动口
- adb 设备路径 MSYS 改写：引号仍偶发双写诡案，`MSYS2_ARG_CONV_EXCL="*"` 是一劳永逸解
- 连抓窗口与用户动作时机四次错位（差 10s~1min）→ 动态事件取证应日志优先、截图补强

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL；test-results XML awk 亲算 tests=358 skipped=0 failures=0 errors=0（42 套件）
files_touched: [app/src/main/res/mipmap-*/ic_launcher.png ×5, app/src/main/res/values/ic_launcher_background.xml]
```

```evidence
verify_cmd: 同上（全量 358 绿）
verify_claim: 同上；BubbleTextStripTest 亲算含 S8-4 +6 测
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt, app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStripTest.kt]
```

```evidence
verify_cmd: 同上（全量 358 绿）+ 真机双态截图
verify_claim: 同上；docs/verification/s8-1-*.png、c-inputbar-*.png 双态单容器+横线可见
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt]
```

```evidence
verify_cmd: 同上（全量 358 绿）+ 真机日志链
verify_claim: 同上；BargeInTest/WakeAckTest 变异锚实测（删 double-check sealed/删重播调用皆红）；真机 19:03 barge-in 接续链 + 12:17 strip-empty 重播链
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/ui/PetView.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerBargeInTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt, app/src/main/assets/wozai.wav]
```

```evidence
verify_cmd: 同上（全量 358 绿）+ 真机交互
verify_claim: 同上；s8-6-menu2/last-round.png 菜单定位取证；12:17:22 用户停止键 barge-in 日志（keyStopActive 修复后）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt, app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt]
```

### 分票提交（6 commit，main 无 remote）
- `fe76cec` S8-2 图标白底（mipmap×5 + background.xml）
- `d744a9a` S8-4 气泡 markdown 剥离（BubbleTextStrip + Test）
- `06a82b0` S8-1 + 单容器拍板（InputBar.kt）
- `3377530` voice 打包（S8-3/S8-5/S8-7 + P2 竞态修 + strip-empty 重听 + wozai e3）
- `640203a` chat 打包（S8-6 + keyStopActive 状态源化）
- `0b7f1bd` 文档 + docs/verification/ 证据

### 文档真相审计（三 subagent 并行只读，主会话统一落笔）
- 方法：根文档声明 vs 代码/git 实况 / docs 树 spec 漂移+死链 / 反向审计（代码有而文档无）
- 修复 20 处：CLAUDE.md（~64 文件、VoiceSessionManager 33KB 全仓最大、ui 15 文件、
  +VoiceClientFactory/playAckThenListen/RevealScheduler 退役标注、adb 改 MSYS2_ARG_CONV_EXCL 口径）；
  AGENTS.md 全量同步（S0-S8/358/当前状态段）；PROGRESS 接手指引 docs/ 前缀错误路径 + 补第 6 个 solution；
  CONSTRUCTION S7-e AEC「进行中」改「已挂起」+ 基线 345→358；DEV_LOG 补六个 commit 哈希 +
  de61cd8 消歧（是设备序列号非哈希）+ 08-05 P2/P3 前向指针；plan 002 KD13/R7 撤销后注；
  两个 spec（RevealScheduler 退役/keyStopActive/单容器/strip-empty 边界/应答音 e3/封存注）；
  MASTER-PLAN + dev-handbook README/09 现行状态行；InputBar.kt 头注释 + 拍板日期 08-05→08-06（commit 时间佐证）
- 存疑留档：`.scratch/xiaoduo-android/issues/` 系早期 force-add 被跟踪（.scratch/ 整体已 ignore，维持现状）；
  四修复未沉淀 docs/solutions/（待用户拍板是否 /ce-compound）
- 验证：testDebugUnitTest BUILD SUCCESSFUL，XML 亲算 tests=358 skipped=0 failures=0 errors=0（42 套件）

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL；test-results XML awk 亲算 tests=358 skipped=0 failures=0 errors=0（42 套件）
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt（仅注释）, CLAUDE.md, AGENTS.md, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md, docs/MASTER-PLAN.md, docs/dev-handbook/README.md, docs/dev-handbook/09-m3-m4-plan.md, docs/plans/2026-07-30-002-feat-xiaoduo-assistant-app-plan.md, docs/spec/assistant-app.spec.md, docs/spec/assistant-ui-voice-input.spec.md]
```

### ce-compound 沉淀真机期四修复（lightweight × 4，一学习一文档）
- `docs/solutions/logic-errors/delta-callback-check-then-act-ghost-bubble.md`（① S8-5 P2 竞态窗）
- `docs/solutions/logic-errors/strip-empty-wake-word-dead-end-replay-ack.md`（② 纯唤醒词死路）
- `docs/solutions/ui-bugs/edge-event-missed-keystopactive-stateflow.md`（③ 边沿→状态源）
- `docs/solutions/design-patterns/wake-ack-audio-selection-cosyvoice.md`（④ 应答音选型）
- frontmatter + doc-claims 双校验器全过（0 flags；Windows 需 PYTHONUTF8=1 防 GBK 读炸）；
  CONCEPTS.md 项目根不存在，lightweight 纪律只更新不创建，留待 Full 轮

## 2026-08-05（S8 代码侧收口：八票全绿，356 测 skipped=0）

### 会话背景
- 从 anzhuo4 死会话恢复（API 429 5 小时用量上限），加载上下文后继续
- 用户选择执行模式：串行关键路径（S8-3→S8-7）+ 每票派 opus critic 盲审
- 纪律：单测只增不减（基线 345→356），skipped=0，禁 mock/skip/删测；每守卫必须有变异锚测试

### S8-1 输入框视觉回滚两层+横线 ✅
- `ui/screens/InputBar.kt`：R3 单容器 → 两层独立 Surface + 层间 HorizontalDivider（outline 色 1dp）
- R3 功能修复全保留（行高 48、FunctionBar padding 4dp、isUp 快照、idle 粉底 gate、停止键、上传链）
- 纯视觉变更，无新单测

### S8-2 图标白底 ✅
- `res/mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`：5 密度 PNG 替换（#FFFFFF 底）
- `res/values/ic_launcher_background.xml`：改 `#FFFFFF`

### S8-3 TTS 中按住说话=打断并接续新一轮 ✅
- `voice/VoiceSessionManager.kt`：startListening speaking/processing 态按住 → cancelCurrentTurn → LISTENING
- 3 处 sealed 回归守卫：consumeSentences 循环头 + 合成后二查 + 多轮迁移
- `voice/VoiceSessionManagerBargeInTest.kt`：+2 测（PROCESSING 打断 4 断言 + SPEAKING 打断 stateFlow 闩）
- critic：不逊，4 P2 → 修复 3，1 留档（second-check sealed 单测不可达，生产环境仍有价值）
- 测试 351→353

### S8-4 气泡渲染 markdown 控制符修复 ✅
- `ui/overlay/BubbleTextStrip.kt`：裸 markdown 控制符剥离
- `ui/overlay/BubbleTextStripTest.kt`：+6 测（无空格标题/引用/加粗/斜体/行内代码/表格管道符）
- 测试 345→351（S8 开工前已完成）

### S8-5 气泡文字改流式直出（撤 reveal 音频同步） ✅
- `voice/VoiceSessionManager.kt`：AG-UI delta 回调直接 bubble.show/append，与 TTS 播放解耦
- 退役 playAndReveal/reveal 方法（RevealScheduler 类保留备查）
- TTS 单句失败降级：气泡文字不受影响（delta 已上屏），仅跳过本句播放
- critic：不逊，1 P2（delta callback check-then-act 竞态窗：L438 sealed→L444 bubble.show）→ **待修**（2026-08-06 已修，见 08-06 条目）
- critic：1 P3（BargeInTest 注释 "reveal 逐字查岗" 过时）→ **待修**（2026-08-06 已修，见 08-06 条目）

### S8-6 气泡「看不到开头」补救 ✅
- `ui/overlay/OverlayMenu.kt`：新增「查看最近一轮」菜单项（canViewLastRound 门控）
- `PetOverlayService.kt`：hasShownBubbleContent 标记 + playPetOnceThen companion
- `MainActivity.kt`：handleViewLastRoundIntent + onNewIntent → ChatViewModel
- `chat/ChatViewModel.kt`：pendingScrollMessageId 一次性事件（哨兵 `__last__`）
- `ui/screens/ChatScreen.kt`：LaunchedEffect 消费 → scrollToItem + listState 上提
- critic：不逊，1 P2 → **降级 P3**（sentinel 单测 JVM 不可达，项目无 Robolectric）+ 4 P3

### S8-7 唤醒成功即时视觉反馈 ✅
- `voice/VoiceSessionManager.kt`：`_wakeAckActive: MutableStateFlow<Boolean>` + 公开 StateFlow
- startListening(playWakeAck=true)：入口置 true + playPetOnceThen(JUMPING, WAITING)
- transition()：SPEAKING + wakeAckActive → 跳过通用 TALKING 映射
- wozaiJob finally：无条件 reset false（critic P2 修复）
- cancelCurrentTurn：reset false
- `ui/screens/FullscreenVoiceScreen.kt`：LaunchedEffect 订阅 wakeAckActive
- `ui/PetView.kt`：playOnceThen 脚手架
- `voice/VoiceSessionManagerWakeAckTest.kt`：+3 测（时序/按住路径/cancel 复位）+ 1 增强
- critic：不逊，1 P2 已修复 + 5 P3
- 测试 353→356

### S8-8 生成中等候态三点动效 ✅
- `ui/screens/ChatScreen.kt`：TypingDots Composable（三圆点错相呼吸，900ms 周期）
- SENDING 状态替换原 CircularProgressIndicator + "小朵思考中…" 文字
- critic：不逊，2 P3

### 测试基线
- **356 绿 skipped=0 failures=0 errors=0**（42 测试套件全部零失败）
- 增量：HEAD 345 → S8-4 +6 → S8-3 +2 → S8-7 +3 = 356
- 验证命令：`./gradlew.bat testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL

### 工作树
- `git diff HEAD --stat`：20 files, +595/-153
- 零 commit，所有变更在 working tree

### Critic 汇总
| 票 | 结果 | P1 | P2 | P3 |
|---|---|---|---|---|
| S8-3 | 不逊 | 0 | 4→1 | 0 |
| S8-5 | 不逊（首轮 critic 死亡，重派） | 0 | 1 | 1 |
| S8-6 | 不逊 | 0 | 0 | 5（含 P2 降级） |
| S8-7 | 不逊 | 0 | 0 | 5 |
| S8-8 | 不逊 | 0 | 0 | 2 |

### 剩余事项
1. **S8-5 P2**：delta callback check-then-act 竞态窗（真机期顺修）
2. **S8-5 P3**：BargeInTest 注释更新
3. **真机逐票验收**（见 CONSTRUCTION.md 各票验收条）
4. **分票 git commit**（当前零 commit）

```evidence
verify_cmd: ./gradlew.bat testDebugUnitTest --rerun-tasks
verify_claim: BUILD SUCCESSFUL, 356 tests, skipped=0, failures=0, errors=0
files_touched: [MainActivity.kt, PetOverlayService.kt, ChatViewModel.kt, PetView.kt, BubbleTextStrip.kt, OverlayMenu.kt, ChatScreen.kt, FullscreenVoiceScreen.kt, InputBar.kt, VoiceSessionManager.kt, mipmap-*/ic_launcher.png, ic_launcher_background.xml, BubbleTextStripTest.kt, VoiceSessionManagerBargeInTest.kt, VoiceSessionManagerWakeAckTest.kt]
```

## 2026-08-05（S7 收官 + S8 微调合集立项 + P1 解法沉淀）

- **震动验收闭环**：用户手感确认按住说话按下一瞬有震动（S7-f 真机验收最后一格勾掉，全闭）。
- **S8 UI 微调合集立项**（用户拍板五项，CONSTRUCTION.md S8-1~5 出票）：①输入框回滚两层+恢复横线
  （**推翻 S7 盲测驱动的单容器方向——用户视觉拍板优先于盲测口径**，P1/P3 手势修复与功能层参数保留）；
  ②图标白底；③TTS 中按住说话=打断并接续新一轮（S7 留的产品裁决项落定）；④气泡渲染残留修复；
  ⑤气泡文字改流式直出、撤 reveal 音频同步（修订 KD13）。深色细节项撤回不做；AEC spike 维持挂起。
- **文档收口**：BLOCKED.md 盲测关账条加 S8-1 后注（「能力 chips 重赛」条件作废）；CONSTRUCTION S7-f
  验收行收尾（盲测关账+观察项落定 S8-3）；PROGRESS 状态行指向 S8。
- **ce-compound 沉淀**（Full 模式）：`docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md`
  ——consume 先于 changedToUp 恒 false 字节码级根因+先判后吞铁律+手势评审检查清单。
  交叉发现（session history）：本仓 consume 语义坑第二例（第一例=07-31 clickable+requireUnconsumed）。
  grounding 校验：7 条行号断言逐条命中、javap 独立复跑证实、1 条行数统计修正、1 条路径笔误修正。
  重叠评估 low（与 material3 抽屉篇同域独立）；CONCEPTS 扫描无合格新词（框架术语不收）。

```evidence
verify_cmd: git log --oneline -1(4ffdadf 用户 S8 立项提交);python validate-frontmatter.py/validate-doc-claims.py docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md
verify_claim: frontmatter 校验 OK;claims 校验残余 1 flag=de61cd8 设备序列号误报(已注释为 intentional);语义校验 7 行号全中+2 修正落盘
files_touched: [docs/solutions/ui-bugs/compose-hold-to-talk-consume-before-changedtoup-stuck.md, BLOCKED.md, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md]
```

## 2026-08-04（晚：S7-f 真机验收+盲测组 2 R1 负返工+AEC spike 轮 2 后用户叫停）

### AEC spike 轮 2 + 用户拍板挂起

- 轮 2:spike 旁路开启（实测单轮自停设计），长回复（reply 516 chars）TTS SPEAKING 19:10:11→
  末帧 19:12:57 全程 KWS 解码，`wake word hit` 计数 **0**（.scratch/aec/aec-r2.log 全文）。
  两轮累计 ~5.8min 0 命中，≥5min 口径已达；轮 3 两次 PC 音频驱动未达 VAD 阈值（音量/位置漂移，
  10× 放大仍 no utterance），改手机本地播放器驱动方案刚起步即被用户叫停。
- **用户 2026-08-04 晚拍板：唤醒词误触暂时不测** → 整体挂起记 BLOCKED（检出率 10 次真声亦未测，
  恢复条件=用户发话）。设备侧已还原：spike 旁路关、强停误拉起的音乐播放器、回聊天页。

### 盲测组 2 R1 负（封盲 4 对 20 项）→ v3 返工派工

- vs DeepSeek idle 全项负（2.5-3 vs 3.5-4.5）：两段式「输入胶囊+散养图标行」中间死区=系统性病灶；
  hold 对 1 胜 4 负（精致度 2.5 硬伤：点柱混杂波形+「松手发送 上移取消」无分隔符）；
  生成中停止键对全胜平（对手功能胶囊裁字硬伤）；vs 小布 idle 全项负（对手单横条集成）。
- 我方胜点保留：录音中模式退出控件可见（DeepSeek hold 态无退出路径）。
- 返工票（builder 已交付待 critic）：单容器收编 InputArea+FunctionBar/波形 MIN 3→6dp 统一短柱/
  文案「松手发送，上滑取消」「按住说话」/+ 与切换键统一 36dp 圆圈容器（底色 surface 防隐形已声明）。
- 参照物补抓：DeepSeek 生成中停止方块 .scratch/refs/deepseek-generating-stop.png。

### 盲测组 2 R2（v3 单容器后）：2 胜 2 负，连败 2/3

- **hold 对 4 胜 1 负大逆转**（R1 曾 1 胜 4 负）：布局 4.5:3.5（录音条贴着触点+保留会话上下文，
  对手全屏态反馈与触点分离）/留白胜/精致度胜（我方波形「高低错落有振幅」，对手柱高一致呆板）/
  状态反馈平。波形返工（MIN 3→6dp 统一短柱）+触点原位设计被验证。
- **生成中停止键对全项胜**：布局 4.5:3/层级 4.5:3.5/状态反馈 4.5:4——纯黑实心方块独占重色焦点；
  对手（DeepSeek）chips 文字截断硬伤+停止键与功能 chips 同蓝色系争焦。
- **语音空闲/文字空闲两对仍负**，病灶同一：**容器纵向空洞**（输入行与图标行间大段空白，
  对手两行紧凑密度高）。「无能力 chips」属产品功能差异非视觉返工可解，留档。
- R3 票（连败 2/3 最后一次）：只收行间距（FunctionBar vertical padding 最紧+行间零附加间距；
  56dp 两态同高不跳变保留，可降至 48 双态同值），builder 派工中。

### 盲测组 2 R3 与三连败关账（2026-08-05 凌晨）

- R3 返工（容器 120→104dp：行高 56→48 两态同值+FunctionBar padding 8→4）critic 不逊
  （零回归逐字节核/104dp 复算自洽/345 测亲跑），P3 观察项=48dp TextField 首行纵向偏置
  （真机截图断言：文字完整可读不裁切，观感偏顶置可接受）。
- R3 封盲：停止键对**第三连胜**（对手 chips 截断+同色争焦），hold 对 2 平 3 负（抓帧期 PC 音量
  漂移波形帧呆板，材料不可比），空闲两对仍负（对手能力 chips 信息密度=产品功能差异）。
  **三连败按协议关账记 BLOCKED**，R3 代码保留不回退。重赛触发条件=产品加能力入口 chips。

## 2026-08-04（S7-f 真机验收：P1 松手不物化+P3 idle 粉底双修复 critic 不逊，验收条近全勾）

### 疑似缺陷确诊（Explore 静态侦查，字节码级实锤）

- **P1 松手不物化**：`InputBar.kt` VoiceHoldArea 手势循环先 `change.consume()` 后查
  `change.changedToUp()`——compose-ui 1.6.8 字节码实证 `changedToUp()` 首条指令即
  `if (isConsumed()) return false`（javap 双 agent 独立亲验），先吞后判 → 恒 false →
  松手永不 break → onVoiceEnd/stopListening 物理不可达。真机后果链：VAD 空转 30s 超时自收、
  UI 永卡按住态、第二次按住事件被卡死循环吞掉零日志。**本构建「松手发送」从未工作过。**
- 修复：consume 前快照 `val isUp = change.changedToUp()`，净增 2 行。critic 不逊
  （javap 复核/diff 白名单/345 测亲跑/泄密 0/五场景推演无残留路径）。
- **P3 idle 永久粉底**：上滑取消后 `cancelled`（remember）仍为 true，Surface 配色 when 不看
  holding → idle 态挂 errorContainer 粉底直到下次按下。修复：`holding && cancelled -> errorContainer`
  gate 视觉层（松手收尾还要消费 cancelled 分流，清态引入时序耦合，gate 更干净）。
  critic 不逊（7 处 cancelled 消费点逐一核，idle 渲染分支零引用；切键盘离组合 remember 销毁双保险）。
  critic 提示留档：完备性依赖「每次按下重置 cancelled=false」那行，后续改动勿删。

### 真机验收战果（de61cd8，截图 .scratch/s7f/，日志原文见 PetLogger/logcat）

- ✅ **松手发送全链**：`18:31:24 VAD utterance, 19200 samples, maxLevel=0.4146` →
  `state -> PROCESSING (asr+llm)` → `asr ok, text=6 chars` → `ASR->LLM send latency=261ms` →
  run POST → first token → SPEAKING；用户气泡「海得找大箱。」上屏（PC 5×放大音频驱动，
  远场识别质量不究）。另两轮无音频松手：`manual release, no utterance` 静默回 IDLE 不误发 ✅。
- ✅ **声波纹起伏**：w7（说话中柱高成簇起落）vs w8（尾段贴底）帧对比，RMS 驱动实证。
  （此前两轮全平根因=VAD 阈值 0.05 高于远场拾音 0.034，非渲染缺陷。）
- ✅ **上滑 80dp 取消无消息发出**×2：`LISTENING → IDLE (cancel listening)` 之间零 ASR/LLM 活动。
- ✅ **上传真实文件 fileId 进列表**：73B/15MB/19.5MB 三连（`uploadFile ok, fileId=…` 原文），
  chips 渲染文件名+大小；发送后 chips 清空（clearAttached 实证）；**@file: 前缀注入语义铁证**=
  模型逐字引用附件原文「S7-f upload acceptance test file. / xiaoduo fileId injection check.」回表。
- ⚠️ **上传中禁发降级记档**：内网 19.5MB 仅 1.65s 窗口，screencap 链路 ~1s 时延四抓未中瞬态；
  逻辑层 FunctionKeyLogic 9 测+critic 变异钉死，chip「上传中…」与禁发键为同 Composable 状态渲染
  （其余四态均真机实证）。
- ⏳ 震动：无自动断言路径，等设备窗口用户手感确认（与 AEC 真声同批喊人）。
- 观察项（不返工，记 notes）：按住中 TTS 播放时按下说话 → `barge-in cancel` 终轮但未接续 LISTENING
  （startListening 非 IDLE 提前 return），用户需松手再按一次；语义可争议，暂留。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest;adb logcat 实时落盘+PetLogger 原文(上文引用);adb shell input swipe/screencap 真机手势链
verify_claim: BUILD SUCCESSFUL;tests=345 skipped=0 failures=0 errors=0(critic 两轮各自亲跑);松手物化日志 manual release/cancel listening/utterance->ASR->LLM->SPEAKING 全链原文在档;上传 fileId 三条原文在档
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt]
```

## 2026-08-04（S7-f 输入框重构+上传：critic 两轮后不逊，代码侧收口）

### S7-f 交付与评审（H 组：两层输入框 + #2 按住反馈修复 + 文件上传，含停止档增补）

- **两层结构**：InputBar 迁出 `ui/screens/InputBar.kt`——InputArea（语音=56dp 满宽「按住说话」触摸区 /
  文字=TextField）+ FunctionBar（上传键 + 多功能键）；text 上提 ChatScreen 级 rememberSaveable；
  待发文件 chips 行压 InputArea 上方。
- **#2 按住反馈**：`awaitEachGesture` 全程跟踪 + Vibrator 30ms + 声波纹 24 柱（仅 holding 期
  `LaunchedEffect(holding)` 订阅 captureBlocks+EnergyGate.rmsOf）+「松手发送 上移取消」+ 上滑 80dp 取消
  （滑回实时恢复，松手才物化分流 cancelCurrentTurn/stopListening；系统打断走 CancellationException——
  compose-ui 1.6 无 PointerEventType.Cancel，代码注释留痕）。
- **多功能键五态**（用户 08-04 增补停止档）：FunctionKeyLogic 纯逻辑真值表——busy||voiceActive→STOP
  （vm.cancelCurrentTurn）＞uploadInFlight→DISABLED＞有内容→SEND＞文字→TO_VOICE＞语音→TO_KEYBOARD；
  UI 层 400ms 防抖根治 SEND 连点第二击落 STOP 误取消（代价已声明）；voiceActive 改 StateFlow 驱动重组。
- **上传**：`AguiClient.uploadFile`（multipart `file`+`fileClass=agent----{agentId}----{threadId}`，
  Bearer+agentId，401 透明重登 1 次）；FileMeta（信封解析+localId 本地键+分隔符 sanitize）；
  VM _files StateFlow（20MB 拒/UPLOADING 禁发/切会话清空）；发送时 content 前缀
  `@file:{fileId}?{fileName}` `|`+`\n` 注入（平台 web bundle 取证契约，待查项①早关）。
- **critic 两轮**：R1 打回 2P2——① resend() 漏 uploadInFlight 禁发守卫（重发会连坐取消在途上传）；
  ② resetFiles() 成功清场连坐清空 STREAMING 中后传的 DONE 文件。修复=`FileListRules` 纯逻辑同源：
  uploadInFlight() 为 send/resend 唯一判定点（编译层不可分叉）+ attachedDone 发送一刻快照 +
  clearAttached 只清本轮挂载。R2 **不逊**：345 测亲跑账平、契约逐字零漂移、变异 4 处红绿齐
  （含 clearAttached 整清回退→双红）、泄密 grep 0 命中、异步守卫四问全过、白名单过。
  测试 314→345（+31：FunctionKeyLogic 9/FileMeta 7/Upload 7/NetEndpoints 2/FileListRules 6）。
- P3 留档：clearAttached 读-改-写毫秒级竞态窗（与 R1 resetFiles 同暴露，非本票回归）。
- **待设备窗口**：真机验收（声波纹/震动/上滑取消/上传真实文件/停止键）+盲测组 2（vs DeepSeek 双层+小布）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug :app:testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=345 skipped=0 failures=0 errors=0(XML 求和亲算,critic 两轮各自独立亲跑核实);变异 4 处红绿齐(FunctionKeyLogic 优先级/buildContent sanitize/multipart fileClass/clearAttached 整清回退)
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/InputBar.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, app/src/main/java/com/xiaoduo/deskpet/chat/FunctionKeyLogic.kt, app/src/main/java/com/xiaoduo/deskpet/chat/FileListRules.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/agui/FileMeta.kt, app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/main/java/com/xiaoduo/deskpet/agui/AguiClientFactory.kt, app/src/main/java/com/xiaoduo/deskpet/NetConfig.kt, app/src/main/AndroidManifest.xml, app/src/test/java/com/xiaoduo/deskpet/chat/FunctionKeyLogicTest.kt, app/src/test/java/com/xiaoduo/deskpet/chat/FileListRulesTest.kt, app/src/test/java/com/xiaoduo/deskpet/agui/FileMetaTest.kt, app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientUploadTest.kt, app/src/test/java/com/xiaoduo/deskpet/NetEndpointsTest.kt]
```

## 2026-08-04（S7-e 语音行为收口：critic 三轮不逊 + 真机打断双路径 + AEC spike 半程）

### S7-e 交付与评审（#9 唤醒「我在」+ #7 阶段1 键盘打断 + AEC spike 区）

- **「我在」（wozai）**：平台 TTS 端点合成预录 `assets/wozai.wav`（24kHz/mono/Int16 零重采样，
  MD5 e8708e0a…）；`VoiceSessionManager.startListening(playWakeAck=true)` 仅 KWS 唤醒路径播
  （按住说话不播，手指即反馈）；时序 IDLE 守卫→kwsPaused→SPEAKING→wozaiJob 播完→VAD start→LISTENING；
  `WakeAck` 接口注入（JVM 可测 fake，文件缺失降级不播）。
- **键盘打断（阶段1）**：`VoicePlayer.stop()`（stopped 持久首写即抛）+ `VSM.cancelCurrentTurn()`
  四态分支（TurnFlags：utteranceForwarded + terminalFired CAS 防双终态，sealed 上提先于 stop）+
  `ChatViewModel.cancelCurrentTurn()`（`voiceActive || vsm.state != IDLE` 门控，turnEpoch 迟到写主防线，
  OkHttp 不响应协程取消）+ `ChatTurnLogic.markCancelledDone`（部分 DONE 不可重发）+ InputBar `onFirstChar`。
- **critic 三轮**：R1 2 P1（synthesize→play 窗口幽灵音频→二查+先注册 player+isCancelled 复查；
  voiceActive 门控死路径→拍板改 `voiceActive || vsm.state != IDLE`）；R2 3 项（新测 flaky→awaitState；
  playAndReveal 注册窗→注册后补枪查 sealed；Error 分支幽灵泡→`if(!turn.sealed)` 三行包封）；
  R3+P3（cancel 写序与补枪读序不齐→sealed 上提先于 stop）→ **不逊**。测试 274→314（skipped=0）。
- **真机打断双路径取证**：语音轮流式中首字符→TTS 即停+气泡即收+部分 DONE 无重发；
  「我在」播放期首字符打断亦生效（R1 门控死路径修复后覆盖）。
- **AEC spike 半程**（详 BLOCKED 当前阻塞）：`AecSpikeSection`（FLAG_DEBUGGABLE 门控，200ms 循环强制
  kwsPaused=false 旁路）；误触发 1/3 轮——长回复 TTS ~2.5min 全程 KWS decode **0 命中**；
  目标 ≥3 轮 ≥5min + 检出率 10 次（需用户真声，PC 播放 ~2/9 不可靠），等设备窗口续做。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=314 skipped=0 failures=0 errors=0(XML 求和亲算);真机 logcat 时序:首字符→TTS stop→气泡收→部分 DONE
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoicePlayer.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt, app/src/main/java/com/xiaoduo/deskpet/voice/ReplyCarrier.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatTurnLogic.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/FullscreenVoiceScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/AecSpikeSection.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/navigation/AppNavHost.kt, app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/main/java/com/xiaoduo/deskpet/util/PrefContract.kt, app/src/main/java/com/xiaoduo/deskpet/util/Prefs.kt, app/src/main/assets/wozai.wav, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerWakeAckTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerBargeInTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/ReplyCarrierTest.kt, app/src/test/java/com/xiaoduo/deskpet/chat/ChatTurnLogicTest.kt, app/src/test/java/com/xiaoduo/deskpet/util/PrefContractTest.kt]
```

## 2026-08-04（S7-d 盲测 R4 胜，全收口；S7-e 真机自发验证 wozai）

### S7-d 盲测组 1 第 4 轮：21:19 胜，全项不逊 ✅

- **用户两板**：放行第 4 轮 + 白底改判（#0D0D0D→#FAFAFA，近白非纯白——白身宠物留明度差+与主界面浅色同族）。
- **R4 返工**：caption 主语化（正在听你说…/小朵正在思考…/小朵正在回答…）+聆听态 mic 图标+迷你声波柱
  （复用光环 RMS 流）+底部红色挂断钮（#E53935 对齐千问强退出信号，Call 图标旋转 135°）+白底全配色
  （深灰文字/浅灰 X 容器/光环青加深 #19B8D8/IDLE 环提亮）。打断/静音钮未做（cancelCurrentTurn 属 S7-e，不跨票依赖）。
- **R4 封盲 21:19 胜**：状态可辨 4:3（mic 图标+省略号+主语文案+圆环=三重冗余胜参照物单行灰字）、
  精致度 5:4（角色资产工艺胜单一渐变球）、氛围/层级/操作可见性三项 4:4 平。**全项 ≥4 无 ≤2=不逊达成**。
  四轮轨迹 14:20/19:23/19:20/21:19。critic nit 留档：菜单 hint 与红挂断钮位置过近易误读（不返工）。
- **真机新证**：聆听帧（mic 图标+正在听你说）/回答帧（喇叭图标+小朵正在回答+抬手姿势）入 .scratch/。
- **KWS 间歇性疑问关闭**：PC 播放唤醒率 ~2/9 判为声学问题（声压/方向），非产品退化——用户真声两次秒醒
  （08-04 08:49/09:15 各 2/2）；logcat 实时监控 vs PetLogger 文件滞后数分钟的教训复用。

### S7-e 真机自发验证（builder 施工中，wozai 链路提前实证）

- 唤醒→`state -> SPEAKING (wake-ack wozai)`→1.1s→`state -> LISTENING (wozai done, VAD start)`——
  「我在」预录播放+顺序式挂载真机生效；同轮 ASR「你能做什么？」→ SPEAKING(first audio) 全链路通。

### S7-d 盲测组 1 三轮全记录（连败 3/3，协议换票，最小修法已定位）

- **R1 14:20 负**：输状态可辨 2:4（静态图零状态线索）/操作可见性 1:5（整屏无退出路径）/层级 3:4。
  返工=四态 caption+X 关闭钮+「长按小朵打开菜单」hint（spec 语义澄清记 notes：「无文字默认」=无转写，
  caption 是状态反馈非转写）。
- **R2 19:23 负**：氛围 4:4 平/精致度 4:4 平守住，操作可见性救回 4；唯一硬伤=状态可辨 3:5
  （「角色立绘完全不随语音状态变化」）。返工=PhaseHalo 状态光环（LISTENING 订阅 captureBlocks 逐块
  EnergyGate.rmsOf 经 EMA 平滑驱动环呼吸/PROCESSING 旋转虚线/SPEAKING 波浪律动）+X 钮青色统一。
- **R3 19:20 负（A 险胜）**：氛围 5 胜/精致度 4 胜/层级 4:4 平；状态可辨 3:5（caption「正在说话…」
  主语歧义+静态图听/说缺第二线索）、操作可见性 3:5（会话中控件无可见按钮）。
- **裁决**：同项（状态可辨）连败 3 次 → 按协议记 BLOCKED 换票，第 4 轮最小修法已定位（caption 主语化+
  聆听态 mic 图标+底部可见控制条），等用户拍板。三轮返工产物保留不回滚（均为实质改进）。

## 2026-08-03（S7-d 全屏语音模式：施工→代码 critic 不逊→真机连续取证全过）

### S7-d 施工与代码评审

- 设计定案落地：PetOverlayService.setWindowsHidden（GONE/VISIBLE，pref/服务/round 三不动）；
  VSM stateFlow（transition 唯一写入，statusListener 单播不动）；ReplyCarrier.kt 纯逻辑 CarrierBubbleSink
  （现读 carrier 委托分发，VoiceStateRouter 保持只管麦块——spec 偏差记 notes）；FullscreenBubbleSink 恒
  no-op（TTS 经 VoicePlayer 不经 Sink）；新路由 FULLSCREEN_VOICE（DisposableEffect 进出严格逆序配对）；
  转写=chatVm.messages 最后 assistant（单一事实源，baseline 防旧回复——critic 顺手项）；设置「全屏转写」
  开关默认关+PrefContract 键。单测 +5（ReplyCarrierTest 4+PrefContractTest 1），299 绿。
- **代码 critic 打回 1 条 P1**：PetView 480dp>屏宽 360dp 必裁切（critic PIL 亲测帧内容 bbox ~80%）→
  返工 min(440dp, screenWidth) 等比钳制（真机落 360dp 宽 1.875×，spec「~3×」在 360dp 屏物理不可达，
  KDoc 留痕）→ 复评不逊。变异双红（carrier 恒 overlay / 键名改字）。

### S7-d 真机连续取证（全过）

- 右上角 mic 进全屏（日志 enter fullscreen + windows hidden -> true；截图黑底+小朵居中零裁切）；
  悬浮窗隐藏实证（全屏截图无悬浮宠物，dumpsys 双窗在列 GONE）。
- **唤醒对话两轮**：① PC 喇叭放 TTS 合成「小朵小朵」+提问（reply 162 chars，firstToken=2039ms）——
  PC 播放触发成功率约 2/6，间歇性原因未查明（怀疑声压/方向，非 KWS 退化：用户真声即稳）；② **用户真声轮**：
  唤醒→提问→reply 650 chars firstToken=2798ms，用户确认「有反应，有音频输出和动作」；TTS 逐句 played 日志齐。
- speaking 举手姿势截图（无文字，转写默认关）；长按菜单三项截图；退出全屏日志
  `menu: exit fullscreen → carrier=OVERLAY, windows restored`+桌面宠物复现截图。
- 插曲：用户顺带点「隐藏悬浮窗」菜单项——pref false+stopService+宠物消失，第三项行为同获实证（事后已恢复开关）。
- **PetLogger 文件延迟坑**：日志文件刷盘滞后数分钟，实时监控必须 logcat（文件尾 19:13 时 logcat 已到 19:21）——
  两次误判「唤醒失灵」实为文件滞后+陈旧行误读，教训记档。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=299 skipped=0 failures=0 errors=0(critic 亲跑+编排层复跑);真机:进全屏/隐悬浮/唤醒两轮/退出恢复 日志+截图全链;盲测三轮 14:20/19:23/19:20 负(BLOCKED 记档)
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/ReplyCarrier.kt(新), voice/VoiceSessionManager.kt, voice/VoiceClientFactory.kt, PetOverlayService.kt, ui/screens/FullscreenVoiceScreen.kt(新), ui/navigation/AppNavHost.kt, ui/screens/ChatScreen.kt, ui/screens/SettingsScreen.kt, util/PrefContract.kt, util/Prefs.kt, app/src/test/(ReplyCarrierTest 新/PrefContractTest), implementation-notes.html, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md, BLOCKED.md]
```

## 2026-08-03（S7 切片族开工，波次 1 三票并行收口）

### 任务 0 + 编排层并行线（午后）

- **基线复核**：BUILD SUCCESSFUL，awk 亲算 tests=274 skipped=0 failures=0 errors=0；真机 de61cd8 在线；凭据可读。
- **待查项②关闭**（rename 端点）：桌面仓 `agui_sessions.py`+知识包双源——`POST {threadBaseUrl}/renameThread` body `{threadId,threadName}`，无需实测猜路径。
- **待查项①关闭**（fileId 接入）：平台 web bundle 静态取证（`.scratch/fileid/`）——文件走消息 content
  **文本前缀注入**：`@file:{fileId}?{fileName}` 多条 `|` 连接 + `\n` + 用户文本；上传 `POST /common/file/upload`
  FormData=`file`+`fileClass`(`agent----{agentId}----{threadId}`)；web 限 txt/md/pdf/doc/docx/xls/xlsx/ofd ≤10MB ≤5 个。
  渲染侧 g0()/v0() 正则同契。记 implementation-notes。
- **wozai.wav 合成**：平台 TTS `/audio/tts/synthesize`（cosyvoice3/default/1.0，与 App 参数一致）「我在」→
  48044B WAV，RIFF 校验 PCM/mono/24000Hz/16bit，存 `.scratch/wozai/wozai.wav`（S7-e 备用）。
- **对标参照物**：千问语音通话模式截图（`com.aliyun.tongyi` 右上角电话图标进入，全屏大圆球+三钮）+
  DeepSeek 按住说话录音态（松手发送上滑取消+蓝色声波纹，已裁切只留录音 UI 区）入 `.scratch/refs/`；
  **ChatGPT 对标被用户改判出局**。隐私纪律：千问聊天页/DeepSeek 私人对话截图即删。

### S7-ab 会话重命名+废弃聊天记录页+顶栏瘦身（builder 交付 → critic 不逊 → 真机取证全过）

- a/b 合并一票（探索发现同改 ChatScreen.kt 地界冲突）；删 ChatHistoryScreen/路由/入口；clearHistory 死路径连删；
  titleManual 字段+saveMessages 跳过 deriveTitle；renameSession 不动 updatedAt（排序不跳）；renameThreadForSession
  对称 delete（非幂等、不清映射、401 透明重登独立额度）；长按弹操作菜单（重命名/删除/取消）；PetStatusBar 删
  「小朵」Text、头像 32dp、去 vertical padding（m3 IconButton 48dp 热区撑栏高正中 ~48dp）、右上角 Spacer 预留位。
- 单测 +12（AguiClientTest 6/ChatStoreTest 4/SessionIndexCodecTest 2）；变异双红（threadName→name / 删 titleManual 守卫）。
- **真机取证**：聊天记录入口消失 ✅；长按弹菜单 ✅；重命名对话框预填+空白禁用 ✅；
  **改名后发新消息 title 不被覆盖**（S7RenameProbe 发「S7 title probe」后仍原名，updatedAt 刷新「刚刚」）✅；
  平台 rename 日志双路径：失败（WiFi 断开期 `platform thread rename failed (local rename kept, no retry)`——
  fire-and-forget 语义实证）+ 成功（`renameThread POST .../renameThread → renameThread ok → platform thread renamed`）✅；
  顶栏瘦身截图（hamburger+32dp 头像+「待命」，无「小朵」文字）✅。

### S7-c 气泡升级（builder 交付 → critic 打回 1 条 → 返工过 → 真机像素取证全过）

- textSize 13f/MAX_HEIGHT_DP 100/BUBBLE_WIN_WIDTH_DP 260；BubbleTextStrip 剥离→渲染：**纯 Kotlin StyledText 模型**
  （StyledText/Span/Type{BOLD,ITALIC,CODE}+prefix/dropStart/plus，JVM 可测）+ BubbleView toSpannable 转换；
  holdback 逐行未动；单测 18→26 等价改写+新增，变异双红（BOLD→ITALIC / 删 prefix 钳位）。
- **critic 打回 1 条**：typeTick 内同步 scrollToBottom 用旧 layout，末字换行时静止态不贴底 → 返工 `post { scrollToBottom() }` 过。
- **真机取证**：显示层 1040px=260dp×4（dumpsys 日志）✅；超长文本自动滚到底（末尾行可见）✅；
  粗体/斜体/代码三 span 同屏渲染 ✅；表格管道符剥除不渲染 ✅。

### S7-g 图标（builder 交付 → critic 不逊 → 真机截图过）

- idle 首帧（pet_states.json row0 frames6，裁 (0,0,192,208)，bbox (19,5,173,203)）→ adaptive icon
  （前景 60% 安全区 + 底色 #171717）+ 五密度 legacy PNG + Manifest android:icon；aapt 坐实
  `icon='res/mipmap-anydpi-v26/ic_launcher.xml'`；真机桌面第二屏图标清晰可辨 ✅（证据裁图标行，壁纸原图即删）。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=294 skipped=0 failures=0 errors=0(基线 274+20,编排层亲跑);真机:rename 成功/失败双路径日志+title 不覆盖截图+气泡 span/滚动/宽度取证+图标截图
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, chat/ChatSession.kt, chat/ChatStore.kt, chat/ChatViewModel.kt, chat/SessionIndexCodec.kt, ui/navigation/AppNavHost.kt, ui/screens/ChatScreen.kt, ui/screens/SessionDrawer.kt, ui/screens/ChatHistoryScreen.kt(删), ui/overlay/BubbleView.kt, ui/overlay/BubbleTextStrip.kt, PetOverlayService.kt, AndroidManifest.xml, app/src/main/res/mipmap-*/, app/src/main/res/values/ic_launcher_background.xml, app/src/test/(AguiClientTest/ChatStoreTest/SessionIndexCodecTest/BubbleTextStripTest), implementation-notes.html, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md]
```

## 2026-08-03（真机验收波次,手机已连）

### 深色走查 + 抽屉双失灵修复 + wm 矩阵（上午）

- **深色走查五页齐**:chat/drawer/settings/history/permission 全合格(返回键在、黑底对比度合格),
  取证 docs/verification/s6-dark-{chat,drawer,settings,history,permission}.png。
- **抽屉返回键+scrim 双失灵(真机实测,backlog #9 项转正)**:抽屉开着 back→退桌面(mCurrentFocus
  实证);scrim x=1200/1350 两点位均不关。builder javap 反编译 material3 1.2.1 双根因:
  ① scrim onClose 首指令 gesturesEnabled 短路(false 连带 scrim 失效);② NavigationDrawerKt
  无 BackHandler(1.3 才有 predictive back)。修法=gesturesEnabled=isOpen+BackHandler(isOpen)。
  **critic 不逊**(独立 javap 复核双根因/依赖版本实锤/270 全真跑/白名单零越界)。
  **真机四断言全过**:① back 关抽屉留前台;② scrim tap 关;③ 关态 back 退 App(既有)不变;
  ④ 设置页 back 回聊天页不变。
- **wm size 矩阵**:1080x2400 聊天页+宠物完整(取证);**720x1600 宠物完全出屏不可见**
  (交互窗 (540,1414) 617x668,精灵图像窗内居中 x∈[648,1048] 出屏;气泡层 (231,1086) 1234x312
  大半出屏)——P2 位置不重 clamp,builder 修复中(BLOCKED #13)。wm size reset 已复 1440x3168。
- **隐私纪律**:矩阵首拍误捕控制中心(含短信验证码)+桌面私人便签,5 张即删不入库;
  悬浮窗证据只保留宠物区域裁切(1080/720);教训=wm 变更后前台可能是设置页/ shade 可能展开,
  截图前必须 mCurrentFocus+uiautomator 双确认目标页。
- **编排事故留痕**:矩阵第一轮「chat」两张实为设置页(App 恢复上次栈顶),像素抽查 [0,71,0]
  未识别——教训=页面身份断言要用 uiautomator 文本锚(按住说话),不能只像素采样。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=270 skipped=0 failures=0 errors=0(critic 全真跑亲验);真机四断言:back 关抽屉留前台 mCurrentFocus=MainActivity/scrim tap 1350 关(uiautomator 新建会话 1→0)/关态 back→Launcher(既有)/设置 back→聊天页(按住说话锚在)
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, docs/verification/s6-dark-settings.png, docs/verification/s6-dark-history.png, docs/verification/s6-dark-permission.png, docs/verification/s6-wm-1080-chat.png, docs/verification/s6-wm-1080-overlay.png, docs/verification/s6-wm-720-chat.png, docs/verification/s6-wm-720-overlay-lost.png, implementation-notes.html, PROGRESS.md, DEV_LOG.md, BLOCKED.md]
```

### 悬浮窗重 clamp 两轮定案（午前,BACKLOG #13 关闭）

- **一轮**:onConfigurationChanged 重 clamp(窗留 margin 把手,刻意不落盘)——critic 代码层不逊,
  但编排层真机复验打回:720 屏窗被拉回 (528,1328) 而精灵本体(窗内居中 x∈[653,1018])仍整体
  出屏,精灵区像素均值 43=纯壁纸色,「把手可见」与「精灵可见」语义 gap 实证。
- **二轮**:clamp 语义改「窗体中心(=精灵中心)每轴钳在屏内 [0,screen]」,(540,1414)→(412,1266)
  (数学与真机 dumpsys 一致);edgeMargin 参数退役(仅留默认摆放位);OverlayClampTest 14 条等价改写。
- **critic 复评不逊**:274 全真跑;6 条旧测逐条对账无一弱化(位置精确值全保留);变异
  coerceIn 上界 +100 → 恰 3 条中心语义测红;数学独立重算 412/1266 吻合;语义边界(贴缘半身
  出屏=中心语义固有)与我判定一致接受。
- **真机复验过**:720 屏精灵半身可见可辨(像素均值 43→126.5,裁切取证 s6-wm-720-overlay-fixed.png);
  气泡 X 同步钳回;wm size reset 复 1440x3168。
- 副作用留档(接受):拖拽自由度收紧为最多半身出屏,与「陪伴宠物不应可被藏没」同向。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=274 skipped=0 failures=0 errors=0(critic 两轮全真跑);变异 coerceIn(0,screen+100)→3 红;真机 720 dumpsys (412,1186) 与数学 (412,1266) 同语义(x 精确吻合,y 差=app 区高),精灵区像素 43→126.5
files_touched: [app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/main/java/com/xiaoduo/deskpet/pet/OverlayClamp.kt, app/src/test/java/com/xiaoduo/deskpet/pet/OverlayClampTest.kt, docs/verification/s6-wm-720-overlay-fixed.png, implementation-notes.html, PROGRESS.md, DEV_LOG.md, BLOCKED.md]
```

### 语音流式上屏真机验收（午,用户两轮实测）

- 用户确认:「我的话立刻出现,小朵回复逐字往外蹦」——与日志时序钉死一致:
  10:05:54.58 user 上屏(ASR→AG-UI 前)→ 10:05:57.84 首 token(3254ms)→ 10:05:58.36
  ChatScreen 流式渲染 → 10:05:59.36 才播音(文字领先音频 ~2s 起,全程流式)。
  第二轮「你能做什么？」同型;唤醒→识别→回复→多轮→30s 超时回 IDLE 全链路日志齐。
- 观察项:firstToken 3254ms 略超 3s 预算(平台侧抖动,attempts=1 无重试),挂观察不立案。
- 顺带实证:install -r 后服务自愈日志原文(PetOverlay+VoiceFg 双服务 pref=true 自动回来)。

### ce-compound ×3 + 周度知识审计 + 文档收口（午后）

- **沉淀 3 篇**(headless lightweight,校验器双过):material3-121-modal-drawer-scrim-back /
  overlay-clamp-content-visibility / wm-size-matrix-privacy-and-page-identity。
  校验器抓到两处引用瑕疵已修(路径缩写不可解析/反引号引用运行时锁目录)。
- **B6 firstToken 采样**:用户拍板跳过(PC 采样脚本 .scratch/firsttoken_sample.py 就绪备跑:
  登录信封 token 顶层键/createThreadId/线协议已调通)。
- **W31 周度知识审计**(docs/skill-improvement-report-2026-W31.md):42 条 notes 五高频模式——
  P1 拍板翻案 ≥6(对照:参照物 adb 实证先行的决策零翻案)/P2 异步守卫窗 ≥7(票据结构性缺口)/
  P3 代码层过≠真机语义过 ×3/P4 builder 自报失实 ×3/P5 编排层事故 ×3(已沉淀)。
  协议改进落 CLAUDE.md:异步票「守卫四问」前置/critic 标注验收类型/自报数字必附证据;
  权威文档层补 docs/solutions/ 索引行。审计计时已 mark。
- **文档收口**:CONSTRUCTION 状态表 S4✅/S5✅/S6🔶(仅气泡组 D 用户挂起);
  PROGRESS 接手指引整段重写(274 基线/剩余轻量项/纪律指针);机构记忆索引 5 篇。

### 仓库文件审计与文档层翻新（午后,审计子智能体+编排裁决)

- **审计**(Explore 只读,205 文件):A 建议删除=**0**(md5 全量比对零重复,候选均涉证据链不猜判);
  B 建议更新=14;C 存疑=27 逐项裁决。
- **B 档 14 项全落地**:基线 17→274 四处(CLAUDE/AGENTS/CONSTRUCTION 纪律/BLOCKED 隐含);
  AGENTS.md 漂移同步(层级第 5 项 solutions+收口状态);CONSTRUCTION 34 复选框全勾+S3 职责翻转
  史实修订+S6-D 挂起标注;BLOCKED B2 敞口关闭改写(仅余主观项)+#4/#6 划销+#7 空号注记+
  xd-voice.png 死引用更正+#14 量产前裁决挂账;README 重写(助手化文档地图);MASTER-PLAN/spec/
  handbook README/03/04/08/09 全部加「M1 时点快照,现行以 CLAUDE.md 层级为准」封存注记;
  plan 002 R2 补用户翻案修订记录(多会话进范围)。
- **C 档裁决**:22 张无引用取证图=全留(各切片唯一视觉证据,删则证据链断);sherpa AAR 48.8MB
  =接受现状(构建可复现优先);DebugBubbleReceiver=有 FLAG_DEBUGGABLE 门,量产前产品确认(挂 #14);
  .scratch 10 个 M1 知识文件=维持跟踪(docs 仍引用);handoff/07/11=留原位(M1 档案);
  决策总账三处重复=以 CLAUDE.md 层级为现行,handbook 03 注记封存。
- **PROGRESS.md 大手术判不做**:M1 历史段有溯源价值,单文件保留,顶部已是现行态。

## 2026-07-31（续会话,手机归还,backlog 清尾）

### S3 收尾与 S4 P3 收口（上午）
- **基线复核**:BUILD SUCCESSFUL,123 单测全绿 skipped=0;真机 de61cd8 在线。
- **B3 气泡垂直跟随定案=非 bug 关闭**:气泡显示→拖拽 (864,648)→(864,1688)→三连截图气泡同步跟移;
  同拍日志 drag start/end+persisted;updateBubbleWindow 的 runCatching 带 onFailure 日志(不静默)。
  critic-F2 原观察=并行串台期截图时序错位。证据 docs/verification/b3-1/2/3-*.png。
- **S3 默认外观截图**:s3-pet-default.png;dumpsys 双窗(交互层 384x416 无 alpha/显示层 768x312 alpha=0.8
  @(864,648) scale=0.5)。**悬浮窗盲测 v4:遮挡克制 4:2 胜手机精灵 → 形态组全项不逊**。
- **S4 P3×3 builder 交付 + critic 不逊**:AudioCapture 丢新块→DROP_OLDEST(与 VAD pre-roll removeFirst 同策,
  critic 亲验对账表);currentCoroutineIsActive 竞态改 Structured Concurrency(AudioCapturePumpTest 4 测,
  变异 DROP_LATEST/反转 isActive 双红坐实);回环留档 loopback-2026-07-31/(TTS 合成→ASR 逐字命中,
  wav 165164B=165120PCM+44头,python wave 亲验 24kHz/16bit/3.44s)。voice 域 48 测绿。
- **观察项**:设置页悬浮窗开关 UI 态=运行态,与 pref 分叉,重装后服务不自愈须手动重拨(notes 未决)。
- 编排记录:MSYS2_ARG_CONV_EXCL='*' 解决 uiautomator 设备路径被 Git Bash 改写(原「引号包裹」口径失效)。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --tests "com.xiaoduo.deskpet.voice.*" --console=plain
verify_claim: BUILD SUCCESSFUL;voice 域 tests=48 skipped=0 failures=0(XML 求和,含 AudioCapturePumpTest 4 新测);critic-S4 于 09:04 --rerun-tasks 全量 127 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt, app/src/test/java/com/xiaoduo/deskpet/voice/AudioCapturePumpTest.kt, docs/verification/loopback-2026-07-31/(5 文件), docs/verification/b3-1-before-drag.png, docs/verification/b3-2-after-drag.png, docs/verification/b3-3-settled.png, docs/verification/s3-pet-default.png, BLOCKED.md, PROGRESS.md, CONSTRUCTION.md, implementation-notes.html, DEV_LOG.md]
```

### S2 返工 6 项收口 + 导航改判（午前）
- **S2 返工 critic 不逊**(10/10):messageId 复用 MockWebServer 取请求体坐实(两次同 id);流式清洗根因
  =旧实现 cleaned 当累加基丢半截标签,改 ChatTurnLogic 原文缓冲(逐字符测试每步断言无裸 `<`);
  表格流式安全(分隔行未到齐不猜);PetStatusBar 双竞态真修;ChatTurnLogic 纯逻辑 13 测。
  变异三杀:len=1 守卫删→3 红;重发恒造新 id→3 红;表格守卫删→1 红。还原全绿。
- **导航结构改判(用户拍板)**:顶栏垃圾桶+齿轮撤下,改左上 hamburger→ModalNavigationDrawer
  (gesturesEnabled=false 防返回手势互抢);「会话列表」分期落地为「聊天记录」页(契约 R2 不做多会话,
  入口向前兼容)。UX critic 出 P0 清单+6 验收点。
- 深色模式抽查通过(新表格/代码块深色对比无 bug,s2-rework-dark.png)。

### 导航抽屉式改造收口（上午）
- **builder 交付 + critic 不逊**(9/9):ModalNavigationDrawer(gesturesEnabled=false 防返回手势互抢)+
  左上 hamburger;垃圾桶+齿轮撤下;新建 ChatHistoryScreen(概览:条数/首条/最近时间,空态禁用,
  原 AlertDialog 逐字迁入);共享 VM=AppNavHost 层 Activity 级 viewModel() 显式下传两屏
  (navigation-compose 目的地内默认 owner 是 NavBackStackEntry 会分裂——票据风险提示的正解);
  顶栏瘦身 ~64dp 删 Divider;nit 两件(末尾换行+wireId roundtrip 测)。变异:wireId null 守卫删→红→还原。
- **真机交互 6 断言全过**(编排层亲验):hamburger 在左上且无垃圾桶/齿轮;抽屉两入口+设置沉底;
  左缘右滑不呼出(系统返回手势优先);聊天记录页概览真实数据;清空 dialog 文案逐字;BACK 回聊天页回栈健康;
  抽屉→设置可达。证据 docs/screenshots/nav-1/3/4/5。
- **事故留痕**:真机操作期间用户同时在用微信,一次 tap 序列落入微信会话页——截屏误入隐私内容已即删
  (未外泄未入库),微信侧无发送/修改(截图核对其输入框无草稿)。教训:用户持机期间真机操作须先打招呼。
- critic P3 遗留:两屏 `vm = viewModel()` 默认值是静默分裂引信,下票删默认值强制显式传参。

### 气泡所有权上移收口 + 终版盲测 + 多会话改判（午）
- **backlog #4 critic 不逊**(8/8):BubbleOwnership 纯逻辑(CHAT/VOICE/DEBUG,claim 单调 round,
  judgePublish/judgeHide 三态)+服务接线(hideBubbleLater 到点复验)+三调用方适配。
  变异双红:删 round 校验→4 红(互杀场景正中);FORCE_HIDE 优先级反转→1 红。
  nit:报数 165≠实测 168;onDestroy 未清 postDelayed;posted runnable 不复验 round。
  留口:语音泡无收泡触发(旧靠文字轮 12s 兼任,重构后消失)→S5 排泡策略产品定。
- **终版盲测两轮**:输入区 v2 胜 19:14(两项 3)→v3 输 17:18(键盘图标弱/配重/内容裁切,连败 2);
  聊天页 v2 输 15:16(富文本 2/5 硬伤)→返工后 v3 胜 14:11(对手空态 1/5)但仍五项 3/2 未不逊。
  返工已实证:表格整词不折、代码块横滑全文可见;渐变淡出可读性仍差。
- **多会话改判(用户拍板,推翻契约 R2)**:侧边栏=搜索会话+新建会话+会话列表+设置;
  标题=首条用户消息截断;搜索=标题+消息全文;删除=长按+二次确认。
  侧边栏收窄露 scrim(现 360dp=满屏像翻页);hamburger 美化;状态栏融合;返回键保证。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=168 skipped=0 failures=0(XML 求和亲算);critic-#4 另以 --rerun-tasks 全真跑 168 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/PetOverlayService.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceClientFactory.kt, app/src/main/java/com/xiaoduo/deskpet/ui/overlay/DebugBubbleReceiver.kt, app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleOwnership.kt, app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleOwnershipTest.kt, implementation-notes.html, DEV_LOG.md]
```

### 富文本返工 critic 不逊（午后)
- #14 八项全过:displayUnits(阈值 0x2E80,西里尔/阿拉伯无误判)/colMaxUnits 类体内派生不参与 equals/
  列宽 38dp 重算吻合/代码块 softWrap=false+横滑+淡出条件原文核对;变异双红(CJK 计 1→3 红;打桩 8→2 红)。
- nit×2:注释「码点」实为 UTF-16 码元(措辞);PetThumb dimen 变死引用(Theme.kt,在 N2 builder 地界,
  顺延到波次 2 后编排层自清)。
- commit 拆分:MarkdownRenderer+测试先走;ChatScreen.kt 与 N5 输入区返工缠在同文件,N5 落地后合并提交。

### N2 状态栏融合收口（午后)
- enableEdgeToEdge(SystemBarStyle.auto 跟随系统 uiMode,零新依赖);ChatScreen 自绘 PetStatusBar/InputBar
  不消化 insets→NavHost 层补 statusBars/navigationBars/ime padding(modifier 向下游 consume 不重复垫)。
- 真机取证:浅色页状态栏白底深图标无接缝;`cmd uimode night yes` 深色页黑底浅图标;顶栏不被压。
- D3 留痕:抽屉打开时顶部不延伸到状态栏区(垫层在安全区内),要抽屉全高需改 ChatScreen 内部,归边栏票。

### N5 输入区+聊天页返工收口（午后)
- pill 内键盘图标(48dp 热区)+mic 居中配重;scrollToBottom 根修(冷启动落绝对底部,长消息尾部完整);
  流式跟随改 96dp 阈值(用户上翻不拽回);顶栏对齐复核无需改(克制)。
- **渐变淡出根因(有 Compose 源码证据)**:LazyColumn item 以 maxHeight=Infinity 测量,wrap-content Box 透传
  无界约束,fillMaxHeight() 在无界下 no-op(FillNode.measure 实锤)→ 渐变是 48dp×0dp,v2 起从未渲染。
  修:BoxScope.matchParentSize() 拿兄弟节点实测有界尺寸,不依赖运行时状态。
  真机像素复核:中带 247 → 右缘带 232 渐暗,暗纱可见;裁图亲看成立。
- 附带发现入 backlog:斜体 `*…*` 未渲染(最小集遗留,S6 前评估)。

### N4 多会话数据模型收口 + 盲测双组相对尺收口（傍晚)
- **critic-N4 不逊**(10/10):迁移保真(现行 codec 输出当旧文件喂迁移逐条比对)/threadId 键控路由
  (MockWebServer 5 请求序列断言+一次性提升物理不可重复)/删除回落三态/busy 守卫主线程同线程无窗口/
  搜索中文 ignoreCase 无操作正确;变异双红(删旧文件行/强制默认 key)。
- **盲测尺子改判(用户拍板)**:相对尺=逐项不输参照物即不逊(绝对每项≥4 在状态反馈等项参照物自身
  只 3 分,继续磨=过度设计)。输入区 16:14 胜小布、聊天页 16:15 胜 ChatGPT,逐项 1 胜 4 平 0 负 → 双组收口。
- **3 个真 bug 立项**(下票):渐变可辨度(12% 实测渲染但人眼不可辨)/斜体泄漏/表格第 2 行灰字
  (原文 `🏛️ **业务合规指导**` 标记正常,待查渲染)。
- N4 留口:deleteSession 不接平台 /deleteThread(孤儿 thread)待产品确认;测试遗留会话标题
  含 %20(who%20are%20you,测试路径产物非产品 bug)。

### 3-bug 修复收口（傍晚)
- 斜体:单 * 配对(粗体优先,未闭合字面值),4 新测;真机「注:如需…」斜体正确。
- 渐变可辨度:alpha 0.12→0.2 + › chevron 双保险;真机可见。
- 表格灰行根因反转×2:不是表格渲染(解析层取证测绿)、不是遮罩高度不跟随——而是「长消息场景首项恒深裁
  →遮罩恒满 24dp 照洗停靠行」语义漏洞。最终选型:整体撤除顶部遮罩(ChatGPT 即硬切,相对尺不输;
  裁行真 bug 已由 scrollToBottom 根治)。真机像素:行 2 darkPx 0→175 真黑。
- 教训:渐隐遮罩类「观感优化」要过「停靠内容是否被误伤」关;两次返工都是编排层像素扫描钉出来的。

### S5 KWS 常开收口（傍晚)
- 交付:KwsEngine(sherpa 签名 javap 实测,threshold 0.5/threads 2/16k 直喂/回调只推队列 64 块满丢最旧)/
  EnergyGate(p95×2.5 钳 [0.003,0.05],桌面 0.015 回代)/VoiceForegroundService(mic 类型+常驻通知+暂停动作+
  START_STICKY,唯一拉起=设置页显式开关)/校准入口(5s 采 RMS→建议阈值→写入)/语音泡收泡(回 IDLE 才 12s)。
- critic 一审打回 2 条全修:**P1 麦泄漏竞态**(init 线程×onDestroy,服务死麦照采→destroyed 标志+同锁互斥,
  时序推演注释;STICKY 新实例天然复位)+**P2 错误泡不收**(两错误路径补 onBubbleShown 置位)。
- 复评不逊(锁粒度亲核/置位顺序/243 复跑);一审已过 9 项:sherpa javap 复验、对账表零漂移
  (keywords.txt 逐字符)、门控数学、合规无自启、变异双红。
- P3 留口:双 init 线程引擎覆盖泄漏(触发面被 UI 开关挡住,铁壁修法挂 backlog #8)。
- 真机验证项挂 B2 ⑥-⑩(唤醒 10 次/AEC/sticky/通知指示点/校准实采)。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=243 skipped=0 failures=0(XML 求和亲算);critic 两轮 --rerun-tasks 全真跑 243 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/(KwsEngine/EnergyGate/BubbleHidePolicy/VoiceSessionManager/VoiceClientFactory).kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/main/AndroidManifest.xml, app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt, app/src/main/java/com/xiaoduo/deskpet/util/(Prefs/PrefContract).kt, app/src/test/java/com/xiaoduo/deskpet/voice/(EnergyGateTest/BubbleHidePolicyTest).kt, BLOCKED.md, PROGRESS.md, implementation-notes.html, DEV_LOG.md]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=207 skipped=0 failures=0;真机像素复核 行2 darkPx 0→175(真黑)/chevron 可见/斜体正确
files_touched: [app/src/main/java/com/xiaoduo/deskpet/chat/MarkdownRenderer.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, app/src/test/java/com/xiaoduo/deskpet/chat/MarkdownRendererTest.kt]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=202 skipped=0 failures=0(XML 求和亲算);critic-N4 以 --rerun-tasks 全真跑 202 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/chat/(ChatSession/SessionIndexCodec/SessionRules/SessionSearch/ChatStore/ChatViewModel).kt, app/src/main/java/com/xiaoduo/deskpet/agui/(AguiClient/AguiClientFactory).kt, app/src/test 多会话相关 5 新测试类+AguiClientTest, implementation-notes.html, DEV_LOG.md, PROGRESS.md, CONSTRUCTION.md]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=202 skipped=0 failures=0(builder 跑);渐变真机像素复核 中带247→右缘232 渐暗成立
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, DEV_LOG.md, BLOCKED.md, PROGRESS.md]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=168 skipped=0 failures=0(builder 跑);真机双模式截图编排层亲验(白底深图标/黑底浅图标)
files_touched: [app/src/main/java/com/xiaoduo/deskpet/MainActivity.kt, app/src/main/java/com/xiaoduo/deskpet/ui/navigation/AppNavHost.kt, DEV_LOG.md]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=168 skipped=0 failures=0(XML 求和亲算);critic-#14 以 --rerun-tasks 全真跑 168 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/chat/MarkdownRenderer.kt, app/src/test/java/com/xiaoduo/deskpet/chat/MarkdownRendererTest.kt, DEV_LOG.md]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=159 skipped=0 failures=0(XML 求和亲算);critic 另以 --rerun-tasks 全真跑 159 绿
files_touched: [app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatHistoryScreen.kt, app/src/main/java/com/xiaoduo/deskpet/ui/navigation/AppNavHost.kt, app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientTest.kt, app/src/test/java/com/xiaoduo/deskpet/chat/ChatHistoryCodecTest.kt, docs/screenshots/nav-1-topbar.png, docs/screenshots/nav-3-drawer.png, docs/screenshots/nav-4-history.png, docs/screenshots/nav-5-clear-dialog.png]
```

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL(43 tasks 全真跑);tests=158 skipped=0 failures=0 errors=0(awk 求和亲算)
files_touched: [app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt, app/src/main/java/com/xiaoduo/deskpet/chat/(ChatMessage/ChatHistoryCodec/ChatViewModel/MarkdownRenderer/ThinkCleaner/ChatTurnLogic).kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt, app/src/test/java/com/xiaoduo/deskpet/agui/AguiClientTest.kt, app/src/test/java/com/xiaoduo/deskpet/chat/(MarkdownRendererTest/ThinkCleanerTest/ChatTurnLogicTest).kt, docs/screenshots/s2-rework-dark.png, BLOCKED.md, PROGRESS.md, CONSTRUCTION.md, implementation-notes.html, DEV_LOG.md]
```

### 气泡剥离 / 服务自愈 / 语音入史 / 语音流式上屏（晚-夜,补录)

- **气泡 markdown 剥离**(commit 0878e3c):真机取证语音/文字泡裸显 `**`、`|`、反引号 → BubbleTextStrip
  流式剥离(粗斜体含 2*3 数学星保护/行内码/表格管→空格/分隔行跳/标题引用规则剥除/空白压缩);
  PetOverlayService 两入口接 Session.safe-prefix 单调喂入,未闭合 `**`/反引号无限 hold、单 `*` 24 字封顶释放。
- **P1 服务自愈**(commit 9b2a991):用户报「悬浮窗没了」(实为我排障期 force-stop 后无自愈) →
  MainActivity.onCreate 按 pref 自愈(不走 App 类,避后台 FGS 限制);设置页开关绑 pref 非运行态,
  toggle-off 补写 pref=false。日志 `self-heal: PetOverlayService start requested` 取证。
- **P1 语音轮消息入会话历史**(commit 88d2b1e):用户拍板语音/文字同一会话统一视图;TranscriptSink v1
  (Success 原子上屏)。同期三 P1 分诊:「没声音」=我降级测试窗污染端口(已还原,非 bug);
  「语音不上屏」=真空缺,本票修。
- **语音流式上屏**(本 commit):用户问「为什么消息都在音频播完才出现」→ TranscriptSink v2 流式契约:
  onUserUtterance 移到 ASR strip 后 AG-UI 调用前(user 立即上屏)、onAssistantDelta 挂 chat delta 回调
  首行(回复逐字实时)、Done/Error 收尾;VM 侧 rawBuffer 自累加+ensureAssistant 零 delta 补占位。
- critic 三轮:**round1** 打回=语音流式轮对会话操作零守卫(切会话/清空→applyDelta 静默吞 delta)→
  voiceActive 独立标志(busy=SENDING||STREAMING||voiceActive,不复用 phase 防状态条/精灵双写)+
  clearHistory 补 busy 守卫+吞 delta warn;**round2** 打回=异常路径窗未闭(终态回调在
  ttsConsumer.await() 后,reveal 无 try/catch,RuntimeException 逃逸→voiceActive 永久悬挂→文字输入变砖)→
  onUserUtterance 后整段 try/catch(CancellationException 透传/其余补 onAssistantError 后 rethrow,fail loud)
  +terminalFired 防双终态;**round3** 余项全过(261 绿亲跑/时序推演/变异实验/泄密 grep 0 命中),
  仅 implementation-notes 异常路径记录缺失,编排层补录(设计决策+未决+权衡三条)收口。
- 未决挂 backlog:catch rethrow 前 sentences channel 未 close(改造前既有,每异常轮泄漏一挂起协程)。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=261 skipped=0 failures=0 errors=0(critic 第 3 轮全真跑亲验);变异实验(删 catch 内 onAssistantError 调用)证实异常路径无测试钉住属第 2 轮判「可免」范围,sha1 还原一致
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerTranscriptTest.kt, implementation-notes.html, PROGRESS.md, DEV_LOG.md]
```

### voice 域 P3 尾巴收口（backlog #8+#10,夜)

- **OnceGate 门闩一式两用**(internal 纯逻辑,AudioCapture.kt 尾部):
  #8a KWS 双 init 引擎泄漏——initStarted 门闩源头堵双载(收尾 engine 复查只堵泄漏不堵双载
  ~5.4MB onnx 模型;init 失败 release 放行重试,sticky 重投可重入);
  #8b AudioCapture.start() isRunning 双调竞态——同门闩把「检查+占位」收敛成原子操作,
  release 双置读循环 finally+stop()(双放幂等)。
- **#10 异常路径 channel 泄漏**:sentences Channel 提升 try 外,两 catch 分支补 close(幂等)。
- **TDD 红绿齐**:红阶段 PipelineCloseTest 按剧本红(挂起者=ttsConsumer DeferredCoroutine);
  OnceGateTest 3 条(32 线程栅栏齐放硬断言唯一赢家/持有期后来者全败/release 重开双放幂等)。
- **critic 不逊**:265 测 --rerun-tasks 全真跑;变异双红(tryAcquire 恒 true→3 红;删 catch 内
  close→PipelineCloseTest 红,sha1 还原一致);#8a 门闩四交错推演(成功/失败/onDestroy 途中/
  重复 onStartCommand)无永久关闭路径;AguiClient 全文 4 处 catch 均 IOException 坐实注入点;
  参数零漂移;白名单零越界;泄密 grep 0 命中。
- **critic 观察项挂 backlog #11**:OnceGate 过期 release 理论窗(stop→start2 后 job1 finally 错放
  start2 的闩;新旧失败形态不同且严格收窄,collectJob 守卫再压缩);铁壁修法=epoch 所有权令牌。
  builder「同形态」措辞失准被 critic 当场纠正(实体「未变差」成立)。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=265 skipped=0 failures=0 errors=0(critic 全真跑亲验,261+4);变异双红(OnceGate.tryAcquire 恒 true→OnceGateTest 3 红;删 catch 内 sentences.close()→PipelineCloseTest 红),sha1 还原一致
files_touched: [app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt, app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt, app/src/test/java/com/xiaoduo/deskpet/voice/OnceGateTest.kt, app/src/test/java/com/xiaoduo/deskpet/voice/VoiceSessionManagerPipelineCloseTest.kt, implementation-notes.html, PROGRESS.md, DEV_LOG.md, BLOCKED.md]
```

### OnceGate epoch 所有权令牌（backlog #11,夜)

- **令牌式门闩**:tryAcquire(): Long? 递增令牌(holder=++nextToken,绝不复用),release(token)
  仅匹配才放,过期 release 自动失效(原「双放幂等」语义天然保留)。
- **铁壁关键**:AudioCapture 读循环 finally 用闭包局部令牌(不读 gateToken 字段),彻底摆脱
  字段可见性/时序窗;@Volatile 字段仅供 stop() 兜底「协程未及开跑即取消」。
  VoiceForegroundService init 失败 release(initToken) 放行重试,sticky 语义不变。
- **critic 不逊**:266 测 --rerun-tasks 全真跑;变异「release 不看令牌→红」坐实 stale-token 测试
  非摆设;时序矩阵(stop→start2→job1 晚到/双放/start→start)推演全钉住;
  字段陈值靠令牌守卫兜底(critic 推演发现的设计自带免疫)。
- **留档两条**(BLOCKED #11):① 残余 P3 理论窗(start 内 tryAcquire→赋值之间并发 stop,
  布尔版逐字既有非回归,无死锁无泄漏,状态门收窄,暂不修);② AudioCapture 闭包令牌接线
  无 JVM 测试看护(Android import),仅靠 critic 评审亲验——变异 4b(破坏闭包捕获)无测试红,
  事实留档。
- **编排自证**:critic 报的 BLOCKED.md「越界旗」=编排层自写(登记 backlog #12 三项),非 builder 越界。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
verify_claim: BUILD SUCCESSFUL;tests=266 skipped=0 failures=0 errors=0(critic 全真跑亲验,265+1);变异 release 不看令牌→stale-token 测试红(OnceGateTest.kt:76),sha1 还原一致
files_touched: [app/src/main/java/com/xiaoduo/deskpet/voice/AudioCapture.kt, app/src/main/java/com/xiaoduo/deskpet/VoiceForegroundService.kt, app/src/test/java/com/xiaoduo/deskpet/voice/OnceGateTest.kt, implementation-notes.html, PROGRESS.md, DEV_LOG.md, BLOCKED.md]
```

### P3 零星三项收口（backlog #12,夜)——代码类 backlog 全清

- **① VM 兜底 catch**:ChatViewModel 全文件仅两处 launch——runChat 补 try/catch(复位对齐 Error
  分支:CancellationException 透传,其余 PetLogger.e+markError+persist+phase=ERROR);
  deleteSession 平台 thread 删除 fire-and-forget 只记日志不复位(本地删除已完成,无状态可复位)。
  编排层落地 critic nit:**DONE 守卫**(markDone 后善后步骤异常不把已完成回复改判 ERROR,
  防重发重复投递;先例 kwsPaused P2 同款编排层一行修)。① 无 JVM 测试经 critic 核实成立
  (无 Robolectric/VM 依赖静态工厂,引入 seam=变相重构;markError 本体已被 ChatTurnLogicTest 覆盖)。
- **② BubbleTextStrip 极端输入**:空 code span(`` `` ``)现状=findHoldback 永久 hold+strip 吐字面
  反引号两边分裂 → 统一剥除(CommonMark 空 span 规范行为);backtick-freeze 加 BACKTICK_MAX=48
  封顶(对照 ITALIC_MAX=24,超限丢标记留内容放行,前缀稳定不变式不破)。
  TDD 红绿:修前 3 新测红(空 span 冻结/strip 剥空 span/超封顶放行),修后 18 全过。
- **③ 会话列表零会话占位**「暂无会话」复用 DrawerHint;critic git show HEAD 证实「无匹配会话」
  占位为既有,builder 未抢功;onSurfaceVariant 浅 4.8:1 过 AA。
- **critic 不逊**:270 测全真跑;变异 48→3 红坐实封顶被钉住(48→4 全绿=边界测试恰坐阈值,
  critic 精确推演);markError 无状态守卫的双写窗推演→DONE 守卫落地;泄密 grep 0 命中。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL;tests=270 skipped=0 failures=0 errors=0(XML 求和亲算,266+4;critic --rerun-tasks 全真跑 270 绿);变异 BACKTICK_MAX 48→3→红,sha1 还原一致
files_touched: [app/src/main/java/com/xiaoduo/deskpet/chat/ChatViewModel.kt, app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt, app/src/main/java/com/xiaoduo/deskpet/ui/screens/SessionDrawer.kt, app/src/test/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStripTest.kt, implementation-notes.html, PROGRESS.md, DEV_LOG.md, BLOCKED.md]
```

## 2026-07-30（收口 + 规划日）

### 助手化 S0-S6 施工（傍晚起，Claude Code 编排会话）——晚场续与停线

- **S3 悬浮窗两轮返工**：critic 盲测 v1 输（半透明幽灵感+菜单近全屏宽）→ 查实 Android 12+ 强制
  NOT_TOUCHABLE 窗 alpha≤0.8（logcat 原文）→ 用户拍板双窗口职责翻转（PetView 回交互层）→
  二轮返工（菜单 184dp/气泡 X clamp+描边/append 对称 hide/onDestroy 不写偏好/DEBUG 门/默认 scale 0.5）
  代码全绿；盲测 v3 小朵 15:10 胜（遮挡 3/5 已返工）。P1 气泡垂直跟随定案挂 BLOCKED B3。
- **S2 聊天页 critic 过**：20 chat 测真断言、双变异坐实、真机 5 轮对话+杀进程恢复+泄密 0 命中；
  强制跟进 6 项立项（messageId 复用/表格渲染/输入区精致度等，builder 已派未动工遇停线）。
- **S4 语音引擎 critic 打回轻量**：P2 kwsPaused 漏复位（S5 即死级，编排层已一行修复+123 全绿）；
  P3×3（丢块注释/协程竞态/回环留档）挂 backlog；真机全链路等有麦环境（BLOCKED B2）。
- **ce-compound 沉淀**（lightweight ×2）：docs/solutions/ui-bugs/android12-not-touchable-overlay-alpha.md +
  workflow-issues/parallel-builders-single-device-mutex.md（校验器 PYTHONUTF8=1 过；CONCEPTS.md 本轮不建，defer Full run）。
- **编排层事故留痕**：盲测 v2 裁切裁空（宠物没进图）作废一轮，教训=裁切后亲看再派；
  agent ID 错位误派一次（秒刹停无损），教训=以通知摘要核对身份；S2 返工 builder 停线于开工回执（零编辑）。
- **停线快照**：123 单测全绿 skipped=0；在施 S2/S3/S4 状态与下会话第一波见 PROGRESS.md 接手指引。

```evidence
verify_cmd: export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH" && ./gradlew.bat assembleDebug testDebugUnitTest --console=plain
verify_claim: BUILD SUCCESSFUL；test-results XML 合计 tests=123 skipped=0 failures=0（awk 求和亲算）
files_touched: [app/ 全部新增与修改, docs/solutions/ 2 篇, PROGRESS.md, CONSTRUCTION.md, DEV_LOG.md, BLOCKED.md, implementation-notes.html, CLAUDE.md, docs/spec/assistant-app.spec.md]
```

### M1 收口（上午）
- 偏差 A-D 全部裁决：A 窗口改 208（真机 dumpsys 645x698 @scale0.84 取证）；B/C 随 07-30 grilling 交互集追认关闭；D 补日志滚动 7 天 + 设置页导出（FileProvider 分享，ChooserActivity 取证）
- U1 契约测试立起：pet_states.json 进 assets（SHA256 与上游一致）+ 5 条比对全绿
- 单测 17/17；commit `757904f`（代码）+ `a0ec63f`（文档）

### 助手化 brainstorm（下午，9 轮逐题拍板）
- KD9 定位修订：App = 对话主界面（推翻 KD3 控制台定位）
- KD10 仅 AG-UI（Ollama 彻底取消）；KD11 Compose 混排（修订 KTD1）
- KD12 KWS 进本轮 + 长按菜单复活（推翻上午的偏差 B 追认，同日两翻，记录：用户的信息在下午补全了）
- KD13 reveal 补选进；KD14 终点线 = 功能 + 对标不输；KD15 手势集；KD16 半双工
- 参照物留档：手机精灵 = 主题商店桌面精灵（双窗口实证：226x339 可触 + 452x452 NOT_TOUCHABLE 显示层）；小布/ChatGPT 主界面截图进 docs/research/
- 产出：AGENTS.md / plan 002 / spec v1 / CONSTRUCTION.md / 四件套初始化 / 执行提示词（leader 流程）

### 坑与备忘
- Git Bash 会改写 adb shell 参数里的 `/sdcard/...` 路径 → 设备路径必须放引号内；读到的 ui.xml 可能是昨日残留
- 手机精灵在前台时 mCurrentFocus 仍是 launcher（overlay 应用特征）→ 识别 overlay 应用要 dumpsys window windows 查 ty=APPLICATION_OVERLAY，不能只看焦点

### 执行器改判（傍晚）
- 执行环境从 pi 改为 Claude Code：恢复子智能体 fan out + critic 盲测严评的原教旨质量循环；编排协议写进 CLAUDE.md，pi 降级版留 AGENTS.md；执行提示词重写（本会话交付）
