---
title: 无测试锚的常量/分支在事故恢复重建中静默丢失——转录重放只带回「被测试/仲裁锚住」的代码（阈值 0.5 回退 + 文字轮云端分支丢失双案）
date: 2026-08-20
last_updated: 2026-08-21
type: solution
category: workflow-issues
module: 全仓（事故恢复流程）
problem_type: workflow_issue
component: recovery
symptoms:
  - "事故恢复后真机行为回归（唤不醒/发送失败），但恢复树全部单测绿、diff 基线为空"
  - "恢复时代码与事故前 APK 行为不一致，且无人发现——直到用户报障"
root_cause: workflow_issue
resolution_type: process_fix
severity: high
tags: [repo-recovery, transcription-replay, test-anchor, silent-regression, kws-threshold, cloud-branch, forensic-rebuild]
---

# 无锚代码在事故恢复中静默丢失（双案：阈值回退 / 云端分支丢失）

## Problem

2026-08-18 仓库清空事故后，恢复树经「转录重放 + APK 反编译仲裁 + winfr + 上下文金档」五源重建
（见 [[repo-wipeout-five-source-forensic-rebuild]]）。重建有一个系统性盲区：**没有测试锚或
二进制仲裁触发点的代码，转录重放停在旧值/旧形态时无任何机制报警**。盲区家族已实证三案：

1. **KWS 阈值 0.3→0.5 静默回退**（S11-1 修复）：08-10 真机验证的 0.3 未 commit，常量内联无测试锚；
   转录重放停在对账表出厂值 0.5。同批三改动中 strip 修复/USAGE_ASSISTANT 因有测试锚/APK 仲裁被捞回，
   阈值单独丢失。真机随之回归「有概率唤不醒」。
2. **文字轮云端分支丢失**（S11-5 修复）：08-14 验证过「删 SP 零配置文字轮 dashscope」，ChatViewModel
   的 llmIsCloud 分支在重建中丢失（AndroidViewModel 内联代码 JVM 不可测、无锚）；幸存线索是
   CloudClientFactory KDoc 仍写着「ChatViewModel 用这里创建的客户端」。症状=文字轮报「账号或密码未配置」，
   语音轮正常（语音侧分支在 VSM 有测试覆盖而幸存）。
3. **gitignored 秘密整体丢失**（2026-08-19 发现，第三案·**非代码变体**）：`secrets/cloud.properties`
   不在 git 历史、五源全不覆盖——测试锚对它**原理上不可用**（文件根本不入库，无处写测试），唯一出路是
   异地秘密备份 + 从保全 APK 反编译 BuildConfig 取回
   （见 [[gitignored-secrets-lost-in-rebuild-recovered-from-evidence-apk]]）。

## Fix

- 每个调参常量进 `KwsDefaults` 式常量文件 + **精确值锚点测试**（改值必红）。
- 功能分支下沉纯逻辑（如 `cloud/TextChatRouter`）+ MockWebServer 集成锚（「云端路平台 0 请求」式断言），
  变异锚 = 「分支删除态」必须红。
- 取证基线入库：复原对拍用的 jadx 产物放 `docs/verification/`（tracked），不放 .scratch（gitignored）——
  「因无锚而丢的票，其对拍基线自身不能再无锚」。

## Prevention

1. **恢复验收必含行为对拍清单**：不单跑单测（单测也是被重建的），用事故前 APK 的反编译 config dump/
   行为日志逐项对拍关键默认值（本机的 sherpa config dump 对拍已写入真机验收固定动作）。对「密钥存在
   且正确」这类环境断言，对拍清单须含至少一轮**真凭证端到端**（第三案实证：基线全绿+结构对拍皆过而云密钥为空）。
2. 新代码入库自检一句：**「如果这个文件明天被重写，哪个测试会红？」**——答不出的代码 = 下次事故的静默丢失候选。
   （边界：对 gitignored 秘密此问无解——文件不入库；替代自问「**这个 key 丢了能从哪里找回？**」，
   答案须为异地备份/密码库/保全产物三者之一，见第三案。）
3. 根级上下文文档（CONTEXT.md/spec/对账表）与代码同值维护——事故重建优先读文档，文档假事实会
   在重建中「击败」代码真值（本案 CONTEXT.md:32 的 0.5 即重建时的错误事实源）。

## 反例

- 「常量就一行，不值得写测试」——一行常量毁掉真机一周体验（本案①）。
- 「AndroidViewModel 里的分支 JVM 测不了就不测」——下沉 30 行纯逻辑即可测（本案②的 TextChatRouter）。
- 「密钥 gitignore 了就安全了」——防泄漏正确，但同时切断了 git 路线的恢复通道；无异地锚的秘密在下次事故中必丢（本案③）。

## Related

- [[repo-wipeout-five-source-forensic-rebuild]] — 同事故母篇（五源分层重建方法论；本篇实证其盲区）。
- [[gitignored-secrets-lost-in-rebuild-recovered-from-evidence-apk]] — 盲区家族第三案·非代码变体（gitignored 秘密，测试锚原理性不可用，从保全 APK 反编译取回）。
