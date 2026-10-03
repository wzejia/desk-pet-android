# 非代码面评审报告（清单 / 构建脚本 / XML 配置）

- 日期：2026-10-03
- 评审范围：AndroidManifest.xml、gradle 构建脚本（app/根/settings）、res/xml 全部、res/values、gradle.properties、wrapper、签名与秘钥入库情况
- 方式：纯只读精读，未运行构建、未读取 secrets/cloud.properties 内容（秘钥卫生）
- 项目事实基线：内网平台 10.0.18.166（443 自签按 host 白名单信任）、ASR/TTS 10.0.18.97、悬浮窗+麦克风、minSdk 26 / targetSdk 34

---

## 一、逐文件审阅清单

### 1. app/src/main/AndroidManifest.xml（78 行）
- 看了什么：权限声明（SYSTEM_ALERT_WINDOW / INTERNET / RECORD_AUDIO / VIBRATE / FOREGROUND_SERVICE(+MICROPHONE) / POST_NOTIFICATIONS）、queries 包可见性、application 属性、4 个组件（MainActivity / PetOverlayService / VoiceForegroundService / DebugBubbleReceiver / FileProvider）。
- 结论：
  - 权限最小化，无多余权限（无定位/存储/联系人等）；targetSdk 34 麦克风前台服务所需的 FOREGROUND_SERVICE_MICROPHONE 已声明 ✓。
  - `allowBackup="false"`（L26）：云备份与 D2D 迁移一并关闭；无 dataExtractionRules 文件但无需（allowBackup=false 已覆盖）✓。
  - 未设 `usesCleartextTraffic`，明文管控全部收敛到 networkSecurityConfig ✓。
  - 两个 service 均 `exported="false"` ✓；MainActivity 仅标准 LAUNCHER 导出 ✓。
  - DebugBubbleReceiver `exported="true"` 无 permission 保护（L57-65）——见 F-P3-1。
  - 所有 manifest 引用的类（.App/.MainActivity/.PetOverlayService/.ui.overlay.DebugBubbleReceiver/.VoiceForegroundService）均已确认存在于源码；theme/mipmap/@xml 资源均存在；FileProvider authorities 与代码 `"${packageName}.fileprovider"` 一致 ✓。
  - 应用名「小朵」硬编码于 L28（values/ 无 strings.xml），纯卫生观察，不计 finding。

### 2. app/build.gradle.kts（95 行）
- 看了什么：cloudSecrets 读取链、buildConfigField 注入、abiFilters、buildTypes（release 签名/minify）、依赖清单、compose/kotlin 版本对应。
- 结论：
  - 密钥经 secrets/cloud.properties（gitignored）→ BuildConfig 注入（L33-35）——见 F-P2-2；插值未做转义——见 F-P3-5。
  - release 挂 debug 签名 + isMinifyEnabled=false（L40-43，注释已自述权衡）——见 F-P2-1。
  - abiFilters 同时含 x86_64 进 release（L30）——见 F-P3-4。
  - 依赖均为 google/mavenCentral 标准坐标；本地 AAR（libs/sherpa-onnx-1.13.4.aar）已入 git 锁定版本，注释注明 k2-fsa 官方源、用户已批准 ✓。
  - 未引用 proguardFiles（minify 关闭，一致）✓；namespace/applicationId 与 receiver action 前缀 `com.xiaoduo.deskpet` 一致 ✓。

### 3. 根 build.gradle.kts（4 行）
- 看了什么：AGP 8.5.2 / Kotlin 1.9.24 插件版本声明。
- 结论：无问题。与 app 模块 compose compiler 1.5.14 的对应关系正确（Kotlin 1.9.24 → 1.5.14）。

### 4. settings.gradle.kts（16 行）
- 看了什么：pluginManagement / dependencyResolutionManagement 仓库源。
- 结论：仅 google()、mavenCentral()（插件多 gradlePluginPortal()），`FAIL_ON_PROJECT_REPOS` 防止模块私加仓库 ✓。无可疑镜像源。

### 5. app/src/main/res/xml/network_security_config.xml（21 行）
- 看了什么：domain-config 明文白名单（10.0.18.166 / .97 / .24 / .47 / 127.0.0.1 / localhost）、有无 base-config / trust-anchors 覆盖。
- 结论：
  - 无全局 `<base-config>` 覆盖、无自定义 trust-anchors、无 `includeSubdomains` 滥用；默认域走系统 CA + 禁明文 ✓。这是收敛得当的写法。
  - 与代码默认端点（NetConfig.kt:90-92 的 166:8080 / 97:9802 / 97:9803；Prefs.kt:219 的 .47:8080；S41 回环 8848）一致，无缺口 ✓。
  - 与代码 TLS 白名单（HttpClients.kt:25 `TRUSTED_HOSTS = {"10.0.18.166","10.0.18.97","10.0.18.24"}`）是两套机制（明文放行 vs 443 自签跳验），当前内容各自成立，但为两份手工维护清单——见 F-P3-6。
