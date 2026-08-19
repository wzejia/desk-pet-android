# 03 · 决策与契约总表（改方向前必查）

> **【2026-08-03 注】M1 时点快照，多处已被推翻**：KTD1「不引 Compose」（KD11 推翻）、
> 「App=控制台定位」（KD9 推翻）、KTD5「M1 实做 288dp」（偏差 A 已改 208）、
> KTD3/KTD4/KTD7 标「U5/U6 待做」（已在助手化 S0/S1 落地）。现行决策以根目录
> `CLAUDE.md` 层级 + plan 002（KD9-KD16）+ `implementation-notes.html` 为准，本表仅历史溯源。
>
> 来源：`docs/plans/2026-07-29-001-feat-xiaoduo-android-app-plan.md`（用户已确认的 unified plan）、
> `docs/plans/M1-plan.md`（2026-07-28 grilling 共识）、wayfinder map/tickets。
> 本文件是索引+摘要；条文全文以 plan 文档为准。

## 1. 产品级拍板（不再重议）

| 决策 | 结论 | 出处 |
|---|---|---|
| 形态 | 原生 Kotlin 单 App（Tauri 无 overlay 概念、Chaquopy 无音频后端，均已闭环否决） | 知识包 §1 / KD1 |
| 仓库 | 独立新仓 `E:\desk-pet-android`；桌面仓只读（仅知识包文档允许回写） | KD2 |
| 内网接入 | 目标手机能接内网；**但不同站点不同网段 → 端点必须可配置 + 内置默认值** | KD6/R10（决策①细化） |
| 合规成本 | 全接受：前台服务常驻通知 + 麦克风指示点 + 4-5 项权限引导 | 决策② |
| App 定位 | 悬浮窗是主角；App 本体 = 控制台（设置/权限引导/网络配置），首页内嵌预览 | KD3/R6 |
| 链路风险前置 | M1 内插一次性 AG-UI 调试页（多网段/自签/流式第一周上真机排雷）；**不进化成正式对话 UI** | KD4/R11/R12 |
| 交互集 | idle + 点击挥手 + 拖拽；不做自主游走/双击/长按；不做养成玩法 | M1-plan 决策 5 |
| KWS 校准 | gate 可配置 + 校准入口 = **M3 硬性验收**（库房嘈杂 × 0.015 单机校准值） | KD8 |
| 自适应 | 一套布局覆盖手机全比例；平板/折叠屏优雅降级不定制 | KD7/R9 |
| 机型范围 | 不含 HarmonyOS NEXT（纯血鸿蒙风险已排除） | ticket 08 comments |
| 模拟器 | 不可用（BIOS 虚拟化关），全部真机验收 | 环境实测 |

## 2. 技术决策（KTD，plan 的 Planning Contract）

| # | 决策 | 状态 |
|---|---|---|
| KTD1 | 经典 Views + 自定义 PetView，不引 Compose | ✅ M1 已按此落地 |
| KTD2 | SharedPreferences（现有 Prefs 扩展），不引 DataStore | ✅ 已落地 |
| KTD3 | 协议消费器 = 纯 Kotlin 状态机 + JVM 单测，Android 层只做 IO（照 FrameAnimator 模式） | ⬜ U6 待做 |
| KTD4 | 单一 OkHttp 工厂：内网 NO_PROXY 直连 + 自签证书 host 白名单 TrustManager | ⬜ U5 待做 |
| KTD5 | 悬浮窗 M1 按精灵区高度开窗，气泡区 M2 再加 | ⚠️ M1 实做 288dp（偏差 D2，M2 收口） |
| KTD6 | 权限引导 M1 仅悬浮窗强制；通知/电池/自启动列出并标注里程碑 | ⚠️ M1 只做了悬浮窗+ROM 说明位，其余三项的清单页未做 |
| KTD7 | 调试页仅 AG-UI 平台路径（含登录 + token 25min 缓存）；Ollama 路径等 ticket 04 决策 | ⬜ U6 待做 |

## 3. 需求覆盖表（R1-R14）

