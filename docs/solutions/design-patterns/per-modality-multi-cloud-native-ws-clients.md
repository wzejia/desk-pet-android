1	---
2	title: 按模态独立选云厂商——纯 Kotlin 原生 WS 客户端 + Factory 统一分发，VoiceSessionManager 对厂商透明
3	date: 2026-08-13
4	type: solution
5	category: design-patterns
6	module: app/src/main/java/com/xiaoduo/deskpet/cloud/
7	problem_type: architecture
8	component: cloud
9	resolution_type: design
10	severity: info
11	tags: [multi-provider, per-modality, native-ws, factory, sealed-result, byok, okhttp-websocket, jvm-testable]
12	---
13	
14	# 按模态独立选云厂商：原生 WS 客户端 + Factory 统一分发
15	
16	## Context
17	
18	需求：每个模态（LLM/ASR/TTS）**独立**选择走内网平台还是云端 API，云端又支持多厂商（OpenAI 兼容 + 火山 + 阿里）。无网关，app 直连云端 REST/WS。火山/阿里 ASR/TTS 是私有二进制/WS 协议，没有现成轻量 Android SDK。
19	
20	## Pattern
21	
22	### 1. 纯 Kotlin 原生客户端（零 Android import，JVM 可单测）
23	
24	每厂商每模态一个 client 类，构造注入 OkHttp + 凭证 + logger，返回自己的 sealed `Result`：
25	
26	```kotlin
27	class AliyunAsrClient(http, apiKey, model, sampleRate, logger) {
28	    sealed interface Result {
29	        data class Success(val text: String) : Result
30	        data class Error(val message: String) : Result