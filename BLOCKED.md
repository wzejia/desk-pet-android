# BLOCKED.md — 阻塞与待确认事项

## 当前阻塞

- **盲测组 2（输入框 v2 vs DeepSeek 双层+小布）三连败关账**（2026-08-05 凌晨，协议规则执行）：
  R1 负（两段式/点柱波形/文案三处病灶）→ v3 单容器返工 → R2 2 胜 2 负（hold 对 4 胜 1 负逆转、
  停止键对全胜，但逐对未全项不逊）→ R3 收容器空洞（120→104dp）→ R3 1 胜 3 负（停止键对第三连胜，
  其余三对仍负）。三连败按规则关账，不再返工。**败因分层留档**：①hold 对 R3 丢分主因是抓帧期
  PC 音量漂移致波形帧呆板（R2 同代码同波形拿过 4 胜，材料不可比，非设计回退）；②空闲两对的稳定
  负因=对手功能 chips（深度思考/智能搜索/AI 生图/全部技能）提供信息密度与三级层级，本产品无此
  能力，视觉返工填不平——**若产品日后加能力入口 chips，空闲态可重赛**；③稳定胜点留档：生成中
  停止键态三连胜 DeepSeek（纯黑方块独占焦点 vs 对手同蓝色系争焦+chips 截断硬伤）；hold 态 R2
  曾胜（反馈贴触点+保留会话上下文+波形振幅真实）。代码侧 R3 已 critic 不逊并装机。
  **后注（2026-08-05 S8-1 用户拍板）：输入框视觉回滚两层+恢复横线——用户视觉拍板优先于盲测口径，
  单容器方向撤回，「能力 chips 重赛」条件作废；P1/P3 手势功能修复与功能层参数保留（S8-1 票明示）。**
- **AEC spike ✅ 关账（2026-08-21 S12 收口）**：spike 双合格（误触发 0/4min 带喂流佐证 + 检出 8/10）
  → 用户拍板立项 → S12 唤醒词打断转正并真机验证 8/8。历史更正留档：08-04 的「零误触发」为空证据
  （旁路 no-op，见 docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md）。
  **转为观察票：AEC 偶发失效态**——2026-08-21 上午首轮打断零命中窗（回声持续过门 fed+600/gated+0，
  与常态相反；疑与刚重装首启有关，重启后消失未复现）。复发时先查该窗 fed/gated 形态定性；
  频发再评估 S12-2 状态感知门控（SPEAKING 期门控抬高）。
- **B2 敞口仅余主观项（随日常使用回收，非工单）**：②⑥ AEC 长观察、③播放听感与句间断音、
  ⑦ sticky 保活确认。（B2 原文存档见 git 历史 2026-07-31 版。）

## 已解除
- **S7-d 盲测组 1 三连败** ✅ **第 4 轮胜，定案关闭**（2026-08-04，用户拍板放行第 4 轮+白底改判）：
  连败三轮 14:20/19:23/19:20 后，返工=caption 主语化+聆听态 mic 图标+迷你声波+底部红色挂断钮+
  白底 #FAFAFA 全配色适配；第 4 轮封盲 **21:19 胜千问**（状态可辨 4:3、精致度 5:4，氛围/层级/操作
  三项 4:4 平，全项 ≥4 无 ≤2 = 不逊达成）。critic nit 留档：「长按小朵打开菜单」hint 与红挂断钮
  位置过近易误读为其说明（不返工，后续视觉票顺手）。
- B1 sherpa-onnx AAR 来源 ✅（用户批准 GitHub k2-fsa 官方 Release，已入库 app/libs + 模型入 assets + gradle 已接）。
- B3 气泡垂直跟随 ✅ **非 bug,定案关闭**(2026-07-31 真机取证):气泡显示→拖拽 (864,648)→(864,1688)→
  三连截图气泡同步跟移且贴宠物上方稳定;同拍日志 drag start/end + position persisted;
  代码复核 updateBubbleWindow 的 runCatching 带 onFailure 日志(不静默吞)。
  critic-F2 当时所见「气泡同 y 宠物不同位」判定为并行施工串台期的截图时序错位。
  证据:docs/verification/b3-1-before-drag.png / b3-2-after-drag.png / b3-3-settled.png。

## 待办 backlog（不阻塞，下会话优先做）
1. ~~S2 返工 6 项~~ ✅ 已过评(2026-07-31,critic 不逊 10/10,三项变异按剧本红):messageId 复用
   (MockWebServer 取请求体坐实)/表格+引用+行内代码/顶部淡出遮罩/输入区精致度/破折号+裸`<`根因修复
   (改原文缓冲)+PetStatusBar 双竞态/ChatTurnLogic 纯逻辑 13 测。全量 158 测绿。
   输入区盲测重测已随导航改造后完成(16:14 胜小布,相对尺收口);原 xd-voice.png 引用作废(文件未入库)。
   nit 遗留:AguiClientTest 末尾换行;wireId codec roundtrip 无直接单测(下票顺手)。
2. ~~S4 P3×3~~ ✅ 已过评(2026-07-31,critic 不逊):AudioCapture 改 DROP_OLDEST(与 VAD pre-roll
   removeFirst 同策)+竞态 Structured Concurrency 修复(4 新测,变异双红坐实)+回环留档
   docs/verification/loopback-2026-07-31/(TTS→ASR 逐字命中,5 产物齐)。
   遗留:critic-G 报的「语音原文/回复原句落盘日志的隐私口径」仍待产品确认;
   critic 补报 backlog:AudioCapture.start() 的 isRunning 检查与置位间有双调竞态窗口(既有,非本票引入)。