| R | 内容 | M1 实况 |
|---|---|---|
| R1 | 悬浮窗帧动画，窗口对齐桌面端构成 | ✅（含气泡预留位，见偏差 D2） |
| R2 | 拖拽合规（Android 12+） | ✅（偏差 D1 的修正实现，真机验证） |
| R3 | 点击挥手回 idle；切状态重置动画 | ✅ App 内+悬浮窗均验 |
| R4 | 缩放档位可调，尽量整数倍 | ✅ 滑条 0.5-2.0 连续（非档位，可用） |
| R5 | 设置：缩放/气泡开关/悬浮窗总开关 | ✅（总开关状态未持久化，缺口） |
| R6 | 控制台定位 | ✅ 单 Activity 版；U3 的重构（首页预览+入口列表）未做 |
| R7 | 预览与悬浮窗同渲染同互动 | ✅ 共用 PetView/FrameAnimator |
| R8 | 权限引导四项 + ROM 说明位 | ⚠️ 悬浮窗项完整；其余三项未列出（KTD6 允许 M1 仅悬浮窗强制，但清单页要有） |
| R9 | 一套自适应布局 | ⚠️ 线性布局天然自适应，未做 AE2 双比例核对 |
| R10 | 端点可配置 + 自签白名单 + 内网绕代理 | ⬜ U5 |
| R11 | AG-UI 调试页：发消息看流式原文，错误透传 | ⬜ U6 |
| R12 | 调试页定位一次性排障工具 | ⬜ 随 U6 |
| R13 | 流式消费三件套（终止帧校验/空 run 重试/SNAPSHOT 兜底） | ⬜ U6 |
| R14 | 文件日志设施 | ✅ 基础版；轮转/崩溃捕获/分享出口在 U4 |

## 4. 验收例（AE1-AE6）状态

| AE | 内容 | 状态 |
|---|---|---|
| AE1 | 拖拽不被系统屏蔽 | ✅ 真机 4 次拖拽 + 日志取证 |
| AE2 | 18:9 与 21:9 布局核对 | ⬜ 未做（需第二台不同比例机器） |
| AE3 | 库房网段完成一次 AG-UI 对话见 RUN_FINISHED | ⬜ 挂起条件：端点/机型由用户提供（plan Outstanding Questions） |
| AE4 | 空 run 自动重试不伪装成功 | ⬜ U6 单测+真机 |
| AE5 | 自签证书仅白名单放行 | ⬜ U5 逻辑层单测 |
| AE6 | release 无 console 仅凭文件日志定位故障 | ⚠️ 日志设施已有，注入故障演练未做（U4） |

## 5. Ticket 状态（.scratch/xiaoduo-android/issues/）

| # | 票 | 状态 |
|---|---|---|
| 01 | 环境+仓库初始化 | open（实际已由 M1 任务 0/1 完成，可关） |
| 02 | sherpa-onnx AAR 调研 | ✅ resolved：官方 `sherpa-onnx-1.13.4.aar`（46.6MB 全 ABI）放 app/libs + abiFilters arm64-v8a；模型 newFromAsset 直载；modelType="zipformer2" 兼容现有 5 文件 |
| 03 | ROM 权限引导调研 | ✅ resolved：6 项权限清单 + OEM 跳转表（见 05 文件 §3） |
| 04 | M2 provider 路径优先序（local 免登录 vs platform） | ⬜ open（grilling 待用户答：小朵在手机上首先是陪聊玩具还是平台 Agent 入口） |
| 05 | 气泡+键盘输入形态原型 | ⬜ open（blocked by 01，可解封；关键矛盾：overlay 的 FLAG_NOT_FOCUSABLE 与 IME 输入，备选=输入走 Activity 弹层） |
| 06 | M3 是否含 barge-in 打断 | ⬜ open（grilling；成本=多层过滤+AEC，建议 M3 先半双工） |
| 07 | VAD 参数对账 | ✅ resolved：知识包与桌面 HEAD 全部一致；3 处细节已回写知识包（listen_timeout 生效值 30s 勿照抄类默认 8s；TTS 消毒多一步连续空白压缩；§7.1 #6 历史矛盾澄清） |
| 08 | 分发与装机（签名/通道/更新/机型清单） | ⬜ open（grilling 待用户答） |
| 09 | brainstorm 决策同步 | open（plan 已落盘；PROGRESS/map 同步可视为本手册承接，可关） |
