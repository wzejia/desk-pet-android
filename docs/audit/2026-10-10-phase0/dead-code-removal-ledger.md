# 死代码清理台账（Phase 2，2026-10-10）

> spec §5.1「死代码簇 9 处随搬移剔除，单独列清单」交付。原审计 digest 未落盘（spec 已注明），本清单为**实测口径**：剥注释符号扫描 + 编译器逐条裁决。

## 清理账目（三批，共 94 条 import）

| commit | 内容 | 条数 | 验证 |
|---|---|---|---|
| a8417d8 | 搬移脚本 KDoc 误加 phantom（第一轮，逐环抓除） | 24 | 编译器 |
| 236d574 | 未使用 import（脚本扫描 + 编译器裁决；Compose 委托 getValue/setValue 按约定保留） | 23 | 编译器 |
| 本批 | phantom 全量清（剥注释扫描器 `phantom-imports.py` 重算 73 候选 − 24 委托误报 = 49 删，误杀 2 条 java.util.Date 恢复〔`ts.format(Date(now))` 真实引用，剥注释器对字符串模板内代码误判〕） | 47 | 编译器 |

## 甄别记录（供 future 审计参考）

- **保留非死**：`androidx.compose.runtime.getValue/setValue`（`by` 委托操作符约定用法，文本不出现名字）；`kotlinx.coroutines.cancel/launch/job/async`（扩展函数以 `.name(` 形式使用）。
- **误杀实录**：`java.util.Date`（CrashReporter/PetLogger）——字符串模板 `"${ts.format(Date(now))}"` 中的代码被剥字符串器吞掉。剥注释器对插值字符串（`${}` 内代码）需保留语义，当前实现按纯字符串剥除——已知局限。
- **存量发现**：`FileProvider`（SettingsScreen 主屏，exportLogs 搬走后失效）、`Settings`（SettingsWidgets 复制残留）、`async`（VSM）等为主文件时代的存量死 import，一并清理。

## 死代码簇剩余项（spec §5.1 口径：死分支/死参数/死常量）

未在本批处理（需逐条读审计 finding 原文 + 代码验证，风险高于 import 级）→ **归 Phase 3 工程族修复批**，与 §5.3 可维护性簇同窗施工。
