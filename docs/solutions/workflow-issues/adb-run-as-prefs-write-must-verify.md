---
title: adb run-as 改 prefs 写坏文件事故——MSYS 路径转换把设备路径变 C:/Program Files/Git/...，未校验写回把错误文本落盘
date: 2026-09-23
type: solution
category: workflow-issues
module: app/src/main/java/com/xiaoduo/deskpet/net/NetConfig.kt
problem_type: tool_misuse
component: dev-workflow
symptoms:
  - "改板端 platform_base 后 prefs 文件（xiaoduo_net.xml）只剩 112 字节，内容为 'cat: C:/Program Files/Git/data/...: No such file or directory'"
  - "平台 token/账号/password/各云 key 字段全部丢失"
  - "cat 报错路径形如 C:/Program Files/Git/data/data/...（Git Bash 路径转换产物）"
root_cause: tool_misuse
resolution_type: process_fix
severity: medium
tags: [adb, run-as, sharedprefs, msys-path-conversion, git-bash, write-verify, incident]
---

# adb run-as 改 prefs 写坏文件（事故记录 + 纪律）

## 事故经过（2026-09-23，测试板 192.168.137.76）

1. 为验证「平台按源 IP 区别对待」假设，需把板端 `platform_base` 从 `http://10.0.18.166:8080` 改为 portproxy 地址。
2. 用 `exec-out run-as com.xiaoduo.deskpet cat /data/data/com.xiaoduo.deskpet/shared_prefs/xiaoduo_net.xml > 本地.xml` 拉取——**设备路径被 Git Bash（MSYS）自动转成 `C:/Program Files/Git/data/data/...`**，设备端 cat 报错，**报错文本（112B）进了本地文件**。
3. 本地替换脚本报 PATTERN MISS（内容根本不是 xml），但后续写回命令 `run-as sh -c 'cat > /data/.../xiaoduo_net.xml' < 本地.xml` **没有校验 stdin 内容**——112B 错误文本覆盖了 prefs。
4. 事故后才察觉：中间还有一次 `MSYS_NO_PATHCONV=1` 前缀没有生效（写法和 shell 环境相关，别信它万能）。

## 损害与恢复（评估清单）

- 损坏：`xiaoduo_net.xml`（platform_base/token/account/password/各云 key）。
- 完好：`xiaoduo_agui.xml`（thread 映射）、`xiaoduo_settings.xml`。
- **兜底链救场**（`NetConfig.kt`）：SP → BuildConfig 内置（`secrets/cloud.properties` 打包注入）→ 默认值。多数云 key 有内置兜底；`platform_base` 默认值即原值；`account` 默认 `admin`。
- **必须人工重填的两项**：①`password`（无默认值）②`llm_mode/asr_mode/tts_mode` 三模态——**默认是 cloud**，板上原为 platform，不切会静默走云端。
- 恢复动作：force-stop → 删坏文件（app 重建默认）→ 用户设置页重填。**删除前先 `ls -la shared_prefs/` 确认无其他损坏文件**。

## 纪律（写回类操作的硬约束）

1. **写回必须读回校验**：写后立即 `cat` 回来核对字节数/关键字段/（可选）md5，不一致就告警。本次事故的直接原因就是这步缺失。
2. **MSYS 下设备路径用相对路径**：`run-as <pkg> sh -c 'cat shared_prefs/xxx.xml'`（run-as 会切到家目录，`shared_prefs/...` 不以 `/` 开头，转换器不碰）。绝对路径 `/data/...` 在 Git Bash 里随时可能被转。
3. **改前先备份**：`exec-out ... cat > 本地备份.xml` 后**先校验备份文件是合法 xml（首字节 `<?xml`、大小量级对）**再动设备文件。
4. **重定向产物先验内容**：任何 `> 文件` 的命令，下一步用它之前先 `wc -c` / `head -c` 看一眼；明显偏小（如 112B）就是错误消息而非数据。
5. 教训通性：**工具链的隐形转换（路径/编码/换行）是事故高发区**——和宪法「Git Bash curl -F 发中文必损坏」同族，方案级对策是「重要写操作走 python + 显式校验」。
