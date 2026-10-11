#!/bin/bash
# §5.4 指标复测 2026-10-11（adb 自动项；现场项另约）
# 协议同 Phase 0（docs/audit/2026-10-10-perf-baseline/README.md）：
#   空闲=前台静置 5min 后全量 dumpsys；抖动=10×30s；KWS 态=自启后≥10min 全量 dumpsys；
#   首帧=冷启动 added→first-frame drawn 设备钟差 ×5 轮。计时全设备钟。
set -u
ADB=/c/platform-tools/adb.exe
S=10.0.14.18:5555
OUT=/e/desk-pet-android/.scratch/perf-retest-20261011
mkdir -p "$OUT"

echo "[`date +%T`] 1/5 拉起 desk-pet 前台，静置 5min..."
"$ADB" -s $S shell monkey -p com.xiaoduo.deskpet -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 300

echo "[`date +%T`] 2/5 空闲态全量 dumpsys..."
"$ADB" -s $S shell dumpsys meminfo com.xiaoduo.deskpet > "$OUT/meminfo-idle-5min-20261011.txt" 2>&1

echo "[`date +%T`] 3/5 抖动采样 10×30s..."
: > "$OUT/meminfo-jitter-20261011.txt"
for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "-- t=$((i*30))s $($ADB -s $S shell date '+%H:%M:%S' | tr -d '\r')" >> "$OUT/meminfo-jitter-20261011.txt"
  "$ADB" -s $S shell dumpsys meminfo com.xiaoduo.deskpet >> "$OUT/meminfo-jitter-20261011.txt" 2>&1
  sleep 30
done

echo "[`date +%T`] 4/5 KWS 态全量 dumpsys（KWS 自启于 22:27，远超 10min）..."
"$ADB" -s $S shell dumpsys meminfo com.xiaoduo.deskpet > "$OUT/meminfo-kws-20261011.txt" 2>&1

echo "[`date +%T`] 5/5 冷启动首帧 ×5 轮..."
: > "$OUT/cold-start-20261011.txt"
for r in 1 2 3 4 5; do
  "$ADB" -s $S logcat -c 2>/dev/null
  "$ADB" -s $S shell am force-stop com.xiaoduo.deskpet
  sleep 2
  "$ADB" -s $S shell monkey -p com.xiaoduo.deskpet -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 25
  echo "## round $r" >> "$OUT/cold-start-20261011.txt"
  "$ADB" -s $S logcat -d -v year 2>/dev/null | grep -E "dual windows added|overlay first-frame drawn" >> "$OUT/cold-start-20261011.txt"
done

echo "[`date +%T`] 还原 mijijia 前台..."
"$ADB" -s $S shell monkey -p com.yinduo.compactShelf -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "[`date +%T`] 协议完成"