3. ~~S3 默认外观截图~~ ✅ 已取(2026-07-31):`docs/verification/s3-pet-default.png`
   (scale 0.5 + 右侧中上落位,dumpsys 双窗 384x416 交互层 + 768x312 显示层 alpha=0.8 取证)。
4. ~~气泡所有权上移 PetOverlayService~~ ✅ 已收口(2026-07-31,critic 不逊 8/8,服务级 round/owner
   令牌,judgePublish/judgeHide + FORCE_HIDE on show_bubble=false)。
5. ~~悬浮窗盲测 v4~~ ✅ 已胜(2026-07-31):遮挡克制 4:2 胜手机精灵(小朵零遮挡+落位留白区;
   对手压哔哩哔哩标签+侵入三图标可读区仅 2/5)。S3 悬浮窗形态组=全项不逊。
6. ~~Markdown 斜体(*单星号*)未渲染~~ ✅ 已修(2026-07-31,3-bug 票:斜体渲染+渐变+遮罩三修,
   真机像素复核钉死)。
7. (编号留空——原 #7 渐变遮罩项随 3-bug 票一并收口,见 #6)
8. ~~critic-S5 P3 备忘~~ ✅ 已修(2026-07-31,critic 不逊):KWS 双 init 用 OnceGate 门闩源头堵双载
   (initStarted 语义);AudioCapture.start() 双调竞态同门闩原子化(release 双置 finally+stop,幂等);
   OnceGateTest 3 测真并发钉死(变异 tryAcquire 恒 true→红)。
9. 真机遗留断言(等用户设备窗口):~~边栏 scrim 点关/返回键关抽屉~~ ✅ 实测双失灵→已修
   (2026-08-03,material3 1.2.1 双根因 javap 实锤:scrim 被 gesturesEnabled 短路+无 BackHandler;
   critic 不逊,真机四断言全过);气泡组盲测缺手机精灵气泡参照物(**用户 2026-08-03 拍板挂起,
   保持原样**;想做时移动手机精灵触发气泡再截);
   ~~语音流式上屏真机验「边说边上屏」~~ ✅ 用户两轮实测确认(2026-08-03,日志时序钉死)。
   S5 唤醒/AEC/sticky/指示点已转入顶部「仅余主观项」。
13. ~~悬浮窗位置不随显示尺寸重 clamp~~ ✅ 已修(2026-08-03,两轮):一轮「窗留 margin」被编排层
    真机打回(把手落透明区,精灵本体仍出屏,像素均值 43 实证);二轮改「窗体中心钳在屏内」,
    720 屏 (412,1266) 精灵半身可见(像素 126.5);critic 复评不逊(测试改写对账无弱化+变异 3 红)。
    副作用(接受):拖拽最多拖到半身出屏。
10. ~~VoiceSessionManager.runPipeline 异常路径 sentences channel 未 close~~ ✅ 已修(2026-07-31,
    critic 不逊):channel 提升 try 外+两 catch 分支补 close(幂等);PipelineCloseTest 以 ThrowingSink
    注入 chat 异常逃逸,断言 events=[user, error] 且 scope 无残留协程(变异删 close→红,
    挂起者正是 ttsConsumer DeferredCoroutine)。
11. ~~OnceGate 过期 release 理论窗~~ ✅ 已修(2026-07-31,critic 不逊):epoch 所有权令牌——
    tryAcquire(): Long? 递增令牌,release(token) 仅匹配才放;AudioCapture 读循环 finally 用闭包
    局部令牌(不读字段,彻底摆脱可见性窗);新增 stale-token 测试钉死(变异「release 不看令牌」→红)。
    残余 P3 理论窗(布尔版逐字既有,非回归,可达性低):start 内 tryAcquire 成功→gateToken 赋值/
    job 赋值之间并发 stop——后果仅「这次 stop 拦不住新读循环」或理论双读循环,job finally 自持
    令牌放闩,无死锁无泄漏;VoiceSessionManager 状态门收窄触发面,暂不修。
    另:AudioCapture 闭包令牌接线无 JVM 测试看护(Android import),仅靠 critic 评审亲验,留档。
12. ~~P3 零星三项~~ ✅ 已修(2026-07-31,critic 不逊,270 测绿 +4):
    ① ChatViewModel 两处 launch 补兜底 catch(runChat 复位对齐 Error 分支+DONE 守卫防双写——
    critic nit 编排层一行落地;deleteThread fire-and-forget 只记日志);② BubbleTextStrip 空 code span
    冻结修复(剥紧邻双反引号)+backtick-freeze 加 BACKTICK_MAX=48 封顶(对照 ITALIC_MAX=24 体例,
    变异 48→3 红坐实钉住);③ 会话列表零会话占位「暂无会话」(「无匹配会话」占位 git show HEAD
    证实为既有,builder 未抢功)。**代码类 backlog 全清。**
14. 量产前裁决(2026-08-03 文件审计挂账):DebugBubbleReceiver exported=true 调试入口——
    有 FLAG_DEBUGGABLE 门(release 包自闭),量产前需产品确认保留或移除;
    sherpa-onnx-1.13.4.aar 48.8MB 入 git(构建可复现优先,接受现状;将来可考虑 LFS);
    .scratch/ 下 10 个 M1 知识文件仍被 git 跟踪(gitignore 对已跟踪无效,README/MASTER-PLAN
    仍引用——维持跟踪,勿 git rm --cached)。
