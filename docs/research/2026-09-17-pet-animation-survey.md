# 调研：AI 陪伴宠物/桌宠的形象动画实现方式（对标 Grok Companions）

> 日期：2026-09-17 ｜ 方法：浏览器取证（Bing/Google/GitHub/官方文档，原文快照见文末来源）
> 关联：`2026-09-17-live2d-feasibility.md`（本调研为 Live2D 拍板的行业佐证与实现参照）
> 名词澄清：「grokbot」= xAI **Grok 的 Companion 虚拟陪伴角色**（Ani/Valentine 等，Grok App 内的
> 动画 AI 伴侣）——调研基准样本。

## 1. 样本盘点（实测取证）

| 产品/项目 | 形态 | 动画技术 | 口型/表情驱动 | 证据 |
|---|---|---|---|---|
| **Grok Companions（Ani 等）** | AI 陪伴 App 内嵌 | **3D 实时渲染**（rigged 3D 角色，带 Pro Rig + 面部绑定；社区已从 App 提取出完整 3D 模型并转出 MMD .pmx）；由专业动画团队 Animation.Inc 制作 | 情感化语音 + 动态 3D 动画，情绪驱动表情 | myxmr.com 报道（Animation.Inc=「Grok Companions 项目中 3D 角色与动画效果的主要制作」）；BlendAtlas/社区模型页（「rigged 3D character model … Pro Rig, MMD .pmx with Face Rig」） |
| **Open-LLM-VTuber**（开源，最贴近小朵架构） | 语音对话全链（ASR→LLM→TTS）+ 桌宠/直播 | **Live2D**（Cubism 3~5），Web 前端 **pixi-live2d-display-lipsyncpatch**（Pixi.js WebGL + 口型同步补丁） | **lipsync patch=音频驱动口型**；情绪→expression/motion 映射在 model3.json 配置 | 官方文档 open-llm-vtuber.github.io Live2D Guide |
| **Desktop Mate / 桌面灵 等 Live2D 桌宠生态** | 桌面陪伴 | **Live2D**（用户可导入自备模型） | 模型自带表情/动作组 | Bing 检索多源（桌面灵 README、Electron+PIXI+Live2D 开源项目等） |
| **VPet / Shimeji 类** | 传统桌宠 | **逐帧精灵图 + 状态机**（与小朵现状同路线） | 无口型同步（动作切换式） | 各项目 README（公知实现） |
| **Codex Pets**（OpenAI，2026-05） | 编码 agent 桌面 App（macOS）内嵌桌宠 | **像素风逐帧动画 + 状态机**（官方示例「Meme」像素小狗；8 只内置崽；渲染层实现未官方披露，按像素美术形态与三状态切换机制推断为帧序列动画） | 无口型；**宠物=agent 状态指示器**：三状态 Running / Waiting for input / Ready for review + active thread + 进度提示（「灵动岛」思路）；`/pet` 唤醒浮动 overlay、`/hatch` 孵化自定义（本地 Codex home 加载，社区数小时 467 只自制） | akiraxclaw.com 评测（功能/状态机/像素小狗/自制流程）；Bing 检索多源（知乎/博客快照） |
| **Live2D 手游阵营**（碧蓝航线/公主连结 等） | 移动端 | **Live2D** 立绘动效 | 音频/剧情驱动 | Live2D 官方维护「商业作品数据库」（Live2D Database）+ 官网自称「industry standard tool for 2D real-time expression」 |

## 2. 四条技术路线归纳

1. **3D 实时渲染**（Grok Companions）：表现力上限最高（转身/视角/物理），但需 3D 建模+绑定+
   引擎（Unity/自研），制作成本与团队体量远超 Live2D——重资本路线，商业头部专属。
2. **Live2D（2D 立体绑定）**：AI 陪伴/桌宠品类**主流选择**（开源全链项目与商业桌宠一致选它）；
   单一原画分层绑定即得「伪 3D」微动（呼吸/视线/口型），移动端经手游大规模验证。
