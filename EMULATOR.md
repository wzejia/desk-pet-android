# EMULATOR.md — 模拟器使用手册（agent 版，2026-08-17 建成）

> 给子智能体/后续会话的即用手册：起模拟器、装包、授权、UI 自动化、抓日志、避坑。
> 所有命令与坐标均 2026-08-17 实测；环境变动后先跑 §2 自检再干活。
> 分工：**模拟器 = UI/逻辑/文字链/KWS 库加载日常调试；真机 de61cd8 = 声学与 ColorOS 行为验收**
> （真机规范见 CLAUDE.md「真机互斥锁」；模拟器不涉及该锁，多 agent 可并行用）。

## 0. 30 秒速通（复制即用，Git Bash）

```bash
export MSYS2_ARG_CONV_EXCL="*"
export ANDROID_HOME='E:\Android\Sdk' ANDROID_SDK_ROOT='E:\Android\Sdk' ANDROID_AVD_HOME='E:\Android\avd'
ADB="/e/Android/Sdk/platform-tools/adb.exe"
S="-s emulator-5554"

# 模拟器未在跑则启动（PowerShell 更稳，Git Bash 后台起见 §2 坑 4）
"$ADB" devices | grep -q "emulator-5554.*device$" || echo "模拟器未跑，见 §2 启动"

# 等 boot 完成（首次 1-3 分钟）
until [ "$("$ADB" $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done

# 装包 + 授权 + 起主界面
"$ADB" $S install -r "E:/desk-pet-android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" $S shell pm grant com.xiaoduo.deskpet android.permission.RECORD_AUDIO
"$ADB" $S shell appops set com.xiaoduo.deskpet SYSTEM_ALERT_WINDOW allow
"$ADB" $S shell am start -n com.xiaoduo.deskpet/.MainActivity
```

## 1. 环境布局（勿重新调研）

| 项 | 值 |
|---|---|
| SDK 权威 | `E:\Android\Sdk`（C:\Android\Sdk 为退役旧份，勿用勿删） |
| AVD | `xiaoduo_emu` @ `E:\Android\avd`：Pixel 6 画像，1080×2400 @420dpi，Android 14（API 34）google_apis **x86_64** |
| adb serial | `emulator-5554`（**所有 shell 操作显式 `-s emulator-5554`**） |
| 加速 | WHPX 已启用，冷启 ~1-3 分钟 |
| 用户级环境变量 | `ANDROID_HOME` / `ANDROID_AVD_HOME` 已 setx 指 E 盘 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`（双 ABI：arm64-v8a + x86_64，~87M） |
| 构建 | 老命令不变：JDK17 PATH + `./gradlew.bat assembleDebug`（local.properties 已指 E 盘 SDK） |

## 2. 启动与关闭

**启动（推荐 PowerShell，detached 长驻）：**

```powershell
$env:ANDROID_AVD_HOME = "E:\Android\avd"
Start-Process -FilePath "E:\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd","xiaoduo_emu","-no-snapshot","-no-boot-anim","-gpu","auto" -WindowStyle Minimized
```

- **必须先设三个环境变量**（当前 shell 显式 export/set）：`ANDROID_HOME`、`ANDROID_SDK_ROOT`、
  `ANDROID_AVD_HOME` 全指 E 盘——**setx 不溯及既往**，继承旧值的会话会让 emulator 报
  `FATAL: Cannot find AVD system path`（2026-08-17 实测踩过）。
- boot 等待：轮询 `getprop sys.boot_completed = 1`（见 §0），别用固定 sleep。

**关闭**：`"$ADB" -s emulator-5554 emu kill`（温和）；或 `taskkill /f /im qemu-system-x86_64.exe`（强杀）。

## 3. adb 规范（Git Bash 三坑）

1. `export MSYS2_ARG_CONV_EXCL="*"` 防设备参数改写——但它**连本地文件路径也禁转**，
   `install`/`pull` 的本地路径必须写 Windows 风格 `E:/desk-pet-android/...`（POSIX `/e/...` 会 stat 失败）。
2. Windows `.bat`（sdkmanager/avdmanager）**别用 Git Bash `cmd //c`**（会进交互模式静默失败），
   用 PowerShell 工具直调 `& "E:\Android\Sdk\cmdline-tools\latest\bin\xxx.bat"`。
3. `/tmp`（Git Bash）**跨会话残留**：临时 dump/脚本一律独占命名（如 `ui_<日期>_<用途>.xml`），
   否则会读到旧会话文件得出鬼结论（2026-08-17 实测：误读旧真机 dump）。

## 4. 权限授予（模拟器特供，AOSP 镜像可 shell 授）

```bash
"$ADB" $S shell pm grant com.xiaoduo.deskpet android.permission.RECORD_AUDIO      # 麦克风
"$ADB" $S shell appops set com.xiaoduo.deskpet SYSTEM_ALERT_WINDOW allow           # 悬浮窗
```

- appops 授完立即生效，设置页会显示「权限已授权」。**ColorOS 真机禁 shell 授**——两套别混。
- 开「语音唤醒」开关会弹 **Android 13+ 通知权限对话框**（POST_NOTIFICATIONS），
  dump 找 `Allow` 按钮点掉（2026-08-17 实测坐标约 (540,1307)，以 dump 为准）。

## 5. UI 自动化：dump 验证法（先 dump 后 tap，禁止盲点）

Compose 界面 `uiautomator dump` 可拿到全部节点 label+bounds。流程：

```bash
"$ADB" $S shell uiautomator dump /sdcard/ui_<独占名>.xml
"$ADB" $S pull /sdcard/ui_<独占名>.xml "C:/Users/Administrator/AppData/Local/Temp/<独占名>.xml"
```

