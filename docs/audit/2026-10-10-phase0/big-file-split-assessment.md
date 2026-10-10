# 大文件拆分评估（Phase 2 交付，2026-10-10，Explore 只读侦察）

> spec §4.5/§6 Phase 2 交付物。口径：只按已存在内聚区块聚合搬移，禁止顺手重写；每票独立提交、独立过测试。
> 实测 LOC：VSM 1329 / SettingsScreen 1099 / ChatViewModel 928 / AguiClient 873 / PetOverlayService 868。

## 施工票排序

| 优先级 | 票 | 产物 | 估行 | 风险 | 批次 |
|---|---|---|---|---|---|
| P1 | Settings×5 | ui/screens/Settings{EndpointEditor,AgentPicker,SelfCheck,Kws,Widgets}.kt | 205/120/135/195/60 | 极低 | **Phase 2 做** |
| P2 | Overlay 气泡 | ui/overlay/OverlayBubbleHub.kt | ~255 | 中（真机回归） | 挂下次部署批 |
| P3 | Agui 认证 | agui/AuthGate.kt | ~120 | 低 | Phase 3 |
| P3 | VSM 续听 | voice/pipeline/ContinuationWindow.kt | ~110 | 中（缝委托） | Phase 3 |
| P3 | VSM 盲区 | voice/seam/BlindWindowBackfiller.kt | ~90 | 中（缝委托） | Phase 3 |
| P4 | VSM TTS 分块 | voice/pipeline/SentenceTtsPlayer.kt | ~80 | 中 | 按需 |
| 不立项 | ChatViewModel / VSM 四态与采麦 / Agui 主链与 Result / PetOverlayService 双窗与桥 | — | — | 拆=传递私有依赖（§4.5 排除条件） | — |

## 关键结论摘录

- **ChatViewModel（928）不拆**：纯逻辑已全部外置（10+ 兄弟文件各有 JVM 单测），剩余为 IO+接线薄编排层；各区块共享私有五件套（_messages/_phase/turnEpoch/chatJob/activeAssistantId + persist），拆任何一块都传递私有依赖。
- **SettingsScreen（1099）5 票全可拆**：区块全部是同文件 private @Composable、无共享可变状态；沿用 ui/screens 平铺兄弟文件先例（AecSpikeSection），不建 settings/ 子包。拆完主文件 ~430。
- **PetOverlayService（868）**：气泡 API+指示泡（~255）可拆为 OverlayBubbleHub（ctor 注入视图 supplier/Prefs 读口，Service 留同名委托→六调用方零改动）；双窗构建/滑动动画/companion 桥不拆（生命周期强绑定）。`instance` companion 与三静态桥禁止随迁。
- **AguiClient（873）**：仅认证块可拆（AuthGate，ctor 注入，8 测试零改动）；thread 管理/upload/agent 列表共享私有原语不拆；Result 密封类型不动（嵌套限定名引用面大，kotlin 无嵌套 typealias）。
- **VSM（1329）**：三票可拆（续听窗/盲区回灌/TTS 分块，各 ~80-110 行落 pipeline/seam 子包），15 个测试文件直引的 internal 缝必须留 VSM 面上转委托；四态迁移/采麦调度/对话管线为共享枢纽不拆。
- 通用硬验收：每票提交后全量测试绿；VSM 三票「测试零改动」；纯搬移+参数化不改行为。

（评估全文含区块行号盘点与风险点，见本文件同目录评审记录；施工票引用本表优先级。）
