package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S57 mic_source 漂移告警纯函数锚（上游 1723d7b 裁剪版：保采麦源诊断基建
 * 不锁设置，只告警）。
 *
 * ②C 裁决遵守：fork 本地第 4 档 MIC（mode=3）相关断言 **已剔除并逐条记录**（详
 * 见 T1-progress.md Layer 4 章节注记）：
 * 1. `pref mode 3 maps to MIC source`
 * 2. `mode 3 drift fires on non-mic source and stays silent on mic`
 * 3. `mic source mode coercion admits mode 3 and clamps dirty values`
 * 4. `mic source mode constants locked 0-3`
 *
 * 被测对象：[AudioCapture.expectedSourceFor] / [AudioCapture.isSourceDrift]
 * （companion 纯函数，零 Android import，JVM 直接驱动）。
 * readLoop 里的 WARN 接线属 Android 路径 JVM 不可达，回归靠变异锚（反转判定 →
 * 本类漂移组红）+ 真机日志取证。
 *
 * 206 板当日实踩形态（上游 e5b6e11/593595f gotcha）：pref 手改档位后活性源漂移——
 * 环境噪声结论必须先排除配置漂移，同一个 0.19 先查 pref 再怪房间。
 */
class AudioCaptureSourceDriftTest {

    // ---- 锚组 1：漂移触发（期望源 ≠ active source，采麦启动应打 WARN 含两值）----

    @Test
    fun `drift fires when preferred source falls back to DEFAULT`() {
        // 板子当日形态：mode=0 期望 source 7(VOICE_COMMUNICATION)，初始化失败 fallback 0(DEFAULT)
        assertTrue(AudioCapture.isSourceDrift(prefMode = 0, activeSource = 0))
    }

    @Test
    fun `drift fires for recognition and unprocessed modes on fallback or cross-source drift`() {
        assertTrue(AudioCapture.isSourceDrift(prefMode = 1, activeSource = 0))
        assertTrue(AudioCapture.isSourceDrift(prefMode = 2, activeSource = 0))
        // 跨档互串（活性源落到另一非期望档）同为漂移
        assertTrue(AudioCapture.isSourceDrift(prefMode = 1, activeSource = 9))
        assertTrue(AudioCapture.isSourceDrift(prefMode = 2, activeSource = 7))
    }

    // ---- 锚组 2：一致不告警（三档各自命中期望源）----

    @Test
    fun `no drift when active source matches pref for all three modes`() {
        assertFalse(AudioCapture.isSourceDrift(prefMode = 0, activeSource = 7))
        assertFalse(AudioCapture.isSourceDrift(prefMode = 1, activeSource = 6))
        assertFalse(AudioCapture.isSourceDrift(prefMode = 2, activeSource = 9))
    }

    // ---- 常数表锁：pref 档位 → 采麦源映射（0→7 / 1→6 / 2→9，未知档归 7）----

    @Test
    fun `pref mode to source constant table locked`() {
        assertEquals(7, AudioCapture.expectedSourceFor(0)) // VOICE_COMMUNICATION
        assertEquals(6, AudioCapture.expectedSourceFor(1)) // VOICE_RECOGNITION
        assertEquals(9, AudioCapture.expectedSourceFor(2)) // UNPROCESSED
        assertEquals(7, AudioCapture.expectedSourceFor(99)) // 未知档归默认通话源（同旧 when-else 语义）
    }
}
