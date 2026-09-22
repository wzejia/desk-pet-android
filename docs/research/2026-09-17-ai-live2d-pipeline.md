# 调研：AI 模型能否完成 Live2D 制作全流程

> 日期：2026-09-17 ｜ 方法：浏览器取证（GitHub/Bing/官方 README 原文）
> 关联：`2026-09-17-live2d-feasibility.md`（三关评估）、`2026-09-17-pet-animation-survey.md`（行业路线）
> 结论先行：**能。全流程已有开源拼图，且全部本地可跑**——P0 关键路径「分层 PSD 美术重制」
> 从「必须美术」降级为「AI 生成 + 人工精修可选」，建议 P1 spike 直接采用。

## 1. 全流程拼图（每环节均有开源实现）

```
AI 生图 → 去背景 → AI 分层(20+层) → AI 绑骨(rig) → 导出 .moc3 → Android SDK 驱动
 (成熟)    rembg     See-through      image2live2d     标准格式      Cubism Android
```

### 环节①：AI 生图（成熟，商业产品级）
Nano Banana / GPT Image 2 / Seedance2 / SD 系生成角色立绘——社区已有人用
「Nano Banana + GPT Image 2 + Seedance2」在不会绘画/不会建模的前提下做出 Live2D 桌宠
（公众号/博客实践文，2026）。

### 环节②：AI 分层（See-through，SIGGRAPH 2026 开源）
- 单张动漫立绘 → **23~24 层语义透明图层**，含**遮挡补全**（被前景遮住的头发后层/身体自动生成）
- 深度估计与排序；有 ComfyUI 插件版；定位就是 Live2D 工作流的「拆图地狱」解药
- 社区实测口径：「一键拆分 24 层图像，连后发都帮你补全了」（bilibili，2026-09-14）

### 环节③：AI 绑骨（image2live2d，开源）
- 输入分层图层（PNG 序列或 See-through 产出的 PSD）→ 自动完成 mesh、变形器、绑定：
  **眨眼、口型同步、视线跟随光标、转头、头发&衣服物理**——正是小朵要的「物理常识」全套
- 输出**三种标准格式**：nijilive .inp + **Live2D .moc3 bundle** + **可编辑 Cubism 工程 .cmo3**
- 无头运行 / 批量（带 pass-rate 报告）/ 本地 Web App；「You get a rig, not a baked video」
  ——产出是可交互实时 rig，不是烤死的视频

### 环节④：全流程整合（naiyouj/live2d-ai-generator，开源）
- 把上面全部串成本地 Web 应用：`AI生图 → 预处理(rembg) → AI分层(20+ RGBA) → AI绑骨
  (mesh/物理/动作) → 预览导出 .moc3`，**全本机运行、图片不出门**
- 硬件门槛：RTX 2070 8GB 即可；分层模型三组合计约 12GB（一次性下载）
- 每步可手动接管（可上传现成立绘/PSD/.moc3 跳过），断点续跑
- 网页预览直接用 Cubism Core + pixi-live2d-display（与 Open-LLM-VTuber 同款渲染栈）

## 2. 对小朵 Live2D 路线的影响

| 原三关评估 | AI 流程后的变化 |
|---|---|
| 关1 素材：分层 PSD 必须美术重制（关键路径） | **大幅降级**：AI 生图+AI 分层产出分层资产；人工精修变为可选质量增强 |
| 关2 工程集成：不变 | 不变（.moc3 进 Cubism Android SDK 的路线原样成立） |
| 关3 装机性能验收：不变 | 不变（模型复杂度可能更高，性能验收更必要） |

**修订后的 P1 spike 建议**：在 PC（RTX 5060 Ti 16GB，显存充裕）跑
`live2d-ai-generator` 全流程生成试验模型 → 产出 .moc3 直接进 Cubism Android SDK 集成 →
质量不足处用 .cmo3 进 Cubism Editor 人工精修（可编辑工程是人工兜底入口）。
原 P0 的「委托美术分层 PSD」转为 Plan B（质量不满意时再启用）。

## 3. 必须核对的局限与许可（诚实清单）

1. **质量分级**：AI 分层的遮挡补全是生成件（头发后层/身体细节可能有瑕疵）；AI 绑骨的
   mesh/物理是通用模板（眨眼/口型/视线/转头/发衣），精致度与老练模型师手工绑定有差距——
   「能用可验收」vs「精致」需 spike 实测定级。
2. **许可逐一核对**：see-through 与 image2live2d 的开源 license（商用条款）；生图产品
   生成物的商用权（Nano Banana/GPT Image 服务条款差异大）；**游戏提取模型不可商用**的老
   坑同样适用于 AI 生成物的训练数据争议面。
3. **定制上限**：小朵要的「VSM 四态→动作语义映射」仍需参数级调整（.cmo3 可编辑=入口存在），
   AI 绑骨不会替你做产品语义。
4. **一致性风险**：AI 生图的角色多视角/多表情一致性是已知难点（立绘一张 vs 动画多态），
   spike 时重点验证。

## 4. 来源清单

1. GitHub `naiyouj/live2d-ai-generator` README（v2）——五阶段全流程、本地运行、8GB 显存、
   人工接管点、Cubism Core+pixi 预览栈
2. GitHub `Wzhang3912/image2live2d` README——单图→rig（眨眼/口型/视线/转头/发衣物理）、
   .inp + .moc3 + .cmo3 三格式、批量与 Web App
3. Bing 多源——See-through（SIGGRAPH 2026）单图拆 23~24 层 + 遮挡补全 + ComfyUI 插件；
   bilibili 实测（2026-09-14）「一键拆分 24 层」
4. 博客实践文——Nano Banana + GPT Image 2 + Seedance2 制作 Live2D 桌宠全记录
