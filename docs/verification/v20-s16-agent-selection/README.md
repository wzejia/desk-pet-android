# v20 - S16 平台智能体选择（2026-08-29 模拟器验收）

## 功能

设置页「端点与账号」区新增「平台智能体」选择器：点击展开拉取 `/agent/list`
（443 反代 `https://10.0.18.166/ai-server/agent/list`，登录态复用 AguiClient 链路），
列表单选（名称+描述+Radio），选择即存 Prefs（agentId=请求头路由 + 显示名离线缓存）。

**语义（用户 2026-08-25 拍板）**：agentId 仅请求头路由（不同 agent = 不同提示词/MCP），
thread 连续性与本地历史不动--切换后对话继续、历史可查看（桌面端同语义升级为列表选择）。

## 实现要点

- `AguiClient.agentId` 构造常量 -> `agentIdProvider: () -> String` 动态 provider
  （chat/delete/rename/upload 头与 createThreadId body 全部现读，切换即时生效无需重建 client）
- `AguiClient.listAgents()`：POST 空 body + Bearer，信封 code==200 data.list[]，
  401 透明重登 1 次（与 deleteThread 同模式）；脏数据容错（缺 agentId 跳过、name 缺失回退 id）
- `NetEndpoints.agentListUrl`：443 反代范式（2026-08-25 Python 探针实测 9 agent，
  与 :8080 直连返回一致）
- Prefs 键：`platform_agent_id`（默认 chatAgent）/ `platform_agent_name`

## 测试

- 461 -> **467 绿 skipped=0**：AguiClientAgentListTest ×4（信封解析+请求契约 /
  401 透明重登 / 信封错误透传 / 动态 provider 逐请求取新值--header 与 body 双点断言）；
  NetEndpointsTest +2（443 反代 / host 不可解析回退）
- `assembleDebug` 绿

## 模拟器证据（emulator-5554）

| 截图 | 内容 |
|---|---|
| `s16-agent-list.png` | 展开列表：9 agent 渲染（年报助手/环控/知识库/C款密集架语音助手/langflow测试/小朵/…），日志链 `login ok -> agent/list POST https://10.0.18.166/ai-server/agent/list` |
| `s16-agent-selected.png` | 选中「环控」：`Settings: agent selected -> hkChatAgent` |
| `s16-agent-persisted.png` | force-stop 重启后设置页显示「当前：环控」（持久化 ✓） |
| `s16-agent-reply.png` | 换 agent 后发消息：`run POST .../agent/stream threadId=eebc...（复用）-> RUN_FINISHED ok, reply 337 chars`，回复内容为温湿度/安防/无人库房域（环控知识域） |

## 待真机现场（用户）

切 agent 后语音对话回复风格变化（提示词/MCP 生效主观感受）。
