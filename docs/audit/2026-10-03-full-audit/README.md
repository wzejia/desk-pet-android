# 小朵 Android 全量代码审计 · 总报告（2026-10-03）

> 入口文档。逐条明细见 `unified-findings.md`；本报告只给结论、覆盖、披露与待拍板项。

## 一句话结论

**P1 = 0，P2 = 43，P3 = 410。** 配置面与代码面整体质量良好（权限最小化、allowBackup=false、NSC 收敛、秘钥文件未入库）；**风险集中在「分发形态」**：debug 签名发布 + BuildConfig 明文密钥 + TLS 内网白名单旁路三件事互相放大，适合打包成一个安全决策提交拍板，而非逐条零敲。

## 方法与覆盖（双通道机扫 + 双路人工）

| 通道 | 覆盖 | 产出 |
|---|---|---|
| ocr scan（智谱 glm-5.3-flash） | 100/105 源码文件（Kotlin） | 462 条（high 61 / medium 179 / low 220） |
| codex-security（默认模式） | 全仓 215 文件（含 res/manifest） | 5 条（high 1 / medium 2 / low 2），coverage complete |
| 人工兜底（子智能体） | ocr 三连败的 5 文件（VoiceSessionManager 1308 行在内），已核实与 ocr 零重叠 | 14 条（P2×2 / P3×12） |
| 人工非代码（子智能体） | manifest / gradle 三件套 / res/xml / wrapper / 签名秘钥入库检查 | 8 条（P2×2 / P3×6） |

**有效覆盖 = 105/105 源码文件 + 全部非代码面。**

## 跨通道独立命中（高置信信号，优先处理）

| 条目 | 命中通道 | 一句话 |
|---|---|---|
| SEC-1 TLS 证书校验对内网白名单完全旁路 | codex high + ocr | `HttpClients.kt:25` TRUSTED_HOSTS 三内网 IP 跳过校验；利用前提=进入板子所在内网 |
| SEC-3/SEC-4 凭证明文（prefs + BuildConfig 编入 APK） | codex + ocr + 人工非代码 | 反编译即得；服务端需可轮换/限额 |
| CONC-1 VAD 并发无锁 | ocr + 人工 | 分级有分歧（high vs P3），列待复诊 |

## 披露（审计过程如实记录）

1. **配额风暴**：首扫 55/105 文件被 429 限流打死（三流并发所致）→ 降并发分三轮补扫恢复；耗时与重试史见 `ocr-0*.log`。
2. **ocr 请求级死线**：5 文件（MarkdownRenderer/VolcengineAsrClient/BubbleTextStrip/NlmsEchoCanceller/VoiceSessionManager）三次尝试（并发 8/2/1 + 45min 超时）全部 LLM 单请求超时，转人工兜底覆盖，覆盖无缺口。
3. **疑似部分评审 3 文件**：DynamicWakeAck / BubbleView / ChatSession（同批次 error+comments），条目可信、覆盖完整性未证实（`coverage-flags.json`）。
4. **codex Deep 模式在本机不可用**：deep_discovery 的 `in_scope_files.txt` 仓库相对路径校验 bug（绝对/相对路径均触发），降档默认模式完成。
5. **分级非机械映射**：ocr 61 条 high 逐条复核后仅 42 条 P2（19 条降 P3：理论性/纯性能/诊断类）；179 条 medium 中 21 条凭实锤升级。全部升降理由见 `unified-findings.md` 第一节。

## 待领导拍板（6 项，详见 unified-findings.md 第五节）

1. **SEC-1 是否升 P1**——判 P2 的前提是「内网不可进入」这条威胁模型假设，若板子部署环境不可控建议升 P1 并做证书锁定
2. SEC-6 runId 注入：ocr 命中、codex 明示否决，两通道矛盾，需读源码裁决
3. CONC-1 VAD 并发分级分歧
4. WAV 解析族是否统一升 P2
5. ocr[161] saveAll 潜伏参数 footgun（当前无触发调用方，未核实未来路径）
6. ocr[375] JVM org.json 依赖是否进入生产路径

## 产物索引（本目录）

- `unified-findings.md` — **合并分诊主文档**（P2 全列 43 条 / P3 七主题聚类 / 重叠表 / 待复诊）
- `p2-p3-detail.json` — P1+P2 全字段（含 ocr_indices 回溯索引）
- `merged-ocr-findings.json` + `coverage.tsv` + `coverage-flags.json` — ocr 原始 462 条与覆盖账
- `codex-report.md` + `findings.json` + `coverage.json` — codex 通道原件
- `manual-code-rescue-findings.md` / `manual-noncode-findings.md` — 人工两路原件
- `ocr-0*.json` / `ocr-0*.log` — 机扫原始批次与日志
- `merge-ocr.py` — 批次合并脚本（可复跑）

审计对象：main `5ee8e20`（2026-10-03 与 HEAD 一致）。纯审计零代码改动；交付时 `git status` 仅含本目录、PROGRESS.md、BLOCKED.md。
