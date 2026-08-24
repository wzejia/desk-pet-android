# v18 S13 形象动作改造验收（2026-08-24，模拟器）

> 定案（用户逐项拍板，原型 `designs/xiaoduo-motion-preview/小朵动画改造预览 v2.html` 过目后确认）：
> **平时/聆听/处理/出错 = idle 行第 0 帧完全静止；说话 = idle 行 6 帧 @120ms/帧（眨眼+嘴型微动）；
> 唤醒跳、点击挥手全部取消**。素材不换（沿用现 spritesheet.webp，hd-v2 方案经原型对比后被用户否决）。

## 代码改动面

| 文件 | 改动 |
|---|---|
| `pet/PetFrameTable.kt` | IDLE/WAITING/REVIEW/FAILED → row0 frames=1 静止；TALKING → row0 6帧 720ms；RUNNING/WAVING/JUMPING 行保留不接线 |
| `assets/pet_states.json` | 同步镜像（SHA256 `60b45124…4366`；Android 行为有意偏离桌面端，notes 记档） |
| `voice/VoiceSessionManager.kt` | 唤醒视觉 `playPetOnceThen(JUMPING→WAITING)` → `setPetState(WAITING)`；wakeAckActive 门控保留 |
| `ui/screens/FullscreenVoiceScreen.kt` | 唤醒分支同上改直接 WAITING |
| `PetOverlayService.kt` | 单击挥手接线移除（非贴边单击 no-op）；`playPetOnceThen` 实例+companion 转发删除 |
| `ui/screens/ChatScreen.kt` | 顶栏宠物点击挥手移除（含 latestPhase/`rememberUpdatedState` 清理） |
| `ui/PetView.kt` | `playOnceThenIdle`/`playOnceThen` 死代码删除 |

## 证据

### 1. 单测基线：455 → **457 全绿 skipped=0**（+2 新增，零删除）

- 新增：`静止单帧态永不推进`（四静止态 tick 任意时长恒第 0 帧）、`static states share idle row zero and talking uses idle row`（S13 契约）
- 适配：`每行帧数与帧表一致`/`帧时长换算正确`/`源矩形坐标…` 等旧期望值改 S13 值；「行号唯一」契约改为「row0 有意共享 + row1-4 遗留行唯一」
- `assembleDebug` 绿

### 2. 悬浮窗静止取证（`emu-home-overlay-1/2.png`，间隔 1s）

宠物区域裁剪（`crop-pet-t0/t1.png`）**MD5 逐字节一致**（b48c38caaad9，diff bbox=None）——
改造前 idle 为 6 帧 183ms/帧循环，1 秒内必跨帧，现完全一致 = 完全静止。

### 3. 说话动画取证（聊天页 STREAMING→TALKING 映射，顶栏 32dp 宠物）

连拍哈希序列（顶栏宠物裁剪区）：

| 时刻 | 相位 | 裁剪 MD5 | 结论 |
|---|---|---|---|
| stream-a（SENDING） | REVIEW | `50613d41a43f` | 静止（与 idle 帧同 hash，符合「静止态共享 idle 第 0 帧」） |
| stream-g（STREAMING） | TALKING | `25470875ce4e` | **≠静止帧 = 动画在播** |
| stream-done（完成） | IDLE | `50613d41a43f` | 回静止 |

`diff-talking-vs-static.png`：差异 bbox `(0,6,49,85)` 局限在面部（嘴/眼区）——与 idle 行帧间
「眨眼+嘴型微动、身体不动」的设计一致。对照图 `crop-bar-talking-g.png` vs `crop-bar-static-done.png`。

## 边界与说明

- 语音链路（悬浮窗 SPEAKING→TALKING / 唤醒→WAITING 静止）的映射改动与聊天页同一帧表同一
  transition 映射，模拟器声学不可验，语音轮主观观感留真机日常回收。
- 唤醒期「我在」播放期间视觉 = WAITING 静止帧（不再是 jumping）；wakeAckActive 门控行为不变
  （既有 wakeAck 测试未动断言，全绿）。
- running-left/right/waving/jumping 行保留在 spritesheet 与帧表内（无触发点），未来要恢复任一
  动作只需接线不改素材。