解析脚本（落自己独占路径后用；输出 `label  bounds  clickable`）：

```python
# parse_ui.py — 用法: python parse_ui.py <xml路径>
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*/?>', xml):
    n = m.group(0)
    d = re.search(r'content-desc="([^"]+)"', n)
    t = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="([^"]+)"', n)
    click = 'clickable="true"' in n
    label = (d.group(1) if d else '') or (t.group(1) if t else '')
    if label or click:
        print(f'{label or "(no-label)":36s} {b.group(1) if b else "":26s} clickable={click}')
```

**关键锚点**（1080×2400 实测坐标；布局会变，**tap 前先 dump 对锚点 label 核验**）：

| 目标 | 锚点 label | 实测 bounds | tap 中心 |
|---|---|---|---|
| 开侧边栏（聊天页左上） | 打开菜单 | [43,129][169,255] | (106,192) |
| 侧边栏→设置 | 设置 | [32,2148][756,2295] | (394,2221) |
| 悬浮窗开关 | （Switch 无label，行首文本「悬浮窗」） | [901,402][1038,528] | (969,465) |
| 语音唤醒开关 | （行首文本「语音唤醒」） | [901,1547][1038,1673] | (969,1610) |
| 设置页返回 | 返回 | [12,150][138,276] | — 用 `keyevent 4` |
| ⚠️ 聊天页右上 | **全屏语音模式**（别误点） | [913,129][1039,255] | — |

**实测怪癖**：
- 全屏语音页「退出全屏」按钮 `input tap` 无响应——用 **`input keyevent 4`**（Back）退出。
- 键盘输入：先点输入框聚焦再 `input text`；**`input text` 只支持 ASCII**（中文进不去，测中文链路用语音或建 DebugBubbleReceiver 类注入）。
- 悬浮窗精灵验收：开悬浮窗后 `input keyevent 3`（HOME）回桌面截图，确认精灵+前台通知。

## 6. 日志抓取（有效 tag 清单）

PetLogger 第一参数即 logcat tag。按 pid 过滤再捞业务 tag：

```bash
PID=$("$ADB" $S shell pidof com.xiaoduo.deskpet | tr -d '\r')
"$ADB" $S logcat -d --pid=$PID | grep -viE "EGL_emulation|BufferQueue|OpenGLRenderer|Gralloc|HostConnection|libEGL|goldfish|chatty|ImeTracker|InputMethod" | grep -iE "<tag>"
```

| tag | 用途 | 样例行（08-17 实测） |
|---|---|---|
| `CloudLLM` | 云端对话链路 | `cloud llm ok, 35 chars, firstToken=1099 ms` |
| `ChatViewModel` | 文字轮生命周期 | `chat send: 5 chars` / `chat round done` |
| `VoiceFgService` | KWS 前台服务 | `always-on KWS started, gate=0.015` |
| `KwsEngine` | KWS 检出/门控统计 | fed/gated/decoded/hits 计数行（`kws stats:`；KwsCore 的日志经 KwsEngine 注入的 logger 走 tag `KwsEngine`，写 `KwsCore` 会漏行；兜底 `grep -iE "kws"`） |
| `AudioCapture` | 采麦状态 | `capture start, source=7, 16kHz/mono/Int16` |
| `sherpa-onnx` | native 库（见 §7） | KeywordSpotterConfig…（W 级配置 dump） |
| `Settings` | 设置页动作 | `KWS always-on enabled by user toggle` |
| 崩溃兜底 | `-b crash` 缓冲区 + `grep -i "UnsatisfiedLink\|FATAL"` | — |

## 7. 能力边界（能测 / 不能测）

**✅ 已验证可用**（证据 `docs/verification/emu-2026-08-17/` 五图）：
Compose 全 UI、悬浮窗精灵+前台通知、KWS 常开（sherpa x86_64 库加载零异常+采麦运行）、
云端对话（dashscope，内置 key 生效）、文字轮全链路。

**⚠️ 受限/不可测**：
- **声学唤醒效果**：模拟器麦 = 宿主机默认输入设备，有无麦/音质不定——「小朵小朵」检出率、
  TTS 听感、AEC 等**一律真机验收**。
- **ColorOS 行为**（后台静音/服务保活/权限弹窗策略）：AOSP 镜像无此环境，结论不可外推。
- **设置页「链路自检」按钮**：前置校验要求 SP 端点配置，内置 cloud key 不回填 SP，
  会提示「请先填写密码」——**非故障**；聊天页直接发消息即等价验证（ASCII，见 §5）。
- 语音自检（按住说话）：依赖宿主麦，结果只作参考。

## 8. 与 Android Studio / 真机的关系

- **Android Studio 共存**：Studio Open 本项目自动读 `local.properties`（已指 E 盘 SDK）；
  Device Manager 可见 `xiaoduo_emu`（ANDROID_AVD_HOME 共享）；Studio 与命令行 adb 是同一
  server，`adb devices` 互见。Studio 若提示升 AGP/Gradle 一律拒绝（锁 AGP 8.5.2 / Gradle 8.9）。
- **真机切换**：插回 de61cd8 时 adb 会同时看到两设备——**adb 命令一律显式 `-s`**；
  真机操作（install/input/screencap…）仍须 `.scratch/device.lock` 互斥锁，模拟器免锁。

## 9. 变更记录

- 2026-08-17 建成（SDK 迁 E 盘 + x86_64 双 ABI + AVD 创建 + 全链路验收，见 DEV_LOG 同日条目）。
