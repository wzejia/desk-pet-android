# 易声(YS) API SDK 与 demo APK 逆向调研报告 — 「红外触发唤醒」集成可行性

> 调研日期：2026-10-26。分析对象（全部只读，未执行任何其中代码）：
> - `E:/desk-pet-android/.scratch/ir-research/ysapi/extracted/20230728-ysapi/20230728-ysapi/` 下 `ysapi.jar`、`Yisheng API Development Document.pdf`、`app.zip`（解包到 `app-zip-src/`）、`API demo.apk`、两个乱码名 .doc/.pdf
> - `E:/desk-pet-android/.scratch/ir-research/apk/api-demo.apk` + `apk/extracted/`（已解包 dex）
>
> 使用的分析命令：`jar tf` / `javap`（JDK17）、`aapt dump badging` 与 `aapt dump xmltree`（build-tools 34.0.0）、`pdftotext`、`unzip`、`md5sum`、`grep -a`、`python -I`（脚本均在 `scripts/`，路径作参数传入）。

---

## 1. SDK 定位

**ysapi = 深圳易声科技（Shenzhen Yisheng Technology, www.yishengtec.cn）面向瑞芯微 Rockchip 系列 Android 板卡的系统级控制 SDK**（广告机/一体机/自助终端类板卡），文档编号 YS-API-001，包内版本 V5.4-20230728（PDF 修订记录 2023.07.28 V5.4）。

证据：
- PDF 首页：`Shenzhen Yisheng Technology Co., LTD — The API Reference Documentation — Document number YS-API-001`（scripts/ysapi-doc.txt 前 10 行）
- jar 类清单（`jar tf ysapi.jar`，55 类）：全部在 `com.ys.rkapi` / `com.ys.myapi` 下，product 子包按 SoC 分板卡实现：`YS3128/YS3288_5/7/9、YS3328/YS3368、YS3399(_7/_9)、PX30、RK3566_11、RK3568_11、x301、GT8953_8`，由 `YsFactory.getRK()` 分发 —— 典型 Rockchip 板卡 SDK 形态。
- demo 包名 `rkandroidapi.ys.com.rkandroidapi`（aapt badging）。

**目标硬件与连接方式**：跑在易声 RK 板卡的 Android 固件上。SDK 本体是**纯 binder 客户端**：应用绑定固件内置系统应用 `com.ys.ys_receiver` 的服务 `com.ys.ys_receiver.AIDLService`（MyManager 字节码：`ComponentName("com.ys.ys_receiver","com.ys.ys_receiver.AIDLService")`；`Constant.YSRECEIVER_PACKAGE_NAME` 初值同，包检测失败时回退 `com.yishengkj.achieve`）。串口只提供 `getUartPath("TTYSx")` → `/dev/ttySx` 路径查询（PDF 7.3 章，ysapi-doc.txt:1984-2015），无串口读写 API。**不是 USB/网络 SDK**。

**关键结论：「红外」能力 = 不存在**。
- PDF 全目录 10 章（Get system information / Shutdown-Reboot / Display / Installation-upgrade / Network / Storage / Timer switch / GPIO / Other）**无任何红外章节**（ysapi-doc.txt:100-180）。
- `jar tf` 全部 55 类中无 ir/infrared/remote 命名；`javap -constants` 全 product 类 grep `ir|infrared|remote|wake` 仅命中 `awaken`（休眠唤醒方法）与 PX30 的 `DIRECTION`（GPIO 方向节点）。
- 全 API 面（javap MyManager 130+ 方法、IgetMessage 18 方法、INewApi 80 方法、IGpio 4 方法）无红外收码/发码/学习接口。
- 文档「唤醒」仅两处：`setDormantInterval(Context, long)`（10.4 章，ysapi-doc.txt:2534-2566）与 `awaken()`（修订记录 "Add the dormancy wake up method"，ysapi-doc.txt:56；存在于 MyManager/INewApi 及全部 product 类签名）——后者是**应用软件主动唤醒休眠中的系统**，中文 doc 原文「休眠之后唤醒系统的方法」（scripts/doc_scan.py 扫描 cabinet-doc-src.doc @0x45f20）。均非红外触发。

## 2. ysapi.jar 完整 API 面

类清单（`jar tf ysapi.jar`，55 类）：
- `com/ys/myapi/`：IGpio、INewApi、IgetMessage（各含 Stub/Proxy，binder AIDL 接口）
- `com/ys/rkapi/`：BuildConfig、Constant、Device、Gpio、MyManager(+2 内部类)、MySDK、NewApi
- `com/ys/rkapi/Utils/`：GPIOUtils、LogUtils、NetUtils、ScreenUtils、ShellUtils、SilentInstallUtils、StorageUtils、TimeUtils、Utils、VersionUtils
- `com/ys/rkapi/product/`：YS、YsFactory + 各板卡类

