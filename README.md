# 小朵 · Android 桌面陪伴助手（xiaoduo desk-pet）

原生 Kotlin 单 App：**App = 对话式助手主界面**（AG-UI 平台链路），**悬浮窗 = 桌面陪伴**
（手机精灵形态 + 气泡回复 + 「小朵小朵」唤醒词语音对话，半双工）。部署于瑞芯微板卡
（rk3288 / rk3568）驱动密集架场景的语音助手，也兼容普通 Android 手机调试。

> 本仓库为内部项目公开存档，未附开源许可证（默认保留所有权利）。凭据与内网秘密文件均不入库。

## 特性

- **语音全链路**：KWS 唤醒词常开（sherpa-onnx）→ VAD 判停 → ASR → LLM（AG-UI SSE）→ 分句 → TTS 流式播报，半双工四态状态机（待机/播报/聆听/处理）
- **多种唤醒通道**：唤醒词（本地 KWS）、点按对讲（Tap-to-Talk）、双击打断、**PIR 红外人体感应**（易声板卡 GPIO 轮询，`app/src/main/java/com/xiaoduo/deskpet/ir/`）
- **悬浮窗桌宠**：精灵动画、打字机气泡、语音指示（声纹/思考三点）、贴边收纳、长按菜单
- **平台与联动**：AG-UI 协议客户端（登录/SSE/工具调用/多智能体）、密集架 MCP 结果卡、本地设备技能（整理台/净化柜/导航）
- **工程纪律**：839 个 JVM 单测（零 Robolectric/Mockito/MockK，MockWebServer + 手写 Fake + 纯逻辑 + 契约测试四模式），异步守卫变异锚制度

## 构建

```bash
export PATH="/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin:$PATH"   # Windows/Git Bash
./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks
```

- minSdk 26 / targetSdk 34 / Kotlin 1.9.24 / Compose BOM 2024.06.00 / Gradle 8.9
- 云端密钥经 gitignored 的 `secrets/cloud.properties` 在构建期注入 BuildConfig（仓库内无任何凭据）
- `app/libs/` 内 sherpa-onnx（Apache-2.0）与易声板卡 SDK 为本地依赖

## 导航

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/xiaoduo/deskpet/` | 源码（根包入口 / voice 语音管线 / chat / agui / device / ui / ir 红外唤醒） |
| `CLAUDE.md` / `AGENTS.md` | 项目规则与协作约定入口（内容同步） |
| `docs/plans/`、`docs/spec/` | 产品契约与技术蓝图（as-built 以代码 + DEV_LOG 为准） |
| `docs/research/` | 专项调研报告（如 PIR 红外唤醒可行性） |
| `docs/verification/` | 验收记录与执行清单 |
| `docs/solutions/`、`docs/adr/` | 踩坑解法库 / 决策留痕 |
| `docs/DEV_LOG.md`、`PROGRESS.md`、`CONSTRUCTION.md` | 开发日志 / 进度账 / 施工清单 |
