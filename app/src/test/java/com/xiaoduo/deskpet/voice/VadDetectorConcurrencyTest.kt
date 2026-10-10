package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.VadDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CONC-1〔审计 ocr-419 + 人工 F-VSM-3〕：VadDetector 跨线程无同步。
 *
 * 实调用拓扑（VoiceSessionManager）：feed() 同时来自采麦收集协程（Dispatchers.Default
 * routeBlock→VAD 分支）与 stopListening() 调用线程（按住松开喂静音块触发判停，
 * VSM:583-589）——LISTENING 态两者天然并发；start/stop/raiseNoiseFloorFromLastUtterance
 * 另可达管线 IO 线程。内部 ArrayList/ArrayDeque + 非 volatile Double RMW 全裸奔。
 *
 * 压测锁定：双线程并发 feed 不得抛容器损坏异常；start/stop/raise 与 feed 并发同理。
 * 复诊备注（分级分歧）：rk3568 实为 arm64-v8a（abiFilters 双 64 位），非 volatile
 * Double 撕裂在 64 位 HotSpot 上不成立；保留的是容器损坏与 tick 状态机竞态——
 * 按容器损坏可能保 P2，可复核降级 P3。
 */
class VadDetectorConcurrencyTest {

    private fun block(value: Int) = ShortArray(VadDetector.BLOCK_SAMPLES) { value.toShort() }

    @Test
    fun `CONC-1 concurrent feed from two threads never corrupts internal state`() {
        val vad = VadDetector()
        val errors = CopyOnWriteArrayList<Throwable>()
        val stop = AtomicBoolean(false)
        val feeder = { value: Int ->
            java.lang.Thread {
                try {
                    val b = block(value)
                    while (!stop.get()) {
                        vad.feed(b)
                        if (!vad.isActive) vad.start()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.apply { start() }
        }
        val threads = listOf(feeder(1000), feeder(100))
        val manager = java.lang.Thread {
            try {
                while (!stop.get()) {
                    vad.stop()
                    vad.start()
                    vad.raiseNoiseFloorFromLastUtterance()
                    vad.currentEndThreshold()
                    vad.currentNoiseFloor()
                    vad.currentMaxLevel()
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }.apply { start() }
        Thread.sleep(600)
        stop.set(true)
        threads.forEach { it.join(3000) }
        manager.join(3000)
        assertTrue("并发 feed/start/stop 不得损坏内部状态: $errors", errors.isEmpty())
    }
}
