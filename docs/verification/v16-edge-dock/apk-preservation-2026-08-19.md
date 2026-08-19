# S10-3 前置：设备 APK 保全证据（2026-08-19）

> 目的：S10-3 验收往设备装恢复树新构建前，把设备上现装老 APK 保全为不可变证据。
> 本文件为任务 A 收尾记录（sha1 指纹），背景见交接文档
> `C:\Users\Administrator\AppData\Local\Temp\handoff-desk-pet-android-s10-3-2026-08-19.md`。

## 副本清单（sha1sum 实测，2026-08-19 本会话亲算）

| 副本 | 路径 | 尺寸 (B) | sha1 |
|---|---|---|---|
| 真机 de61cd8 现装（S9 最终构建） | `C:\Users\Administrator\desk-pet-recovery\apks\device-base.apk` | 90,876,784 | `f018793f59b494c8a899d5e95047b2ff82bd8456` |
| 模拟器 emulator-5554 现装（S10 构建，恢复树反编译/资产正本） | `C:\Users\Administrator\desk-pet-recovery\apks\emulator-base.apk` | 90,703,733 | `5d7447ae28668c67a59fca89f2fe26fd456f4064` |
| 恢复树新构建（E: 复验产物，本次装机包） | `E:\desk-pet-android\app\build\outputs\apk\debug\app-debug.apk` | 90,703,737 | `eb8ec300c1d0e02e7f4d8dd06e5e27b302d7d9ba` |

- 尺寸与交接文档口径逐一核对一致（90,703,733 / 90,703,737 逐字节相符；device-base 90,876,784 ≈ 交接口径「约 90.8MB」）。
- emulator-base 与新构建 4 字节漂移属重建非确定性，恢复会话已核实无碍（交接文档 §2 步骤 3）。
- 保全完成 → 进入 S10-3 装机步骤。