3. **2D 骨骼动画**（Spine/DragonBones）：Live2D 平替，游戏角色常用；但 AI 陪伴品类生态/模型
   供给远少于 Live2D（VTuber/桌宠社区模型几乎全在 Live2D 格式）。
4. **逐帧精灵图 + 状态机**：小朵现状。成本最低、调度最轻，但表现力封顶（无连续微动/口型同步）。

**口型驱动的两种实现**：① Web 系（Open-LLM-VTuber）用 pixi-live2d-display 的 lipsync patch
——按音频 RMS 能量实时驱动 `ParamMouthOpenY`；② 原生系（手游/App）直接在渲染循环里喂
音频能量/音素——小朵 Cubism Android SDK 集成后在 `VoicePlayer` 播放回调里做 RMS→口型参数
即可，两种思路等价。

## 3. 对小朵的结论（佐证 09-17 Live2D 拍板）

1. **Live2D 是本品类的主流与最优性价比路线**：语音陪伴全链开源项目（Open-LLM-VTuber）与
   商业桌宠（Desktop Mate 等）一致选 Live2D；Grok 的 3D 路线依赖专业动画团队，不适用当前体量。
2. **可直接借鉴的成熟模式**：
   - 情绪/状态 → expression + motion 的**配置驱动映射**（model3.json），对应小朵 VSM 四态
     + 唤醒/打断事件，无需硬编码动作；
   - 音频能量→口型的 lipsync 思路（Open-LLM-VTuber 的 patch 即此实现），移植到 Android
     Cubism SDK 场景 = VoicePlayer 播放回调算 RMS 驱动 `ParamMouthOpenY`；
   - **模型来源生态**：Booth / Bilibili / 模之屋 / Eikanya·Live2d-models（GitHub 上千个模型仓库）
     ——P0 素材未定制前，可先用**可商用授权的现成模型**跑通集成（注意逐一核对模型许可，
     游戏提取模型不可商用）。
3. **产品语义的高价值参照（Codex Pets）**：把桌宠做成「后台 agent 的可视化状态」——宠物
   表情/动作实时反映任务态（跑任务/等输入/待审阅）。小朵已同构（VSM 四态→帧表），Live2D
   化后该语义可升级为更细粒度的任务反馈（如处理期微动作差异、说话口型=任务语义的一部分）。
4. **风险不变项**：Live2D 常驻渲染线程 vs 语音链调度争抢（rk3568 红线）仍须装机三件套验收；
   Open-LLM-VTuber 的 Web 前端不构成 Android 原生性能参照，Cubism Android SDK 侧仍以
   `2026-09-17-live2d-feasibility.md` 的验收口径为准。

## 4. 来源清单

1. myxmr.com《Grok 告别 AI 伴侣：独立应用 Animates 即将接管 Ani 等虚拟角色》（2026-08-30）
   ——Companions 为 3D 角色、Animation.Inc 为其 3D 角色与动画制作方
2. BlendAtlas / 社区模型页——「Grok Ani Companion AI Model：rigged 3D character model …
   simple and Pro Rig, MMD .pmx with Face Rig」（App 内 3D 实时渲染的旁证）
3. apifox.com《如何使用 Grok 同伴模式并与 Ani 对话》——「互动式 3D 对话」
4. Open-LLM-VTuber GitHub README + 官方文档 Live2D Guide
   （open-llm-vtuber.github.io/en/docs/user-guide/live2d/）——pixi-live2d-display-lipsyncpatch、
   Cubism 3~5、模型配置与来源渠道
5. Bing 检索快照——Desktop Mate/桌面灵/Electron+PIXI Live2D 桌宠生态多源
6. live2d.com 官网（「industry standard tool for 2D real-time expression」）+ Live2D Database
   （官方商业采用作品数据库）
7. akiraxclaw.com《OpenAI 把 Tamagotchi 塞進 Codex》（2026-05-01）+ 中文快照多源——
   Codex Pets 功能形态：`/pet`·`/hatch`·Cmd+K、像素小狗 Meme、三状态指示器、本地
   自定义加载与 467 只自制版
