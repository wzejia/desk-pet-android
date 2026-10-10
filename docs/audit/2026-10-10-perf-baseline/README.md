# 性能基线 2026-10-10（Spec v0.4 §5.4 Phase 0 交付）

> 测量协议见 spec §5.4；设备 = 生产板 10.0.14.18（rk3568_r，Android 11，1080×1920 @160dpi）。
> 包 = refactor/p0 @ 19ea825（含 Phase 0 日志锚：`playback-start *` / `overlay first-frame drawn`）。
> **计时口径：全部取设备钟**（logcat/文件日志时间戳；板钟比 PC 慢 ~3.5 分钟，严禁跨钟求差）。
> 目标值未设定（领导 2026-10-10 拍板：先实测后议）；唯一守门线 = 各 Phase 复测不回退。
> 抖动带宽 = 后续任何目标值讨论的分辨力依据（spec §5.4 前置条款）。

## 1. 内存（dumpsys meminfo PSS，单位 MB）

| 态 | 采样点 | TOTAL PSS | Native Heap | 备注 |
|---|---|---|---|---|
| 空闲 | App 前台静置 5min | **219.2** | **86.3** | meminfo-idle-5min.txt |
| KWS 常开 | 开关开后 10min | **221.2** | **86.3** | meminfo-kws-10min.txt；较空闲 +2.0MB（稳态带内，复测按同协议对比） |
| 播报中 | 起播后 3s 起连采 5 次 @1s 取中位 | （待现场喊话采集） | | 需真人喊「小朵小朵」+指令 |

**抖动带宽**（空闲期 10 样本 @30s）：稳态（t≥60s，9 样本）TOTAL PSS **215.8~216.7 MB（带宽 <1 MB，分辨力 ±1 MB）**；Native Heap 84.9~85.2（±0.3 MB）。t=30s 样本 192.9/63.7 为冷启动爬坡（会话恢复后渐涨），不计入稳态带。后续目标值讨论以 ±1 MB 为最小可判差异。

## 2. 响应（设备钟差值，ms）

| 项 | 定义 | 基线值 | 样本 |
|---|---|---|---|
| 悬浮窗挂窗→首帧 | `dual windows added` → `overlay first-frame drawn` | **中位 276**（样本 259/268/271/276/279/289/679；679 为 r3 布局调度离群，剔除后带宽 259~289） | 7 轮冷启动 |
| 悬浮窗端到端（trampoline） | mijijia 基础设置按键→首帧 | （待现场按键采集） | |
| 唤醒词命中→应答音 | KWS hit 日志 → `playback-start`（wozai） | （待现场喊话采集） | |
| Chat 发送→首 token | input 提交 → 上屏日志 | （待采集） | |

## 3. 现场配合项（自动化不可达，需真人）

1. **播报中三态**：喊「小朵小朵」→ 应答后说一句指令（触发 LLM 回复播报）——起播锚 `playback-start` 出现后由会话侧连采 5 次 dumpsys。届时说一声即可，采集全自动。
2. **唤醒响应**：同一次喊话的 KWS hit → playback-start 设备钟差（可自动解析，无需额外操作）。
3. **trampoline 端到端**：mijijia 基础设置点「打开小朵」一次（锚可自动解析）。

## 4. 原始产物

- `meminfo-jitter.txt` / `meminfo-jitter-full.txt` — 空闲期抖动采样（10×30s）
- `meminfo-idle-5min.txt` / `meminfo-kws-10min.txt` — 两正式态全量 dumpsys
- `cold-start-fullchain.txt` — 冷启动全链锚时间戳
- `.scratch/perf-baseline-20261010/` — 同内容工作副本（临时区）