- 附注（非 finding）：白名单 TrustManager 对 3 台 host 完全跳过证书校验（含过期/域名匹配），属项目文档明示的内网自签设计，记录在案即可。

### 6. app/src/main/res/xml/file_paths.xml（4 行）
- 看了什么：FileProvider 暴露路径。
- 结论：仅 `files-path logs/`，收敛良好；唯一调用方 SettingsScreen.exportLogs（L1052-1074）带 `FLAG_GRANT_READ_URI_PERMISSION` 走系统分享面板，用户显式触发 ✓。无 path 事故。

### 7. res/ 其余安全相关配置
- 看了什么：全 res 目录清单。xml/ 下仅上述两文件；values/ 下仅 ic_launcher_background.xml（纯颜色资源）；无 backup/dataExtractionRules 相关 xml（allowBackup=false 下不需要）；无 strings.xml、无 keys.xml。
- 结论：资源层无硬编码敏感串。零发现。

### 8. gradle.properties（10 行）
- 看了什么：jvmargs、缓存开关、AndroidX 开关、systemProp 代理。
- 结论：无敏感项（无账号/token/密码）；代理 127.0.0.1:7897 为本机 Clash 构建期代理，出网信任面=本机代理进程，内网自用可接受。L1 行内 `#` 注释疑点——见 F-P3-2。

### 9. gradle/wrapper/gradle-wrapper.properties（附带检查）
- 看了什么：distributionUrl。
- 结论：`file:///D:/gradle-dist/gradle-8.9-bin.zip` 本地发行包 + 无 distributionSha256Sum——见 F-P3-3。

### 10. 秘钥/签名入库情况（附带检查）
- 看了什么：.gitignore 规则与 `git check-ignore` / `git ls-files` 实测。
- 结论：secrets/（含 cloud.properties）、local.properties、*.keystore、dist/、designs/ 均已忽略且实测未被 git 跟踪；仓库内无任何 keystore/apk/密钥文件 ✓。local.properties 仅含 sdk.dir，干净 ✓。本次审计未读取 cloud.properties 内容。

---

## 二、Findings

### P1
无。

### P2

**F-P2-1｜release 包挂公开已知的 debug 签名**
- 位置：app/build.gradle.kts:43（关联 L40 `isMinifyEnabled = false`）
- 原文：`signingConfig = signingConfigs.getByName("debug")`
- 影响：Android 默认 debug 密钥库（~/.android/debug.keystore，口令公开）全网皆同。任何第三方可用同一签名构造 APK，在本机被授「安装未知应用」的前提下**直接覆盖安装**（同签名视为升级），无需先卸载——升级链完整性零保护；叠加未混淆，密钥字符串与业务逻辑在 APK 中裸奔。dist/ 中已有按此方式分享的 release APK（xiaoduo-0.1.0-m1-*-release.apk），说明该形态已在实际流通。注释表明是「可直接安装分享」的有意权衡，故定 P2 而非 P1。
- 建议：至少生成一个项目独立 keystore（不入库，密码走 secrets/）替换 debug 签名；成本极低且不改变「直接安装分享」体验。正式发布前再补混淆规则。

**F-P2-2｜云端密钥以 BuildConfig 明文编入 APK**
- 位置：app/build.gradle.kts:33-35，变量名 `CLOUD_KEY`、`ALIYUN_SPEECH_KEY`、`VOLC_SPEECH_KEY`（源值：secrets/cloud.properties，未入库 ✓；本报告不抄录任何密钥值）
- 影响：BuildConfig 字符串常量会被内联进 dex，用 apktool/jadx 即可从任一分发出去的 APK 中还原密钥。密钥一旦泄露即长期有效（除非服务端轮换），且该 APK 正以 release 形态在内网分享。注释（L8-9、L32）表明这是 2026-08-13 已拍板的设计，属已知接受风险，列示如下供分诊。
- 建议：短期——确认服务端对这些 key 有调用限额与可轮换手段，泄露后只换 key 不换包（重打）；中期——改为设备级 token（由平台按设备签发短时效凭证），密钥不出服务端。

### P3（卫生类，共 6 条）

**F-P3-1｜DebugBubbleReceiver exported=true 常驻 manifest**
- 位置：app/src/main/AndroidManifest.xml:57-65
- 原文：`android:exported="true"`（action `com.xiaoduo.deskpet.DEBUG_BUBBLE*`，无 permission）
- 影响：receiver 内有 `FLAG_DEBUGGABLE` 守卫（DebugBubbleReceiver.kt:24），release 包不生效，故无实际漏洞；但 debug 包上任意同机应用可伪造气泡文本（冒充桌宠说话），且导出面常驻 manifest，验收期结束易被遗忘。
- 建议：给 receiver 加 `android:permission`（signature 级自定义权限）或迁入 debug source set 的 manifest；至少在注释中加「转正前必拆」标记（现有注释已提，建议升级为 issue 跟踪）。

