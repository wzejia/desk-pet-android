# 国产 ROM 权限引导清单调研

日期：2026-07-28 ｜ 对应 ticket：`.scratch/xiaoduo-android/issues/03-research-rom-permissions.md`
调研方式：官方 Android 文档（developer.android.com）+ 社区通行做法汇总。⚠️ **OEM 私有跳转 Intent 均非公开 API，属易碎约定**——标注「真机验证项」的条目须在 ticket 08 确定的目标机型上逐一实测。

## 0. 权限全景（小朵全量需要 6 项）

| 权限 | 系统层级 | 用途 | 里程碑 |
|---|---|---|---|
| 悬浮窗 SYSTEM_ALERT_WINDOW | 特殊权限（引导页跳转） | overlay 渲染宠物 | M1 |
| 通知 POST_NOTIFICATIONS | 运行时（Android 13+） | 前台服务常驻通知可见 | M3 |
| 麦克风 RECORD_AUDIO | 运行时 | KWS/VAD 采集 | M3 |
| 电池优化豁免 | 特殊权限（引导页跳转） | 保活 | M4 |
| 厂商自启动/后台运行 | ROM 私有（无公开 API） | 保活 | M4 |
| MIUI「后台弹出界面」 | MIUI 私有 | 后台时 overlay/Activity 弹出 | M4（仅小米系） |

## 1. 悬浮窗（M1 必须）

- **标准路径全 ROM 通用**：`Settings.canDrawOverlays(context)` 检测；跳转
  `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))`。
- **Android 12+ 不受信任触摸限制**：遮挡其他窗口的 overlay 若 alpha > 0.8 且尺寸超出触摸区域，系统会拦触摸——知识包 §8 已定方案「宠物大小窗口 + 拖拽时切 flag」，设计已规避。
- 真机验证项：部分 ROM（如旧 MIUI）把悬浮窗入口藏在「应用管理→权限管理→显示悬浮窗」，标准 Intent 落点偏一级页面，引导文案需提示用户手动路径。

## 2. 前台服务 + 麦克风（M3 合规核心）

- **Android 14 (API 34) 强制**：manifest 声明 `android:foregroundServiceType="microphone"` + 权限 `FOREGROUND_SERVICE_MICROPHONE`；`startForeground()` 需传对应 type。缺任一直接 `ForegroundServiceTypeException`/`SecurityException` 崩溃。
- **启动时机**：Android 12+ 禁止后台启动 FGS；麦克风类 FGS 还要求 RECORD_AUDIO 已授予且 App 处于 while-in-use——**VoiceService 必须由用户可见动作（打开 App/点悬浮窗/Quick Settings Tile）拉起**，不能指望开机自启拿麦。
- **麦克风指示点**：Android 12+ 系统强制显示（状态栏绿点 + 隐私面板），无法隐藏、无需代码——引导页文案要**预先解释**这个绿点是小朵在听唤醒词，否则用户会当成异常卸载。
- **常驻通知**：microphone FGS 通知不可移除（划掉即停服务）；Android 13+ 需先拿 POST_NOTIFICATIONS。通知文案建议透明说明「小朵正在待命」。
- 硬件 AEC：外放场景回声误触发 VAD（知识包 #35）——Android 采集走 `MediaRecorder.AudioSource.VOICE_COMMUNICATION` 可启用硬件 AEC/NS，M3 优先试；退回 `MIC` + 软件方案为备选。

## 3. 电池优化豁免（M4）

- 检测：`PowerManager.isIgnoringBatteryOptimizations(packageName)`。
- 跳转：`Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))`（manifest 需声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）；部分 ROM 该 Intent 无效时回退 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页让用户手找）。
- 真机验证项：MIUI 对豁免后的「省电策略：无限制」是另一层（应用信息→省电策略），豁免 ≠ MIUI 无限制，两层都要引导。

## 4. 厂商自启动 / 后台运行（M4，全 ROM 碎片化重灾区）

无公开 API，社区通行 Intent 跳转（全部 try/catch，失败回退应用详情页 `ACTION_APPLICATION_DETAILS_SETTINGS`）：

| ROM | 自启动跳转（社区做法，真机验证项） |
|---|---|
| 小米 MIUI/HyperOS | `component: com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` |
| OPPO ColorOS | `com.coloros.safecenter/.startupapp.StartupAppListActivity`（新版可能 `com.oplus.safecenter`） |
| vivo OriginOS | `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`；iQOO 同 |
| 华为 EMUI/HarmonyOS | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| 荣耀 | `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| 三星 | 无自启动概念；引导「电池→后台使用限制」白名单 |

- **MIUI「后台弹出界面」**（Display pop-up windows while running in background）：跳转 `action: "miui.intent.action.APP_PERM_EDITOR"`，`extra_pkgname = packageName`，落到权限编辑页由用户手开。检测无 API，只能引导后让用户回 App 自查。
- 知识包 §8 结论不变：**国产 ROM 杀后台无 100% 技术解**——以上引导做全是必要非充分，M4 需真机矩阵实测。

## 5. 业界参考与引导页设计建议

- Shimeji/Live2DViewerEX 通行模式：**首次启动分步引导页**（每屏一个权限：图示 + 「去开启」按钮 + 返回后自动检测打勾），全部完成才进主流程；设置页保留「权限自检」入口随时复查。
- 建议小朵同样做成分步引导 + 设置页自检清单（ticket 01 的设置页骨架预留此位）。
- 引导顺序建议：悬浮窗（M1 即可用）→ 通知 → 麦克风 → 电池豁免 → 自启动/后台弹出（M4 加）。**前三个拿不到就明确降级提示**（无语音、仅键盘对话）。

## 6. 待真机验证清单（交 M4）

1. 目标机型（ticket 08 产出）逐一实测 §1-§4 全部 Intent 落点。
2. MIUI 后台弹出界面在 HyperOS 2 上的页面路径是否变化。
3. 华为 HarmonyOS NEXT（纯血鸿蒙，不兼容 APK）是否在目标机型内——**若在，本产品形态不支持，须升级为用户沟通问题**（记入地图 Not yet specified 候选）。
4. OPPO/vivo 新版系统包名改名（coloros→oplus）的兼容分支。