核心入口 `com.ys.rkapi.MyManager`（`javap -classpath ysapi.jar com.ys.rkapi.MyManager`）关键签名：
- `public static synchronized MyManager getInstance(android.content.Context)`
- `public void bindAIDLService(android.content.Context)` / `unBindAIDLService(...)` —— 绑定/解绑 ys_receiver 的 AIDLService
- `public void setConnectClickInterface(MyManager.ServiceConnectedInterface)` —— onConnect() 回调后 API 可用（唯一回调接口）
- `public boolean awaken()` —— 休眠后唤醒系统（主动）
- `public boolean setDormantInterval(android.content.Context, long)`
- `public boolean setGpioDirection(int, int)`（1=输入 0=输出）、`getGpioDirection(int)`（返回 In/Out）、`getGpioValue(int)`（读电平）、`writeGpioValue(int, String)`、`exportGpio(int)`、`upgradeRootPermissionForGpio(int)`
- `public String getUartPath(String)` —— "TTYS1" 返回 "/dev/ttyS1"
- `public boolean execSuCmd(String)` —— root shell 由系统侧代执行
- `public boolean selfStart(String)`（开机自启）、`daemon(String, int)`（守护进程，0=30s/1=60s/2=180s）

无「接收红外码的回调/轮询接口」、无红外相关广播注册 API、无 INPUT keyevent 注入接口。

AIDL 接口（javap）：`IgetMessage`（信息/网络/时间/截图/卸载/getApi/getGpio）、`INewApi`（显示/网络/开关机/GPIO/守护等 80 方法，含 awaken() / setDormantInterval(long)）、`IGpio`（getGpioValue/setGpioValue/getGpioDirection/setGpioDirection）。

工具类 `com.ys.rkapi.Utils.GPIOUtils`（javap）：`readGpio(int)`、`readGpioPG(String sysfs路径)`、`readGpioPGForLong(String)`、`writeGpio(int,int)`、`exportGpio(int)`、`do_exec(String)` —— demo 用它直读 sysfs。

初始化三步（PDF 第 1 章，ysapi-doc.txt:244-258）：`getInstance(this)` → `bindAIDLService(this)` → `setConnectClickInterface(onConnect)`；销毁时 `unBindAIDLService(this)`。文档原文："V5.3-20211028 开始…all API calls to AIDL mode…initialization interface can be executed in the onCreate() of Application or Activity"。

## 3. demo APK 集成方式

- `aapt dump badging "API demo.apk"`：`package: name='rkandroidapi.ys.com.rkandroidapi' versionName='1.1' sdkVersion:'17' targetSdkVersion:'30'`，label `YSAndroidAPI`，权限仅存储/开机/SETTINGS/PHONE_STATE/NETWORK。`apk/api-demo.apk`（md5 132a8b1e643fac4c544e8caaec5e7605）与 ysapi 包内 `API demo.apk`（md5 551995720b30bbb1b8d7d8adc56899e3）**md5 不同但 badging 一致**（同包名同版本，重签名/重打包关系）。
- `aapt dump xmltree`：**只有 4 个 activity，零 service / 零 receiver / 零 provider / 零 uses-library**；唯一 intent-filter 是 MainActivity 的 LAUNCHER。→ demo 不监听任何系统广播。
- app.zip 源码（解包 `app-zip-src/`，类名与 dex 字符串一致，以源码为准）：
  - `MainActivity.java:39-49`：`manager = MyManager.getInstance(this); manager.bindAIDLService(this); manager.setConnectClickInterface(...)` 标准三步初始化。
  - `MainActivity.java:201-388`：按钮点击 → `manager.getXxx()` → `ToastUtils.showToast(...)` 展示（纯控制类演示：背光/HDMI/旋转/以太网/静默安装等）。
  - `GpioActivity1.java:82-140`：GPIO 输入演示 = `manager.setGpioDirection(index,1)` 置输入后，点按钮 `manager.getGpioValue(index)` 读电平；或 `GPIOUtils.readGpioPG(PX30.IO1)` 直读 sysfs（GpioActivity1.java:144-146）。**点按式读取，无中断/回调**。
  - `Main3Activity.java:44-77`：sendBroadcast 发 `com.ys.slide.*` 等控制广播（发往系统，非监听）。
