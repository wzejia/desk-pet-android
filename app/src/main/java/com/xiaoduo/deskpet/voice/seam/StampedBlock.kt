package com.xiaoduo.deskpet.voice.seam

/**
 * 带采集时间戳的采麦块（S40）：时间戳在 producer（AudioCapture 读循环）侧盖戳，随块
 * 过 SharedFlow 队列——消费侧停顿恢复后仍可判定块的时代（捕获早于/晚于唤醒接缝水位），
 * 这是「陈旧块突发」治理的先决条件（无戳则消费时刻≠捕获时刻，接缝判定失真）。
 *
 * 时钟域：[System.nanoTime]（单调，JVM/Android 同域）——AudioCapture 盖戳与
 * VoiceSessionManager 记接缝水位必须同一时钟域，双方都用 nanoTime。
 */
class StampedBlock(val pcm: ByteArray, val capturedAtNs: Long)