**F-P3-2｜gradle.properties 行内 # 注释疑似混入 jvmargs 值**
- 位置：gradle.properties:1
- 原文：`org.gradle.jvmargs=-Xmx2048m ... -Djava.net.preferIPv4Stack=true # S55: 本机 Java 通配 UDP 绑定缺失...`
- 影响：java.util.Properties 的 `#` 只在行首才是注释，行内 `#` 及其后文字属**值的一部分**，理论上会随 daemon JVM 参数传入（`#` 非 `-` 开头 token）。S56 提交信息称「642 green; both boards」，说明当前环境下大概率被容忍，但属未定义行为，Gradle 升级或换机后可能突发 daemon 起不来。**不确定项：本次只读审计未跑构建，无法实证 Gradle 8.9 是否过滤该 token。**
- 建议：把「S55: ...」说明挪到独立的 `#` 注释行，值保持纯净。

**F-P3-3｜Gradle wrapper 指向本地发行包且无校验和**
- 位置：gradle/wrapper/gradle-wrapper.properties:4
- 原文：`distributionUrl=file\:///D\:/gradle-dist/gradle-8.9-bin.zip`
- 影响：换机/CI/新人克隆必挂（依赖 D 盘本地 zip）；且未配 `distributionSha256Sum`，发行包完整性无校验。
- 建议：改回 `https://services.gradle.org/distributions/gradle-8.9-bin.zip`（本机代理已可出网）并补 distributionSha256Sum；本地 zip 方案如必须保留，至少记录 sha256。

**F-P3-4｜release 包携带 x86_64 ABI**
- 位置：app/build.gradle.kts:30
- 原文：`ndk { abiFilters += listOf("arm64-v8a", "x86_64") }`
- 影响：模拟器专用 x86_64（含 sherpa-onnx 对应 .so）打进生产 release，APK 无谓增大约 15-20MB（注释自述）。板端仅用 arm64-v8a。
- 建议：x86_64 移入 debug 变体（`androidComponents`/sourceSets 按 buildType 设 abiFilters），或出包时用 `-Pabi=arm64` 类参数裁剪。

**F-P3-5｜buildConfigField 密钥插值未做字面量转义**
- 位置：app/build.gradle.kts:33-35（`cloudSecret()` L14-15）
- 原文：`buildConfigField("String", "CLOUD_KEY", "\"${cloudSecret("CLOUD_KEY")}\"")`
- 影响：秘钥值若含 `"` 或 `\`，会直接破坏生成的 Kotlin 字符串字面量（构建失败，最坏情况生成语义错误的常量）。当前文件能编译说明现值无特殊字符，但对未来换 key 是隐性坑。
- 建议：cloudSecret 返回前做 `escape(`\``/`$`/`\`)` 或用 `org.intellij.lang.annotations` 无关的简单转义函数包一层。

**F-P3-6｜内网主机白名单两处手工维护**
- 位置：app/src/main/res/xml/network_security_config.xml:8-20 与 app/src/main/java/com/xiaoduo/deskpet/net/HttpClients.kt:25
- 原文：NSC 明文域列表 `{.166,.97,.24,.47,127.0.0.1,localhost}` vs `TRUSTED_HOSTS = {.166,.97,.24}`
- 影响：两份清单机制不同（cleartext 放行 vs 443 自签跳验），当前内容各自成立、无缺口；但新增内网主机时需人工同步两处，NSC:12 注释只提示了 NSC 自己。漏配症状是「TLS 握手失败/明文被拦」这类运行期故障，排查成本高。
- 建议：在 HttpClients.kt:25 补一行注释指向 NSC（或抽一个共享常量文档说明两处清单的职责边界）。

---

## 三、汇总

| 级别 | 数量 | 编号 |
|---|---|---|
| P1 | 0 | — |
| P2 | 2 | F-P2-1（debug 签名出 release）、F-P2-2（BuildConfig 明文密钥，已知接受风险） |
| P3 | 6 | F-P3-1 ~ F-P3-6 |

零发现但已审阅的文件：根 build.gradle.kts、settings.gradle.kts、file_paths.xml、res/values/*（无 strings.xml/keys.xml，无敏感串）、local.properties、.gitignore/入库检查。整体结论：非代码面配置质量良好（权限最小化、备份关闭、明文/自签收敛、秘钥文件未入库），主要风险集中在「分发形态」——debug 签名 + 明文密钥 + 未混淆三件事互相放大，且都已被项目注释自认，适合作为一组决策提交用户拍板。