- classes.dex `grep -a`（`grep -aoE "[ -~]{6,}" classes.dex | grep -iE 'ysapi|ir|infrared|serial|tty|uart|wake|remote'`）：仅命中 SDK 自身类名、`/dev/ttyS0-5`、`/sys/class/gpio/*` 路径与 androidx 的 RemoteInput/WakeLock（通知/唤醒锁框架 API，非红外）。

**调用链**：LAUNCHER → MainActivity.onCreate → bindAIDLService（等 onConnect）→ 用户点按钮 → manager.方法 → binder → ys_receiver 系统服务 → sysfs/系统属性/root 命令 → Toast 显示结果。收到「输入」的唯一方式 = 主动点按/轮询读取。

## 4. 事件到达机制（核心结论）

**红外触发从硬件到应用代码，在本 SDK/文档/demo 中不存在原生通道。** 证据：
1. `jar tf` + 全量 javap：55 类无一含 IR/红外语义；唯一监听器接口是 `MyManager$ServiceConnectedInterface.onConnect()`（连接成功通知，非数据事件）。
2. PDF 3042 行全文 grep `infrared|IR|remote|唤醒|红外`：仅命中休眠唤醒与软键盘章节。
3. `Constant` 全部广播 action（javap -c 反汇编 static 块 + -constants 输出）：`android.intent.action.shutdown/reboot`、`com.ys.set_static_ip`、`com.ys.set_dhcp`、`com.ys.dormant_interval`、`android.intent.PowerOnTime`、`com.ys.rotation_screen`、`com.ys.execSuCmd`、`android.intent.action.YS_UPDATE_FIRMWARE`、`com.ys.screenshoot`、`com.ys.update_time` 等约 20 个——**全部是应用→系统的控制指令**，无一个「事件上报」类广播。
4. demo AndroidManifest 无 receiver；dex 无红外字符串。

「触发类输入」在本平台上唯三的到达路径（均有证据支撑）：
- **GPIO 电平轮询**：红外感应器（人体感应 PIR/红外对射）接板卡保留 IO → `setGpioDirection(n,1)` 置输入 → 应用循环 `getGpioValue(n)`（AIDL）或 `GPIOUtils.readGpioPG("/sys/...")`（直读 sysfs，demo GpioActivity1.java:144 同款）。V5.2+ 参数语义：gpio 传 1..N 表示第 N 路保留 IO（PDF 第 9 章注：RK3288-M8 保留 IO 139/138/68/69 对应传 1/2/3/4）。
- **串口数据自读**：红外/扫码模块接 UART → `getUartPath("TTYSx")` 拿 `/dev/ttySx` 路径（ysapi-doc.txt:1984-2015，输出例 "UART-1= /dev/ttyS1"）→ SDK 不提供读写，应用需自实现串口读（第三方串口库或 JNI），设备权限通常需 root/系统组。
- **遥控器按键（仅可能性，无证据）**：若易声固件把红外遥控键映射为标准 Android KeyEvent，则走 kernel→输入子系统，与 ysapi 无关。SDK 与文档对 IR 协议解码零涉及，此路径未获任何证据支持。

另：`Constant.IO_INPUTS = ["/sys/devices/misc_power_en.22/key", "/sys/class/display/HDMI/enable"]`、`IO_OUTPUTS = []`（javap -c Constant static 块）——老版本 IO 约定是电源键状态与 HDMI 状态两个只读节点；`getGPIOStatus/setGPIOStatus` 操作 `/sys/devices/misc_power_en.23/<name>`，属电源使能控制 IO，非红外。

## 5. 文档摘要（Yisheng API Development Document.pdf，V5.4-20230728）

