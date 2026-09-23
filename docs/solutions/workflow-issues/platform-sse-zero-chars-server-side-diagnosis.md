---
title: 平台 SSE 零字符断流排障全案——08-13 空转 65s / 09-23 快关 2.2s 两形态，定案平台 Agent 故障，客户端零改动
date: 2026-09-23
type: solution
category: workflow-issues
module: app/src/main/java/com/xiaoduo/deskpet/agui/AguiClient.kt
problem_type: diagnosis_methodology
component: platform-llm
symptoms:
  - "发消息后报 SSE 断流: stream ended without RUN_FINISHED (produced 0 chars)（09-23 形态：HTTP 200 + RUN_STARTED/STEP_STARTED 后 ~2.2s 空手关流）"
  - "08-13 形态：login/createThreadId/run POST 全成功但 SSE 全程零事件，~65s 后断开"
  - "同一时刻 PC 直连同 token 同 thread 成功、板上 100% 失败（或反之），间歇窗几连断后自愈"
  - "板端全链路（login/TCP/TLS/HTTP/SSE 头+控制事件）均正常"
root_cause: server_side_upstream_unresponsive
resolution_type: no_app_change
severity: info
tags: [platform, agui, sse, diagnosis, server-side, chatAgent, pktmon, tcpdump, clock-skew, dual-track-control]
---

# 平台 SSE 零字符断流排障全案（两形态定案 + 方法论）

## 两案速览

| | 08-13 案 | 09-23 案 |
|---|---|---|
| 形态 | login/thread/run POST 全成功，SSE **全程零事件**，~65s 断 | HTTP 200 + RUN_STARTED/STEP_STARTED 到达后，**~2.2s 空手关流**（close_notify） |
| 面 | 切平台初期全量不通 | 间歇窗：几连断后自愈，板/PC 表现不一致 |
| 定案 | 服务端上游无响应（server_side）,app 零改动 | **平台 chatAgent 故障，切换 Agent 即恢复**（平台侧确认） |
| 共性 | 客户端一切正常（链路通、鉴权通、HTTP 2xx）——**别改 app** | 同 |

## Symptoms（09-23 案取证细节）

- 板上连续 6/5/3 连断（`run POST` → `AG-UI failed: SSE 断流 ... produced 0 chars`，每轮 POST 后 ~2.2s）。
- 板上 root tcpdump 抓包（`host 10.0.18.166`）：新建 TCP+TLS + POST(606B) → 252ms 响应头(486B)+SSE 控制事件(29B+32B+72B) → **2.58s 静默** → 27B（TLS close_notify）→ EOF。**服务端应用层主动关流**，非网络故障。
- 同时刻 PC 直连（python httpx，同 token 同 thread 同 body）：**成功**（首事件 0.1-0.5s）。修「板上 100% 断」花了大量对照实验（见下）。

## What Didn't Work（12 项排除清单——下次别再重走）

| # | 假设 | 排除方法/证据 |
|---|---|---|
| 1 | S65 代码引入 | 聊天 UI（不走语音链）同断；改动不碰网络栈 |
| 2 | token 过期 | `login ok` 后 2 秒即断；新旧 token 全试 |
| 3 | thread 状态坏 | 失败/成功轮同一 threadId；换新 thread 同样表现 |
| 4 | 平台全局故障窗 | PC 同刻成功（后确认：确实有独立窗，但板的失败是 agent 故障叠加） |
| 5 | 板子网络/DNS | TCP 443/8080 全通；pcap 显示 TLS/HTTP 全正常 |
| 6 | HTTP 层错误 | 断流轮是 2xx（否则代码报 HTTP code） |
| 7 | 消息内容触发 | PC 重放同内容（含走工具调用的「当前有哪些档案」）全成 |
| 8 | forwardedProps.source 字段 | 交叉矩阵：板 token/web token × source=desk_pet/web——source 无影响 |
| 9 | User-Agent/头指纹 | PC 复刻 okhttp 全套头——照成 |
| 10 | keep-alive 复用连接 | pcap 证实新建连接也断 |
| 11 | 源 IP（私网 192.168.137.76 被区别对待） | PC bind 192.168.137.1 源发——照成；（pktmon 却抓出另一个事实：板流量经 PC 是**纯转发未 NAT**，平台看到的板 IP 确与 PC 不同——但 11 的对照实验已排除它是主因） |
| 12 | Ollama 排队/慢 | 停掉 PC 探测后板上仍 100% 断；Ollama 直测仅证其自身有 40% 概率首 token 6-8.5s 抖动（背景噪声，非本因） |

**收敛点**：客户端变量排除干净后，剩余只能是服务端配置级差异——**平台 chatAgent（该 agent 的执行/工具链）故障，切 Agent 恢复**。

## Diagnosis 方法论（可复用）

1. **双线对照（金标准）**：故障时刻同时取「板端 app 日志 + PC 直连重放（同 token/同 body）」。二者分化 → 客户端侧；同断 → 平台侧。**必须对齐时钟**（本案例板钟快 PC 2 分 34 秒——差点把「2 分钟后 PC 成功」误读为「同刻 PC 成功」）。
2. **板上 root 取证**：`adb root` + `/system/bin/tcpdump`（rk 板自带）抓 `host <平台> and port 443`，看连接形态（SYN/复用）、FIN/RST 方向、静默时长。**close_notify + FIN = 服务端应用层关流**，不是网络故障。
3. **PC 出口抓包定位路径**：`pktmon filter add -i <平台IP>` + `pktmon start --etw -f cap.etl` → `pktmon etl2txt`（UTF-16 输出用 python 解析）→ 看**真实五元组**（本案例揭穿「板走 ICS NAT 源 IP=PC」的错误假设：实为纯转发，源 IP 保持 192.168.137.76）。
4. **重放分叉矩阵**：把「客户端」的所有可控变量（token/source/UA/body/thread/源 IP/连接新旧）在 PC 上逐一复刻——**每排除一项就永久出局**，收敛到最后一环。
5. **Git Bash 发中文必损坏**（宪法级坑）：用 python/httpx 重放（`trust_env=False` 手配代理），curl -d 中文会变乱码换一个错误现场。

## Prevention

- **平台侧知识 > 黑盒探测**：12 项假设烧了近 3 小时，而「切 Agent 试一下」是 1 分钟的事。排障顺序应把**便宜的服务端自检**（login→换 agent→换 thread 的最小重放）放在昂贵取证（tcpdump/pktmon/矩阵实验）之前。
- 平台出现「HTTP 2xx + 控制事件后空手关流」形态时，**先怀疑 agent 配置/工具链，而非网络**。
- 板上 2s 级快关与 65s 空转是同族（上游无产出）的两种面：平台超时参数/agent 版本变化会让形态漂移，排障时别把「形态不同」当「不同 bug」。
