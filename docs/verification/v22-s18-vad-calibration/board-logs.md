# rk3568 板子声学诊断日志存证（2026-08-29/08-31）

## 第1轮-校准生效前
```
08-29 20:27:06.329 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#6, fed=110242, gated=10)
08-29 20:27:15.265 23014 23210 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:16.089 23014 23157 I VoiceSession: ASR text: 当前有哪些档案？
08-29 20:27:27.847 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#7, fed=110373, gated=10)
08-29 20:27:36.770 23014 23157 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:37.741 23014 25863 I VoiceSession: ASR text: 我要查看合同档案。
08-29 20:27:49.729 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#8, fed=110508, gated=10)
08-29 20:27:58.665 23014 25863 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:59.413 23014 25864 I VoiceSession: ASR text: 查看第一个档案。
08-29 20:28:15.336 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#9, fed=110680, gated=10)
08-29 20:28:24.278 23014 23210 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:28:25.047 23014 25863 I VoiceSession: ASR text: 查看第二个档案的信息。
08-29 20:28:47.426 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#10, fed=110917, gated=10)
08-29 20:28:56.366 23014 25857 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:28:57.115 23014 25857 I VoiceSession: ASR text: 查询第三个档案信息。
08-29 20:30:49.568 23014 25926 I VoiceSession: VAD utterance, 132800 samples, maxLevel=0.3358
08-29 20:34:21.323 23014 23014 I Settings: kws calibration: n=50 median=0.048729096 p95=0.04957933 max=0.050680205 suggest=0.05
08-29 20:47:38.554 26263 26331 I AudioCapture: capture start, source=7, 16kHz/mono/Int16, block=3200B
```

## 第2轮-受控实验
```
08-29 20:27:06.329 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#6, fed=110242, gated=10)
08-29 20:27:15.265 23014 23210 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:16.089 23014 23157 I VoiceSession: ASR text: 当前有哪些档案？
08-29 20:27:27.847 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#7, fed=110373, gated=10)
08-29 20:27:36.770 23014 23157 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:37.741 23014 25863 I VoiceSession: ASR text: 我要查看合同档案。
08-29 20:27:49.729 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#8, fed=110508, gated=10)
08-29 20:27:58.665 23014 25863 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:27:59.413 23014 25864 I VoiceSession: ASR text: 查看第一个档案。
08-29 20:28:15.336 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#9, fed=110680, gated=10)
08-29 20:28:24.278 23014 23210 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:28:25.047 23014 25863 I VoiceSession: ASR text: 查看第二个档案的信息。
08-29 20:28:47.426 23014 23093 I KwsEngine: keyword hit: 小朵小朵 (#10, fed=110917, gated=10)
08-29 20:28:56.366 23014 25857 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 20:28:57.115 23014 25857 I VoiceSession: ASR text: 查询第三个档案信息。
08-29 20:30:49.568 23014 25926 I VoiceSession: VAD utterance, 132800 samples, maxLevel=0.3358
08-29 20:34:21.323 23014 23014 I Settings: kws calibration: n=50 median=0.048729096 p95=0.04957933 max=0.050680205 suggest=0.05
08-29 20:47:38.554 26263 26331 I AudioCapture: capture start, source=7, 16kHz/mono/Int16, block=3200B
```

## 第3轮-换源后
```
08-29 21:10:06.686 26994 27048 I AudioCapture: capture start, source=6, 16kHz/mono/Int16, block=3200B
08-29 21:11:07.200 26994 26994 I Settings: kws calibration: n=50 median=0.04612436 p95=0.047189154 max=0.04853461 suggest=0.05 vad=0.07550264596939088
08-29 21:11:25.589 26994 26994 I Settings: kws gate written: 0.05, vad level written: 0.07550264894962311
08-29 21:11:38.501 26994 27043 I KwsEngine: keyword hit: 小朵小朵 (#1, fed=37, gated=880)
08-29 21:11:47.543 26994 27049 I VoiceSession: VAD utterance, 132800 samples, maxLevel=1.0000
08-29 21:11:49.478 26994 27045 I VoiceSession: ASR text: 当前有哪些项目档案？
```
