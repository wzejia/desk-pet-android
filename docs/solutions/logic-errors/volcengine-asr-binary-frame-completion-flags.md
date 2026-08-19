1	---
2	title: 火山 ASR 二进制帧完成信号——flags 负序号位(0x02)而非 is_last_package 字段，误判致超时丢弃已识别文本
3	date: 2026-08-13
4	type: solution
5	category: logic-errors
6	module: app/src/main/java/com/xiaoduo/deskpet/cloud/VolcengineAsrClient.kt
7	problem_type: protocol_misjudgment
8	component: cloud-asr
9	symptoms:
10	  - "火山 ASR WebSocket 连接正常、发帧正常、收到 26 个 code=0 响应帧，但 12-30s 超时"
11	  - "超时后返回 Error，把已捕获的识别文本（如「只不过是咱咱们村里」）当错误丢弃"
12	  - "服务器末帧 text 非空且含完整 utterances，但 last=false（无 is_last_package 字段）、连接不断开"
13	root_cause: wrong_completion_signal
14	resolution_type: code_fix
15	severity: high
16	tags: [volcengine, asr, websocket, binary-protocol, completion-detection, flags, negative-sequence, ported-from-python]
17	---
18	
19	# 火山 ASR 完成判定：用 flags 负序号位，不是 is_last_package
20	