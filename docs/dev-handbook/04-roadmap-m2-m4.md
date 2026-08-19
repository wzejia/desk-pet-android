# 04 · M2-M4 路线与单元缺口表

> **【2026-08-03 注】M2/M3 已由助手化大切片（S0-S6，plan 002）吸收并交付完毕**：
> U2 持久化三件套（S3）、U5/U6（S0/S1）、M2 对话链路（S1-S3）、M3 语音链路（S4/S5）
> 均已收口。本表仅历史溯源；M4 ROM 加固仍指向未来（见 09 后半）。
>
> 上游：unified plan 的 U1-U6（M1 增补）与 §9 路线；知识包 §2/§3/§5 是协议与参数单一事实源。
> 开工顺序建议：先补 M1 缺口（U1-U6），再 M2。

## 1. M1 增补单元缺口表（plan 已定案，按依赖排序）

| 单元 | 内容 | 依赖 | 现状 |
|---|---|---|---|
| U1 | 权威文档/素材镜像 + **PetFrameTableContractTest**（解析 assets 里 pet_states.json 与 PetFrameTable 逐行比对） | 无 | ✅ **已完成（2026-07-30）**：pet_states.json 已进 assets（SHA256 `914b11d7…8e12` 与上游一致）+ 契约测试 5 条全绿（含 _meta 常量/default/逐项 row-frames-duration/无多余状态/行界合法性） |
| U2 | 悬浮窗补缺口：位置持久化（clamp 纯函数可单测）、总开关状态持久化、杀进程恢复 | U1 | ⚠️ 主体已建，缺持久化三件套 |
| U3 | 控制台三件套重构：首页预览+入口列表 / SettingsActivity / PermissionGuideActivity（四项清单） | U1 | ⚠️ 单 Activity 版可用，未拆页 |
| U4 | 日志轮转（按天+上限）+ CrashReporter + 设置页分享出口 | U1 | ⚠️ **滚动+导出已完成（2026-07-30，偏差 D 裁决实施）**；CrashReporter 未做 |
| U5 | 网络配置面：NetConfig（5 端点+账号可编辑，内置默认值）+ OkHttp 工厂（NO_PROXY+白名单 TrustManager）+ TokenStore（25min） | U3 | ⬜ |
| U6 | AG-UI 调试页：AguiSseConsumer（纯 Kotlin 三件套）+ AguiClient + DebugChatActivity | U5 | ⬜ |

验收门：JVM 单测全绿 / assembleRelease 警告清零 / 真机本地段 F1-F2+AE1/AE2/AE6 / 链路段 F3+AE3-AE5
（库房端点未提供则 AE3-AE5 挂起并记录，不阻塞开发）。

## 2. M2 对话链路（Kotlin providers + 气泡 + 键盘输入）

**开工前必须先答的票**：ticket 04（provider 优先序）、ticket 05（气泡/输入形态原型）。

技术要点（全部来自知识包，实现时逐项对照，勿凭记忆写）：

- **LLM 两条路径**：
  - Ollama（prefer=local）：`POST {base}/api/chat`，`think:false` + `num_ctx:2048` + `num_predict:100`，
    NDJSON 流（每行 JSON 取 `message.content`，`done==true` 终止）；**免登录**，首 token ~1s。
  - 平台 AG-UI（prefer=platform）：必须走 **443 nginx**（:8080 直连会 401 假象）；自签白名单；
    **增量单条消息**（重发全量历史 → 空回复率 ~50%）；消息 id 全局唯一、重试复用同 id。
- **流式消费三件套（铁律，知识包 Android 第 1 条）**：
  ① 未见终止帧（RUN_FINISHED / done）= 断流，**显式报错**，绝不伪装 0 字成功；
  ② 累计 0 字 = 平台随机空 run（~20%），自动重试共 3 次；
  ③ MESSAGES_SNAPSHOT 兜底：末条 assistant 文本比已产出长则补尾部（content 兼容 string 与分片）。
