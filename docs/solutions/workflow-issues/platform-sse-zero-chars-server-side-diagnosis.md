1	---
2	title: 平台 SSE 65s 空转零字符——靠"同会话云路径正常 + 连接建立但零事件"判定为服务端上游问题
3	date: 2026-08-13
4	type: solution
5	category: workflow-issues
6	module: app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt
7	problem_type: diagnosis_methodology
8	component: platform-llm
9	symptoms:
10	  - "LLM 切平台后发消息，等 ~65s 后报 SSE 断流: stream ended without RUN_FINISHED (produced 0 chars)"
11	  - "login、createThreadId、run POST 全成功（网络通），但 SSE 流全程零事件零字符"
12	root_cause: server_side_upstream_unresponsive
13	resolution_type: no_app_change
14	severity: info
15	tags: [platform, agui, sse, diagnosis, server-side, timeout, zero-chars, nginx-reverse-proxy]
16	---
17	
18	# 平台 LLM 零字符：用三段证据判定服务端上游问题，别改 app
19	
20	## Problem
21	
22	LLM 模式从云端切回内网平台后，发消息等 ~65s 报错 `SSE 断流: stream ended without RUN_FINISHED (produced 0 chars)`。login、createThreadId、run POST 都成功（说明网络通、鉴权通），但 SSE 流从头到尾零事件零字符，65s 后断开。
23	
24	## Diagnosis（如何判定是服务端而非 app bug）
25	
26	三个独立证据，缺一不可，避免误下"app 改坏了"的结论：
27	
28	### 证据 1：连接链路全通
29	```
30	login ok, token cached           ← 鉴权通