- **使用方式**（第 1 章）：jar 拷入 app/libs；V5.3 起 AIDL 统一模式，初始化建议放 Application/Activity onCreate，销毁必须 unBindAIDLService（ysapi-doc.txt:244-258）。
- **鉴权/配对**：无。无签名校验、无 token、无配对流程——只要设备固件内置 `com.ys.ys_receiver`（fallback `com.yishengkj.achieve`）系统应用，任何应用 bind 即用。root 能力经 `execSuCmd` 由系统侧代执行。
- **章节要点**：Ch2 系统信息（版本/内存/SN/厂商识别码）；Ch3 开关机重启；Ch4 显示（截图/旋转/三栏状态/背光/HDMI/副屏截图/DPI）；Ch5 升级与静默安装卸载；Ch6 以太网全套；Ch7 存储（SD/U盘/串口路径）；Ch8 定时开关机（周模式/多组时刻）；Ch9 GPIO 方向与电平（9.1-9.4）；Ch10 其他（系统时间/软键盘/休眠时长 setDormantInterval/ROOT shell/HDMI 状态/默认输入法/语言/CPU 温度/ADB 开关/开机动画/默认 Launcher/自启 selfStart/守护进程 daemon/安装黑白名单/DPI）。
- **版本与依赖**：jar 零第三方依赖（纯 framework binder）；demo minSdk 17 / targetSdk 30 / compileSdk 30（Android 11）；固件侧按板卡 Android 版本分 API（修订记录含 3288/3399 9.0 API、3288 11.0 与 amlogic API）。
- **两个乱码名 .doc/.pdf**：经 python 按文件大小复制为 ASCII 名后处理（scripts/copy_garbled.py）。确认为**同一份 API 文档的中文原版**：同一文档号 YS-API-001、同一修订史（V1.0-20180602 → V5.4-20230728 逐条对应）；doc 内 UTF-16LE 明文串「增加休眠唤醒方法」「查询串口绝对路径」「执行关机操作走安卓标准关机流程」等与英文版一一对应（scripts/doc_scan.py 输出 @0x2cfda/@0x3f944/@0x45f20）。PDF 侧中文字体全子集化（pdffonts：8 个字体 sub=yes）文本不可提取，以 doc 取证。全文**无「红外」「遥控」字样**（UTF-16LE/GBK 双编码二进制计数：红外=0、遥控=0；休眠=11、串口=8、唤醒=2）。

## 6. 集成到第三方 App（小朵常驻前台服务）的可行路径评估

前提澄清：ysapi 只能在**易声 RK 板卡固件**上工作（bind 目标 com.ys.ys_receiver 是固件内置系统应用；普通手机/平板上 bind 必失败）。若目标设备确为易声板卡：

| 路径 | 做法 | 前提 | 评估 |
|---|---|---|---|
| A. 依赖 ysapi.jar + GPIO 轮询 | 前台服务内 getInstance+bindAIDLService，onConnect 后 setGpioDirection(n,1) 置输入，循环（如 100-200ms）getGpioValue(n)，电平跳变→触发自有逻辑（可再调 awaken() 唤醒休眠的系统） | 设备=易声板卡；红外感应器物理接在保留 IO；需向易声确认板上保留 IO 的路序/GPIO 编号 | **最贴合「红外触发唤醒」的官方路径**。GPIO 是轮询非中断（demo 亦如此）；轮询间隔与功耗需权衡；AIDL binder 多客户端共享，无独占问题 |
| B. GPIOUtils 直读 sysfs | 绕过 AIDL，readGpioPG("/sys/class/gpio/gpioN/value") 或先 exportGpio | 需 root（upgradeRootPermissionForExport / do_exec("su")），或固件已放开 sysfs 权限 | 与 A 等价但更脆；仅当 AIDL 不可用而 root 可用时考虑 |
| C. 串口自读 | getUartPath 拿路径 + 自实现 UART 读 | /dev/ttySx 读写权限（root/系统组）；红外模块须串口输出协议码 | SDK 零支持，工作量大；仅当红外模块是串口型才有意义 |
| D. 监听 demo/系统广播 | 无 | **不存在可监听的红外/事件广播**（第 4 节证据 3）；demo 也不转发任何输入事件 | 不可行 |
| E. 依赖 ysapi.jar 但设备非易声板卡 | — | bind 失败，全部 API 不可用 | 不可行；此类「红外唤醒」在通用 Android 上需另找硬件方案 |

**直答**：文档/SDK 不支持「收到红外码」这一事件抽象；可行接法只有 A（或其变体 B/C）——把「红外」还原为 GPIO 电平/串口字节流，由应用自己轮询。SDK 初始化无独占性（binder 多客户端），但 GPIO 的 export/方向设置若被两个应用同时操作会互踩，建议只由小朵前台服务独家管理用到的 IO。

## 附：安全纪律执行情况

- 全程未执行 jar/dex/apk/app.zip 内代码；未接触设备/模拟器；未从解包目录启动解释器或构建工具。
- 解包：app.zip → `app-zip-src/`（新建目录）；分析脚本集中在 `scripts/`（copy_garbled.py、doc_scan.py，均 python -I 运行）；Windows 工具（aapt）调用前 export MSYS2_ARG_CONV_EXCL="*"。
- 遗留障碍：乱码名 PDF 中文文本层不可提取（pdffonts 显示 8 个字体全部子集化），已用同内容 .doc 的 UTF-16LE 明文扫描替代取证。
