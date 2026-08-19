# 小朵助手化（对话式 App + 语音悬浮窗）- Plan

artifact_contract: ce-unified-plan/v1
artifact_readiness: requirements-only
product_contract_source: ce-brainstorm
日期：2026-07-30 ｜ 来源：07-30 brainstorm 会话（9 轮逐题拍板 + 2 处后续修订）

## Goal Capsule

把小朵 Android 从「控制台 App + 陪伴悬浮窗」升级为**对话式助手**：App 主界面即聊天（参考 ChatGPT/小布），
仅走平台 AG-UI 链路；悬浮窗对齐手机精灵形态，气泡输出回复，「小朵小朵」唤醒词语音对话（半双工，对齐电脑端）。
终点线：功能全通 + 与参照物（ChatGPT app / 小布助手 / 手机精灵）并排对比不输。
开放阻塞：平台登录凭据（→ `.scratch/secrets/platform-account.json`，用户开工前放置）。

## Problem Frame

库房员工离开工位后无法连平台 Agent 干活（原有 Problem Frame 不变）。M1 证明悬浮窗形态可行，
但「控制台」定位的 App 无法承载对话主业——对话才是用户每天打开 App 的理由。
参照物已证明形态：ChatGPT app = 极简消息流 + 底部输入条；小布 = 按住说话为主输入；手机精灵 = 双窗口悬浮陪伴。

## Key Decisions（本轮新增，编号续 001 plan 的 KD1-8）

| # | 决策 | 推翻/修订 | 理由 |
|---|---|---|---|
| KD9 | **App = 对话主界面**（聊天首页），设置/权限/网络退二级；悬浮窗仍是桌面陪伴 | 修订 KD3 | 用户拍板：参考 ChatGPT/小布，对话是主业 |
| KD10 | **仅平台 AG-UI 链路**（Ollama 彻底取消，后续也不做）；端点可编辑 + 内置默认 | 关闭 ticket 04；收窄 KD6 | 用户拍板：目标就是 Agent 能力，不维护双链路 |
| KD11 | **UI 层 Compose + Material3**；PetView 保留 Canvas 自绘经 AndroidView 嵌入 | 修订 KTD1 | 聊天式 UI（消息流/Markdown/深色/适配）是 Compose 主场 |
| KD12 | **KWS 唤醒词进本轮**（sherpa-onnx + 前台服务常驻 + 校准入口，KD8 硬验收不变）；**长按 = 菜单复活**（桌宠开关/语音开关等） | 推翻 07-30 偏差 B 追认 | 用户拍板：对齐电脑端体验；唤醒词触发使长按解放给菜单 |
| KD13 | **reveal 边念边出字进范围**（句级 PCM 时长均分逐字，降级 4.5 字/秒） | **【2026-08-05 撤销】S8-5 拍板改流式直出** | 用户拍板（二轮修订，补选） |
| KD14 | **终点线 = 功能全通 + 对标不输**：关键界面与三参照物并排截图，严评循环至不逊 | — | 用户拍板 |
| KD15 | 手势集：单击=挥手 / 拖拽=移动 / **长按=菜单** / 唤醒词=语音起手；App 聊天页 = 键盘 + 按住说话按钮 | 落定 ticket 05 | overlay 不做键盘输入（IME 矛盾规避） |
| KD16 | 半双工（说话丢麦块，不可打断） | 关闭 ticket 06 | 对齐桌面端，自激风险最低 |

## Requirements

- R1 聊天主界面：消息流（用户右/小朵左）、Markdown 渲染（粗体/列表/代码块）、底部输入条（键盘 + 按住说话）、发送/生成/失败状态与重发
- R2 历史记录本地持久化（单条流水，杀进程重进可回看，可清空）；~~不做多会话管理~~
  **【2026-07-31 修订，用户拍板】R2 翻案**：多会话进范围——侧栏含搜索会话（标题+消息全文）/
  新建会话/会话列表/长按删除+二次确认，标题=首条用户消息截断，删除接线平台 /deleteThread（N4 已交付）
- R3 AG-UI 链路：登录（凭据可配）→ token 缓存 25 分钟 → SSE 流式对话（**流式消费三件套**：终止帧校验 / 空 run 重试 3 次 / MESSAGES_SNAPSHOT 兜底）；增量单条消息；443 反代 + 自签白名单 + NO_PROXY
- R4 端点设置页：平台地址/ASR/TTS 可编辑 + 内置默认值 + 账号密码输入（密码无默认值，不入库）
- R5 悬浮窗升级：手机精灵式**双窗口**（显示层大窗 NOT_TOUCHABLE + 交互层小窗）+ 气泡输出回复（知识包 §4.2 参数，show_bubble 开关沿用）+ 长按菜单 + 位置持久化/杀进程恢复（吸收 U2）
- R6 语音链路：VAD（§3.4 参数照抄）→ ASR（language="zh" 显式）→ AG-UI → 分句 → TTS（直连 9803）→ 播放；半双工状态机四态；TTS 白名单消毒；气泡显示原句/朗读清洗句
- R7 reveal：句级 PCM 精确时长 `len(pcm)/2/24000` 均分逐字；TTS 失败降级纯文字 4.5 字/秒
  **【2026-08-05 撤销，用户拍板】S8-5 改流式直出**（AG-UI delta 回调直接气泡上屏，与 TTS 解耦），reveal 退役（RevealScheduler 类保留备查）
- R8 KWS 常开：sherpa-onnx KeywordSpotter（threshold 0.5）+ RMS 能量门控（默认 0.015，**可配置 + 校准入口 = 硬验收**）+ microphone 前台服务（常驻通知 + 麦克风指示点，用户可见动作拉起）+ 单条采麦流复用（KWS/VAD 共用）+ KWS pause flag
- R9 适配：深色模式（Material3 主题）+ 多屏幕尺寸/比例（`adb wm size` 模拟验证矩阵）；横屏/平板不定制（KD7 不变）
- R10 四件套持续更新：Spec / CONSTRUCTION.md / DEV_LOG.md / implementation-notes.html（纪律见 AGENTS.md）

## Scope Boundaries

不做：Ollama 任何痕迹；完整 barge-in；横屏/平板折叠屏定制；多会话管理；图片/文件上传；
reveal 以外的高级动效；M4 ROM 加固矩阵；Ollama 降级路径；overlay 键盘输入。

## Acceptance（终点线展开）

- AE-功能：真机端到端——文字对话一轮成功；「小朵小朵」唤醒→说话→气泡+TTS 朗读（reveal 跟读）成功；单测 ≥17 全绿 skipped=0
- AE-对标：聊天页 / 输入区 / 悬浮窗形态 / 气泡 四组并排截图（小朵 vs 参照物），每组严评记录达到「不逊」
- AE-合规：前台服务通知 + 麦克风指示点可见；权限引导文案预解释

## How This Work Fits Together

本轮吸收原路线图的 M2 全部、M3 全部、U2/U3/U5/U6 增补单元（U6 调试页职能由 S1 链路排雷承担，不再独立交付）。
剩余：M4 ROM 加固（保活/多机型）、完整 barge-in、reveal 精调、多端点配置组/扫码导入。
下一个 brainstorm 候选：M4 保活策略；分发与签名（ticket 08 仍开放）。

## Outstanding Questions

| 项 | 状态 |
|---|---|
| 平台登录凭据 | 用户放置 `.scratch/secrets/platform-account.json`（开工前） |
| ticket 08：签名 keystore 保管 / 分发通道 / 目标机型清单 | 开放，M4 前必须答 |
| 库房真机机型 + 该网段实际端点 | 开放，现场部署前提供 |
