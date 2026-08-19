# M1 方案：悬浮桌宠本体（Android）

> ⚠️ **状态：历史参考（2026-07-30 复核）**。本文件部分决策已被后续产物修订：
> DataStore→SharedPreferences（MASTER-PLAN D11）、M1 无网络→链路调试页前置（D12/KD4）、
> 占位气泡存疑（见冲突 C1）。以 `docs/MASTER-PLAN.md` 权威层级为准；
> 交叉检验与冲突裁决见 `docs/dev-handbook/11-grilling-2026-07-30-crosscheck.md`。
>
> 来源：grilling 会话共识（**实际日期 2026-07-30**，原标注 07-28 系笔误——该会话在不知晓
> 仓库已建成 M1、已有两轮决策的情况下进行，权威层级低于 MASTER-PLAN 既有层级）。
> 上游文档：交接文档（已归档至 `docs/m1-dev-kit/handoff-2026-07-28.md`）+
> 知识包 `E:\desk-pet-migration\desk-pet\docs\android-dev-brief.md`（协议/参数的唯一权威来源）。
> 本文件只管 M1；M2-M4 见知识包 §9。

## 0. 产品决策（grilling 已拍板，不再重议）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 目标手机能否接入内网 | **能**（10.0.18.x 同网段/VPN），M2+ 链路可行 |
| 2 | 合规体验成本 | **全接受**：永久前台服务通知 + 麦克风指示点 + 4-5 权限引导（含 M3 语音常开唤醒） |
| 3 | 真机 | 1 台 OPPO/vivo 系主力机（**ColorOS/OriginOS 引导分支优先**） |
| 4 | 本轮范围 | **严格 M1**：悬浮桌宠本体，无网络依赖 |
| 5 | 交互集 | 静止 idle + 点击挥手 + 直接拖拽 + 占位气泡（不做自主游走/双击/长按手势） |
| 6 | 工具链 | **纯 CLI**：JDK17 + cmdline-tools + platform-tools + build-tools + android-34 + Gradle wrapper，走 Clash 代理（127.0.0.1:7897） |

环境实测：本机无 Java/SDK；BIOS 虚拟化未开 → **模拟器不可用，M1 全程真机联调**。

## 1. 技术默认（8 项，grilling 中声明、用户未异议）

- a. 仓库 `E:\desk-pet-android`（新 git，独立于桌面仓库）；Kotlin + minSdk 26 + target/compileSdk 34 + Gradle KTS
- b. overlay：`PetOverlayService` + `TYPE_APPLICATION_OVERLAY`，自定义 View Canvas 播帧（知识包 §4.1 帧表），窗口 192×288dp × scale（208 精灵 + 80 气泡）
- c. 占位气泡：点击挥手时冒一句招呼，40ms/字打字机（§4.2 参数）——验证 M2 气泡通路
- d. 设置页：单 Activity——缩放滑条 / 气泡开关 / 分享日志 / 版本号；DataStore 存配置 + 记忆宠物位置
- e. 文件日志设施第一天立（教训 #44），设置页可直接分享日志文件
- f. App 名「小朵」，图标用 spritesheet idle 首帧；debug APK 验收，不做 release 签名
- g. 素材只读复制自桌面仓库（`pet/assets/xiaoduo/spritesheet.webp` → assets）；发现知识包与桌面端代码不一致时回写 android-dev-brief.md
- h. ColorOS「USB 安装」可能要登录欢太账号——卡住则退化手动传 APK 安装，adb 只用于 logcat

## 2. M1 施工清单（ticket 级）

| # | 任务 | 要点/契约 |
|---|---|---|
| T0 | 装 CLI 工具链 | OpenJDK 17 → sdkmanager 拉 platform-tools/build-tools/platforms;android-34，全程 `--proxy`；`sdkmanager --licenses` 接受 |
| T1 | 新建工程 | Empty Activity 模板手工搭（无 Studio）：settings.gradle.kts / build.gradle.kts / gradle wrapper / AndroidManifest；applicationId `com.deskpet.xiaoduo` |
| T2 | 文件日志设施 | App 启动即初始化：文件滚动写 + 崩溃捕获 + 分享入口（教训 #44，先于一切业务代码） |
| T3 | 悬浮窗权限引导页 | 首启检测 `Settings.canDrawOverlays`；未授权→引导页→`ACTION_MANAGE_OVERLAY_PERMISSION` 跳转；预留 ROM 私有权限说明位（ColorOS 自启动/后台冻结、OriginOS 悬浮窗二级开关） |
| T4 | overlay 渲染 | PetOverlayService + WindowManager；spritesheet.webp 复制进 assets；按 §4.1 帧表驱动（idle 行 0，6 帧，1100ms/帧）；192×208/帧，8 列 |
| T5 | 拖拽 + 点击挥手 | 按下即拖（移动窗口）；轻点→waving 行播一遍回 idle；状态切换重置动画；Android 12+ 不受信任触摸限制用「宠物大小窗口」方案（§8），不用全屏 hit 层 |
| T6 | 占位气泡 | 窗口上部 80dp 区域；半透明白底圆角（§4.2 样式）；点击挥手时显示招呼语 + 40ms/字打字机 |
| T7 | 设置页 | 缩放滑条（即时生效）/ 气泡开关（即时生效）/ 分享日志 / 版本号；DataStore 持久化 + 宠物位置记忆 |
| T8 | 真机验收 | 见 §3 七条；配合用户开开发者选项+USB 调试 |

## 3. M1 验收清单（真机 7 条）

1. 首启权限引导 → 跳转系统悬浮窗授权 → 授权后小朵出现
2. 小朵悬浮在其他 App 之上，idle 动画循环
3. 点击 → waving 动画 + 占位气泡打字机
4. 按住拖动跟手，松手位置保持，杀进程重进位置记忆
5. 设置页缩放 / 气泡开关即时生效
6. 日志文件产出且可分享
7. 杀进程重进无崩溃、服务正常重建

## 4. 分工

- **pi**：装工具链、写码、构建、adb 安装/logcat、验收协助
- **用户**：手机开开发者选项 + USB 调试、配合连接（或手动装 APK）、执行真机验收 7 条

## 5. 风险与对策（M1 范围内）

| 风险 | 对策 |
|---|---|
| ColorOS「USB 安装」卡账号验证 | 退化手动传 APK（决策 h） |
| ROM 私有权限页跳转 Intent 碎片化 | 引导页先做通用跳转 + 说明位，真机实测后补 ColorOS/OriginOS 专属 Intent |
| Android 12+ 不受信任触摸限制 | 宠物大小窗口 + 拖拽切 flag（§8 既定方案），不做全屏 hit 层 |
| 工具链下载被墙 | 全程 Clash 代理；sdkmanager 支持 `--proxy=http --proxy_host=127.0.0.1 --proxy_port=7897` |

## 6. M1 之外（备忘，不在本轮）

- M2 对话链路：AG-UI 流式消费三件套（终止帧校验 + 空 run 重试×2 + SNAPSHOT 兜底）第一天就要写进去；TTS 直连 9803 勿经 166 转发
- M3 语音：KWS gate=0.015 是单机校准值 → 可配置 + 校准入口；VAD 最小解照抄 §3.4（pre-roll deque 容量=6）
- 第三次移植参数对账表（教训 #47）：M2/M3 开工前把语音链路参数列单一事实源逐项对照
