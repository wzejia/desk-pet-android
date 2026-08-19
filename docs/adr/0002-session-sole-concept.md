# ADR 0002: 会话为唯一概念，废弃独立聊天记录页（grilling 会话 2026-08-03）

## 状态

已接受（#10「聊天记录 vs 会话管理」逐项 grill 确认）

## 背景

多会话已上线（推翻契约 R2「不做多会话管理」），但单会话时代的「聊天记录页」(ChatHistoryScreen)
角色模糊：它显示当前会话概览 + 清空，与 SessionDrawer 的会话删除重叠，且造成「聊天记录 vs 会话
管理」术语混淆——数据层 `ChatHistoryCodec` 编的其实就是某会话的消息列表，二者是**包含关系非并列**。
同时用户新增会话重命名需求，需确定标题权威模型；查证发现 Windows 版（平台权威，list/rename 全
走平台 API）与 Android 现状（本地权威 + 离线优先）架构冲突，不能照搬。

## 决策

1. **会话为唯一领域概念**：废弃独立的「聊天记录」概念与 `ChatHistoryScreen` 页面。看历史＝进入
   会话（ChatScreen），删除＝删除整个会话（侧栏长按）。术语已收敛写入 CONTEXT.md。
2. **只做会话级操作**：删除整个会话（已有）/ 重命名（新增）/ 单击进入（已有）。**不做消息级
   操作**（清空当前会话、编辑/删除单条、重新生成 AI 回复）——对齐 ChatGPT/小布主流范式，避免
   AG-UI「增量单条消息 id、重试复用同 id」语义被破坏。
3. **入口范式 (b)**：会话项长按弹操作菜单（重命名/删除，删除仍二次确认），单击进入会话。替代
   原「长按直接弹删除确认」，改善发现性，并为重命名提供统一入口。
4. **会话标题本地权威 + best-effort 平台同步（路线 C）**：重命名＝本地改 title（`ChatSession`
   加「手动命名」标志，`saveMessages` 跳过对其的 `deriveTitle`，防覆盖）+ fire-and-forget 调
   平台 `/renameThread`（失败只 log 不回滚）。与现有 `deleteSession` 模式（本地权威 + 异步删
   平台 thread）完全对称。**不采用** Windows 版的「平台权威」（list 从平台拉 title）——违背
   Android 离线优先架构。

## 备选方案与取舍

- **路线 A**（完全平台权威，list 也从平台 `/threadHistory` 拉）：跨端强一致，但大改 Android
  架构、牺牲离线可用性。否决。
- **路线 B**（纯本地重命名，完全不调平台）：最简，但跨端零同步。C = B + best-effort 平台同步。
- **保留 ChatHistoryScreen 赋新职责**（跨会话总览/批量管理）：增加维护面与术语混淆。否决。
- **消息级操作**：用户未要；AG-UI 消息 id 语义 + 主流只读。明确不做。

## 后果

- **待删**：`ChatHistoryScreen`、`AppNavHost` 的 `CHAT_HISTORY` 路由、`SessionDrawer` 的「聊天记录」入口。
- **保留**：`ChatHistoryCodec`（它编解码 `List<ChatMessage>`，会话消息文件 `{id}.json` 仍用它，仅名字历史遗留，不影响）。
- **待改**：`ChatSession` 加手动命名标志；`ChatStore.saveMessages` 跳过已手动命名的 `deriveTitle`；
  `AguiClient` 加 `renameThreadForSession`（对称 `deleteThreadForSession`）；`ChatViewModel` 加 `renameSession`。
- **跨端一致性**：Windows/Android 为 best-effort（平台尽力同步），非强一致——接受。
