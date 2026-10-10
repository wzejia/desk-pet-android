# 依赖分析 2026-10-10（Spec v0.4 Phase 0 入库）

- `dep-analysis-20261010.py` — 包级依赖分析脚本 v3（import 扫描 + root-hub 白名单 + buildgen 产物边分离 + 环检测）。
- `dep-analysis-20261010.json` — 全量产物（文件级 import + 矩阵 + 文件链接清单）。

复跑命令与脚本版本史见 `docs/spec/refactor-structure.spec.md` 附录 A：

```
MSYS2_ARG_CONV_EXCL="*" python "E:/desk-pet-android/.scratch/dep-analysis-20261010.py"
```

开工实测对账（2026-10-10）：环 4 对 / hub 例外边 8（chat1/ui5/voice2）/ buildgen 1 / 矩阵逐格 = spec §3.2 一致。Phase 1/2 验收与 §4.4 周期复扫以本脚本为准；白名单增删须同步 spec §4.3。
