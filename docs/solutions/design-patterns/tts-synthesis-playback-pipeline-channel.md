1	---
2	title: TTS 合成与播放流水线化——阻塞生产/消费经带缓冲 Channel 解耦，隐藏合成延迟消除断词
3	date: 2026-08-13
4	type: solution
5	category: design-patterns
6	module: app/src/main/java/com/xiaoduo/deskpet/voice/VoiceSessionManager.kt
7	problem_type: performance
8	component: voice-tts
9	resolution_type: refactor
10	severity: medium
11	tags: [tts, pipeline, producer-consumer, channel, prefetch, latency-hiding, coroutine, sentence-streaming]
12	---
13	
14	# TTS 合成/播放流水线：阻塞生产+消费经 Channel 解耦隐藏延迟
15	
16	## Problem
17	
18	逐句 TTS 管线 `consumeSentences` 原为**串行单循环**：每句 `合成(阻塞 HTTP ~0.7s) → 播放(阻塞全句) → 才合成下一句`。
19	
20	播第 N 句时**没有**预合成 N+1。N 播完 → 等 N+1 合成的 ~0.7s 是静音 → 每句边界 ~770ms 停顿 = 听感断词/卡顿。日志铁证：
21	
22	```
23	00.028 played 块1完 → 00.036 POST 块2(才开始合成) → 00.803 块2ok → 才播块2
24	                       └──────── 这 0.77s 是静音 ────────┘
25	```
26	
27	## Root Cause
28	
29	合成（阻塞 HTTP，~0.7s/句）与播放（阻塞，数秒/句）在**同一循环串行**，无重叠。TTS 每句一次 HTTP 请求，延迟无法隐藏。
30	