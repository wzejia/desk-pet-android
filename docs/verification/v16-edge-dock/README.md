# v16-edge-dock — S10 悬浮窗贴边收纳 语义验收证据（2026-08-19）

> 原 v16 目录随 08-18 仓库删除事故灭失，本目录为重建续写（含 Case A/B/C/A2 补拍）。
> 票据：`CONSTRUCTION.md` S10 节；验收口径：dumpsys 窗口坐标逐值对账 + logcat 原文 + 截图像素断言。

## 几何常数（对账基准）

| 环境 | 屏幕 | 窗宽 winW | zone=winW/2 | dockedX(LEFT) | dockedX(RIGHT) | slideOutX(L/R) |
|---|---|---|---|---|---|---|
| 模拟器 emulator-5554 | 1080×2400 @420dpi | 252 | 126 | **-126** | **954** | 0 / 828 |
| 真机 de61cd8 | 1440×3168 @640dpi | 937（scale≈1.22） | 468 | **-468** | **972** | 0 / 503 |

贴边坐标 = `OverlayClamp.clamp` 不动点（窗中心恰在屏缘），持久化/自愈重启/尺寸重钳位零新机制。

## 模拟器 Case（emulator-5554）

| Case | 断言 | 结果 | 证据 |
|---|---|---|---|
| B 反例 | 屏中松手（中心 700）不吸附、零 `edge slide` 日志 | ✅ | caseB-*.txt/.png |
| A | 左缘松手 → 滑入 x=-126，日志 `dock LEFT: -83 -> -126` | ✅ | caseA-*.txt/.png |
| A2 | 右缘松手 → x=954，日志 `dock RIGHT: 933 -> 954` | ✅ | caseA2-*.txt/.png |
| C | 左贴单击 → 滑出 x=0（`undock LEFT`），非挥手 | ✅ | caseC-*.txt/.png |
| C2 | 右贴单击 → 滑出 x=828（`undock RIGHT`） | ✅ | caseC2-*.txt/.png |
| D | 左贴长按菜单：五项全左缘=0 钳回屏内；「打开 App」点按拉起 MainActivity；「打断/查看最近一轮」IDLE 置灰=门控正确 | ✅ | caseD-*.txt/.png/.uiautomator.xml |
| E | 左贴+右贴气泡（DebugBubbleReceiver 注入）：气泡本体完整在屏内可读（显示层容器可越缘，内容钳屏内） | ✅ | caseE-*.txt/.png ×2 |
| F | force-stop→自愈重启：`dual windows added ... at (954,617)` 逐像素恢复贴边 | ✅ | caseF-*.txt/.png |
| G | `wm size 720x1600` 收窄：`reclamped to (594,617)`=dockedX(RIGHT,252,720)，半身贴缘不消失（历史 P2 回归）；reset 后几何推导自然脱贴边态 | ✅ | caseG-*.txt/.png ×2 |

## 真机 Case（de61cd8，device.lock 纪律）

| Case | 断言 | 结果 | 证据 |
|---|---|---|---|
| RM-1 | 右贴（装新包即恢复 x=972，已是贴边态）单击 → 滑出 503 | ✅ | real-rm1-*.txt |
| RM-2 | 右缘拖放 → 吸附 954→972（`dock RIGHT`） | ✅ | real-rm2-*.txt |
| RM-3 | 左缘拖放 → 吸附 -207→-468（`dock LEFT`） | ✅ | real-rm3-*.txt/.png |
| RM-4 | 左贴单击 → 滑出 0 | ✅ | real-rm4-*.txt |
| RM-5 | force-stop→自愈恢复 -468（`dual windows added ... at (-468,1278)`） | ✅ | real-rm5-*.txt/.png |
| RM-6 | 贴边态「小朵小朵」唤醒一轮：wake→wozai→VAD→ASR「今天的天气怎么样？」→LLM firstToken 456ms→TTS SPEAKING→气泡→多轮 LISTENING→超时 IDLE；全程 x=-468 不变 | ✅ | real-rm6-*.txt |

## 附带修复（验收揭露的恢复遗留缺口）

RM-6 首轮暴露 `secrets/cloud.properties`（gitignored）未随恢复树回迁 → 新构建云链全断。
从 device-base.apk 反编译 BuildConfig 取证恢复 → 重建（APK sha1=070d453022f1b82f4a4d6c3412a2963cd869c7c2）→ 双端重装 → RM-6 二轮实证恢复。详见 `apk-preservation-2026-08-19.md` 与 DEV_LOG 08-19。

## 红线亲证

- `git diff --stat HEAD -- app/src/main/java/com/xiaoduo/deskpet/voice/` 为空（voice/** 零 diff）。
- 基线复跑：`./gradlew.bat testDebugUnitTest --rerun-tasks` → 51 suites / **438 tests** / skipped=0 / failures=0 / errors=0。
- 真机截图只入库左缘裁切条（隐私纪律）；临时全屏原图已删。
