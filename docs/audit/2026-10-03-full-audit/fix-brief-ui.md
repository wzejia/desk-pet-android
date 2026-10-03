# 修复简报 · ui 包(P2 共 8 条)

> 来源:全量审计 2026-10-03(unified-findings.md)。字段原样取自 p2-p3-detail.json,行号未改动。
> 逐条:先在源码验证问题属实(不属实→记「证伪跳过」+理由),属实→先写失败测试(TDD)→最小修复→测试转绿。禁改本简报地界外的文件。SEC-3 若在你的地界:注意 prefs 迁移要兼容板端存量数据。

---

## ROB-3  [ocr]
- 标题: VoiceIndicatorView 线程亲和与可见性
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/overlay/VoiceIndicatorView.kt
- 问题: setMode/startLoop/stopLoop 调 Choreographer.getInstance()（无 Looper 线程即抛），loopRunning/displayLevel 等无同步；onLevel() 文档允许任意线程（VAD 分支）。
- 影响: 非主线程驱动显隐时崩溃或动画状态损坏。
- 修法建议: 生命周期收敛主线程（或主 Looper Handler post）并文档化线程契约。
- ocr 索引: [75]
---

## ROB-8  [ocr]
- 标题: OverlayMenu PopupWindow BadTokenException
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/overlay/OverlayMenu.kt
- 问题: 以 WindowManager 悬浮 anchor showAsDropDown，无 isAttachedToWindow/token 检查、无错误处理（07-rescue 批次）。
- 影响: teardown 与长按竞态/多窗口切换令 token 失效时崩溃进程。
- 修法建议: show 前 guard anchor.isAttachedToWindow 并 try/catch 包裹。
- ocr 索引: [455]
---

## ROB-9  [ocr]
- 标题: ChatScreen 上传协程未捕获异常
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt
- 问题: vm.uploadFile 在 rememberCoroutineScope 的 IO 协程内无 try/catch（无 CoroutineExceptionHandler）；中途导航离开协程被静默取消。
- 影响: 上传网络/IO 异常直达未捕获处理器崩溃应用；文件 chip 遗留指向未上传文件。
- 修法建议: try/catch（CancellationException rethrow）+ toast；迁移 viewModelScope。
- ocr 索引: [333]
---

## ROB-10  [ocr]
- 标题: SessionDrawer 搜索 producer 未捕获 IO 异常
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/screens/SessionDrawer.kt
- 问题: onSearch 在 withContext(IO) 无 try/catch，异常逸出 produceState producer。
- 影响: 任一次搜索 IO 错误撕毁整个组合（UI 崩溃）。
- 修法建议: catch 非 Cancellation 异常降级（log+空结果），CancellationException rethrow。
- ocr 索引: [352]
---

## ROB-11  [ocr]
- 标题: 权限页 ACTION_MANAGE_OVERLAY_PERMISSION 无守卫
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/screens/PermissionScreen.kt
- 问题: startActivity 无守卫，部分 OEM 精简系统无该 action 解析 activity。
- 影响: 点「授权悬浮窗」抛 ActivityNotFoundException 崩溃（机型相关）。
- 修法建议: try/catch（PetLogger.e）+ 回退 ACTION_APPLICATION_DETAILS_SETTINGS。
- ocr 索引: [339]
---

## ROB-12  [ocr]
- 标题: 附件读取先 readBytes 后限 20MB
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt
- 问题: picker 为 */*，readBytes() 在 20MB 限制之前整读入内存；OOM 被 runCatching 吞成「读取文件失败」toast（同样掩盖 SecurityException）。
- 影响: 选数百 MB 文件 → 内存尖峰（低端板可被系统杀）或误导性失败。
- 修法建议: 游标先取 OpenableColumns.SIZE 预拒，或流式带硬上限读取。
- ocr 索引: [332]
---

## ROB-13  [ocr]
- 标题: SettingsScreen 三处 scope.launch 无异常兜底
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/screens/SettingsScreen.kt
- 问题: agent 列表/对话轮/采麦校准三处 scope.launch 无兜底，任一抛出未映射为 Error 的异常沿 rememberCoroutineScope 传播。
- 影响: 应用崩溃且 loading/running 卡 true、按钮永久禁用。
- 修法建议: 每处 try/catch（CancellationException rethrow，其余转 Error）+ finally 复位标志。
- ocr 索引: [80]
---

## FUNC-5  [人工代码 F-BT-1]
- 标题: BubbleTextStrip 无流尾 flush，未闭合标记永久吞尾
- 文件:行号: app/src/main/java/com/xiaoduo/deskpet/ui/overlay/BubbleTextStrip.kt
- 问题: Session 只有 feed 无 flush；`**` 分支无 hold 上限（`*`≤24 字、反引号≤48 字）；调用方只取 feed().text（佐证 PetOverlayService.kt:534,563）。
- 影响: LLM 回复以未闭合标记收尾时标记后内容永不上屏（「回答缺后半句」）。反例 feed("注意**这里是重点") 只显示「注意」。
- 修法建议: Session.flush()（全量 raw 走一次性 render 规则差分补发）在 RUN_FINISHED/收泡前调用；最小改动为 `**` 分支加 hold 上限。
- ocr 索引: []