- **气泡**：知识包 §4.2 参数（精灵正上方、max-height 74px 裁切、圆角 10px、rgba(255,255,255,0.92)、
  11px×font-scale、打字机 40ms/字）；`show_bubble` 总开关 M1 已存配置。
- **对话状态→动画行映射**：listening→waiting / processing→review / speaking→talking / idle→idle
  （别名 auto→jumping、sleeping→idle、running→idle），切换重置动画——FrameAnimator 已支持。
- **IME 矛盾**（ticket 05 核心）：overlay 窗口 FLAG_NOT_FOCUSABLE 与软键盘输入冲突，
  备选方案 = 输入走 Activity 弹层而非 overlay 本身。
- 配置面 15 项按知识包 §6.2，平台密码**无默认值**。

## 3. M3 语音链路（VAD/半双工/TTS 分句/KWS/前台服务合规）

**开工前必须先做**：参数对账表逐项对照（ticket 07 已做一轮，产出
`docs/voice-param-reconciliation-2026-07-28.md`，含 M3 Kotlin 验收清单）；ticket 06（barge-in 范围）。

- **VAD 最小解**（照抄 §3.4，别加自适应层）：level≥0.05 绝对阈值 / 判起 3 块（300ms）/ 判停 8 块
  （800ms）/ 只累积说话块 / pre-roll deque 容量 = 判起 3 + pre-roll 3 = **6**（同长则保首字失效，踩过两次）。
  生效 timeout = **30s**（sidecar 显式传值，勿照抄类默认 8s）。
- **半双工**：四态 idle/listening/processing/speaking + 状态门控分发 + KWS pause flag +
  单条采麦流复用（KWS 与 VAD 共用）。
- **TTS 链路**：分句只用 `。！？`（+200 字符 flush），整段必须**逐字符**喂分句器；
  text 分句成**数组**送流式（整段单元素=伪流式首帧 17.8s）；**直连 9803 勿经 166 转发**
  （30s 总时长截断）；多帧 WAV 剥头拼 PCM；先缓冲全帧再播；合成前白名单消毒（含连续空白压缩，
  ticket 07 补注），气泡显示用原句、朗读用清洗句。
- **ASR**：`language` 必须显式 `"zh"`（auto 中韩误判）；serviceName=`sensevoice`/`FunASR`；
  VAD 截尾防静音幻觉。
- **KWS**（ticket 02 已调研）：sherpa-onnx-1.13.4.aar 放 app/libs + abiFilters arm64-v8a；
  模型 5 文件进 assets 用 newFromAsset；`keywordsThreshold=0.5`；RMS 能量门控 0.015 是单机校准值
  → **可配置 + 校准入口是 M3 硬验收（KD8）**；音频回调只推队列、decode 在消费线程（协程 channel）。
- **合规**：microphone FGS（Android 14 强制类型声明）必须由用户可见动作拉起；常驻通知 +
  麦克风绿点不可隐藏 → 引导文案预解释；采集优先 `VOICE_COMMUNICATION`（硬件 AEC）。
- **reveal**：句级 PCM 精确时长 `len(pcm)/2/24000` 均分逐字；降级纯文字 4.5 字/秒。

## 4. M4 ROM 加固（保活/耗电/多 ROM 真机）

- 保活组合：前台服务 + START_STICKY + 电池豁免 + 厂商自启动白名单——**无 100% 技术解**
  （知识包 §8 最大不确定性），投入深度届时定。
- OEM 跳转表见 05 文件 §3（全部 try/catch 回退应用详情页，真机逐项验证）。
- 耗电：闲置降帧/熄屏暂停——等 overlay 有实测基数再定（map 迷雾区）。
- 多 ROM 真机矩阵：依赖 ticket 08 机型清单。

## 5. 未决问题汇总（需要用户回答的）

1. ticket 04：M2 provider 先 local（免登录低延迟）还是 platform（Agent 能力）？默认哪条？
2. ticket 06：M3 含不含 barge-in？
3. ticket 08：签名 keystore 谁保管 / 分发通道 / 目标机型清单？
4. plan Outstanding：库房真机机型 + 该网段实际端点（F3 执行前提供，否则 AE3-AE5 挂起）。
