1	---
2	title: dashscope ASR run-task 的 task 字段是 "asr" 非 "recognition"——误读 SDK 元组解包致 Model not found
3	date: 2026-08-13
4	type: solution
5	category: logic-errors
6	module: app/src/main/java/com/xiaoduo/deskpet/cloud/AliyunAsrClient.kt
7	problem_type: protocol_misjudgment
8	component: cloud-asr
9	symptoms:
10	  - "阿里 dashscope ASR WebSocket 连接成功、发帧成功，立即返回 task-failed: Model not found (paraformer-realtime-v2)"
11	  - "同一 key 同一 model，Python dashscope SDK 能正常识别，Kotlin 原生 WS 报 Model not found"
12	root_cause: wrong_request_field_value
13	resolution_type: code_fix
14	severity: high
15	tags: [aliyun, dashscope, asr, websocket, model-not-found, tuple-unpacking, capture-sdk-frames, ported-from-python]
16	---
17	
18	# dashscope ASR task 字段："asr" 而非 "recognition"——别靠读源码推演，抓 SDK 实际帧
19	
20	## Problem