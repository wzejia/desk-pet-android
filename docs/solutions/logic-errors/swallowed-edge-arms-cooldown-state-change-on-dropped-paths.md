---
title: 被吞边沿起冷却表——丢弃路径上的状态变更同样是行为变更，评审判「无害」前先算这笔账
date: 2026-10-26
type: solution
category: logic-errors
module: app/src/main/java/com/xiaoduo/deskpet/ir/PirEdgeFilter.kt
problem_type: review_blindspot
component: S70-PIR唤醒
symptoms:
  - "PIR 触发唤醒实测间隔 33-65s，远低于「人到即唤醒」预期"
  - "电平轨迹证明传感器活体信号正常（10s 窗自然翻转），排除硬件"
  - "间隔形态 ≈ 聆听周期 + 30s 冷却，与「连续 30s 无新边沿才放行」模型吻合"
---

# 现象

S70 板测（192.168.137.140）用户反馈触发率偏低。触发史：14:19:51 → 14:20:24 → 14:21:29 →
14:22:21 → 14:23:05，间隔 33-65s；当前电平恒 0（人不动=0，正常）。

# 根因

`PirEdgeFilter` 在**每个上升沿**（含将被 idleGate 丢弃的非 IDLE 态边沿）内部记录 30s 冷却起点。
产品口径「仅 IDLE 响应」丢边沿是对的，但**丢弃路径上的 arming 状态变更仍然生效**——用户持续在
板前挥手的每个被吞边沿都在重置冷却表，等效「连续 30s 无新边沿才放行」。首轮 critic 标为
P3-5 advisory 判「保守方向无害」；板测证伪为主凶。

# 修法

arming 契约反转：`onSample()` 只判定不起表；`armCooldown(nowMs)` 由 controller 在
**过 IDLE 门、真调 onWake** 时才调；被吞边沿零副作用。变异锚 M6 = 把 arming 移回门检查
之前（直接复刻旧缺陷形态）→ 板测用例必须红。commit `c05c0c1`，终局 839 绿。

# 教训（可迁移）

1. **评审判「无害」前先问：这个状态变更影响了谁。** 被丢弃的事件 ≠ 无副作用的事件；
   「保守方向」也要算副作用账。
2. **advisory（P3）是修复优先级，不是重要性证伪。** P3-5 在真实使用场景是主凶——
   评审语言（P1/P2/P3）与实际危害分属两个坐标系，板测/真机数据才有裁决权。
3. **此类缺陷单测难抓**（被吞边沿起表在单看 filter 时「语义正确」），变异锚要按
   「缺陷形态」设计（M6 复刻旧缺陷）而非按代码行设计。

**关联**：`workflow-issues/guard-comment-anchors-must-bind-tested-wiring.md`（锚要绑被测
接线）；`workflow-issues/adb-run-as-prefs-write-must-verify.md`（同日实战复用的活解法）。
