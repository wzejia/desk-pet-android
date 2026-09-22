# Live2D 宠物形象可行性评估与路线图

> 日期：2026-09-17 ｜ 状态：方向已定（用户拍板「想要更生动的形象」），待素材/授权到位后立票
> 关联：S13（2026-08-24 静止化拍板，本方向部分修订之）、S34/S35（CPU 调度红线）、S25b（悬浮窗动画期 Choreographer 观察）
>
> **【2026-09-17 晚更新：AI 全流程调研改变了 P0/P1】**——「能否用 AI 模型完成全流程」结论：
> **能**。开源拼图已齐：AI 生图 → See-through 分层（SIGGRAPH 2026，单图 23~24 层+遮挡补全）→
> image2live2d 绑骨（眒眼/口型/视线/转头/发衣物理，输出 .moc3+.cmo3）→ 本地整合应用
> （live2d-ai-generator，8GB 显存可跑）。**P0「分层 PSD 美术重制」从必须项降级为 Plan B**
> （质量不满意再启用）；修订后 P1 spike = PC 跑 AI 流程生成试验模型 → 直接进 Cubism
> Android SDK 集成，质量不足用 .cmo3 人工精修。许可核对清单与局限见
> `2026-09-17-ai-live2d-pipeline.md`（生图商用权/工具 license/生成件质量分级/一致性风险）。
> Editor PRO 授权可待 spike 质量定级后再决定是否仍需要。

## 0. 决策记录

- **2026-09-17 用户拍板**：宠物动画方向选定为 Live2D（诉求=更生动的形象：呼吸/视线/物理微动）。
- **对 S13 的影响**：S13「循环态全部静止、仅说话 6 帧」是性能与体验的临时折中；Live2D 引入后
  **静止化语义将由新动效映射表取代**（各状态表现逐项重新拍板，见 §5 阶段 3）。替换素材、
  保留 S13 其余产物（帧表结构退役为 Sprite 回退路径）。

## 1. 现状动画栈（基线事实）

- 素材：`assets/spritesheet.webp`（1536×1872，帧 192×208，逐帧精灵图，与桌面端同构）
- 帧表：`PetFrameTable`（纯 Kotlin）+ `pet_states.json`；渲染：`PetView`（Canvas.drawBitmap 切帧）
- 挂载：悬浮窗双窗口（交互层+NOT_TOUCHABLE 显示层）+ 全屏页 Compose AndroidView 互操作
- 板子 rk3568：Mali-G52（GLES 3.1 ✓）；**语音链对 CPU 调度极敏感**（S34 双引擎 202% CPU=撕裂、
  S35 kws-decode 饿死=唤醒必漏、S25b 悬浮窗动画期 Choreographer 白屏观察）

## 2. 技术可行性

| 项 | 结论 |
|---|---|
| SDK | Cubism SDK for Android（Native core C++ 静态库）；**arm64-v8a 与 x86_64 官方均提供**——现有双 ABI（板子+模拟器）都可跑 |
| GPU | Mali-G52 GLES 3.1，Cubism 需 GLES 2.0+ ✓；简单模型 30fps GPU 无压力 |
| 渲染挂载 | 需 GL 上下文：GLSurfaceView/TextureView+EGL；全屏页 Compose 互操作有 S28 先例坑（interop 事件）需规避 |
| 口型驱动 | 真优势项：VoicePlayer 播放进度 → `ParamMouthOpenY`，说话口型与 TTS 实时同步（帧动画做不到） |
| 包体 | SDK+模型 约数 MB，可接受 |

## 3. 三关

### 关 1：素材与授权（用户侧准备，关键路径）
- **分层 PSD 重制角色**：头发前后层/眉/上眼睑/瞳孔/嘴（上唇下唇口腔）/身体/饰品，分层命名
  对齐 Cubism 参数规范。**当前逐帧精灵图不能转换，只能重做**——这是全案最大成本与前置条件。
- Cubism Editor：FREE 版有功能限制（网格数/参数），正式制作需 **PRO 授权**（付费）。
- **Live2D 出版许可**：商用发布需签「Live2D 出版许可协议」（小规模免费额度以上签约）；
  企业内部部署亦建议法务过一遍条款。官方示例模型（Hiyori 等）仅限集成验证，不可随产品分发。

### 关 2：工程集成（素材未到位前可用示例模型先行 spike）
- 抽象 `PetRenderer` 接口：`SpritePetRenderer`（现状，保留为回退路径）/ `Live2DPetRenderer` 新增
- 会话四态（idle/listening/processing/speaking）+ 唤醒/打断事件 → Live2D 参数/表情/动作映射层
- 降载策略（红线前置内建）：参数静止即停帧（不做无谓持续渲染）、锁 30fps、渲染线程优先级
  收敛（对齐 S35 口径：不得挤压 kws-decode 的 priority 6）、模型加载放 worker 线程

### 关 3：装机性能验收（硬门槛，不达标回退 Sprite 版）
- 既有方法论三件套：忙环注入争抢 + `kws stats` fed−decoded diff 水位 + Choreographer 长测
- 验收口径：语音全链（唤醒/说话/打断）期间 diff ≤ 常态水位、Choreographer 零报警、
  唤醒率无回归（对照 09-12 后基线）

## 4. 权衡：与桌面端形象分叉

桌面仓（只读）仍为 spritesheet 逐帧形象。Android 转 Live2D 后两端形象不一致——知识包「双端
同构」语义在此项上打破。若要求两端一致，桌面端需同步换 Live2D（另案评估，本票不覆盖）。

## 5. 分阶段路线图

| 阶段 | 内容 | 出口条件 | 前置 |
|---|---|---|---|
| P0 准备 | 分层 PSD 委托/绘制；Editor PRO + 出版许可确认 | 素材到位 + 授权明确 | 用户/美术 |
| P1 spike | 示例模型集成 SDK；PetRenderer 抽象；悬浮窗+全屏 GL 挂载；降载策略内建 | 模拟器示例模型四态可切换 | 无（可先行） |
| P2 性能验收 | 装机三件套实测（示例模型即可） | §3 关 3 口径全过 | P1 |
| P3 正式模型 | 正式模型导入；各状态动效映射**逐项用户拍板**（修订 S13 语义）；口型接 TTS | 板上四态+口型验收 | P0+P2 |

## 6. 需要用户拍板/提供

1. 分层 PSD 素材来源（现有美术是否可产出 Cubism 规范分层稿？）
2. Cubism Editor PRO + 出版许可预算/法务确认
3. 动效期望清单（哪些状态要微动、幅度上限——P3 逐项拍板的底稿）
4. 两端形象分叉是否接受
