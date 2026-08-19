---
title: 多 builder 并行共用一台真机必须用互斥锁（设备串台事故）
date: 2026-07-30
category: workflow-issues
module: 子智能体编排 / 真机验收
problem_type: workflow_issue
component: development_workflow
severity: medium
applies_when:
  - 多个并行子智能体都要对同一台 adb 真机做操作（install/input/screencap/dumpsys/run-as）
  - 编排会话 fan out 多个 builder 且各自票据含真机验收
tags: [adb, 设备锁, 并行, 子智能体, mutex, 真机]
---

# 多 builder 并行共用一台真机必须用互斥锁

## Context
小朵助手化编排会话同时 fan out 3-4 个 builder，各自票据都含真机（de61cd8）验收段。
无协调时发生真实串台：一方 `installDebug` 杀掉另一方正在验收的进程并清掉当日日志
（S3 builder 被 S1 builder 连杀两次）；一方 input tap 落到另一方驱动的屏幕；
一方验证用的 `pet_scale` 被另一方改成 0.909。运行中 agent 的 transcript 是 0 字节占位，
编排层无法窥视在途命令流，事后只能凭设备状态推断。

## Guidance
真机只有一台 = 全局互斥资源，按锁协议派单（已固化进项目 CLAUDE.md「真机互斥锁」节）：

```bash
# 任何设备操作前拿锁（mkdir 原子）
while ! mkdir .scratch/device.lock 2>/dev/null; do sleep 10; done
echo "builder-sX $(date)" > .scratch/device.lock/holder   # 落身份便于排查死锁
# ... 整段真机操作（≤5 分钟，不拿锁写代码）...
rmdir .scratch/device.lock
```

配套纪律：
- 派单提示词里显式写锁协议（builder 不会自发遵守）
- 验收证据看时间戳新鲜度与连续性：install 时间戳交叠/日志断档 = 疑似被污染，持锁重做
- uiautomator dump 在 ColorOS 本机返回陈旧 accessibility 树，定位以 screencap 为准
- critic 与 builder 同样受锁约束（评审也要亲跑真机）

## Why This Matters
无锁并行的失败是静默的：证据看起来「取到了」但实际是串台产物（截到对方屏幕、
读到被清的日志），比显式失败更危险——它会污染验收结论。立锁后未再发生串台。

## When to Apply
任何「多 agent + 单共享有状态资源」（真机/仿真器/独占端口/单例测试数据库）场景。
同理可推广：资源不可复制就把它当锁，不要指望时序运气。
