package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S57 mic_source 漂移告警纯函数锚（只搬入诊断面，WARN 接线在 readLoop 采麦启动
 * 日志后）。
 *
 * 档位口径演进：②C 裁决（2026-10-20）曾剔除 fork 第 4 档 MIC（mode=3）断言 4 条；
 * 2026-10-21 用户拍板恢复（AGC 增益收益验证需 MIC 低电平档，nf~0.003、g 3.6-8.0 域，
 * 参考 docs/verification/agc-tap-to-talk-acceptance-20261019.md），锚组 4 原样移回，
 * 设置页采麦源同步恢复 fork 可点击循环（S57 只读锁定解除，漂移防线由本类锚组
 * 1/2 的 WARN 网继续兜底）。
 *
 * 被测对象：[AudioCapture.expectedSourceFor] / [AudioCapture.isSourceDrift]
 * （companion 纯函数，零 Android import，JVM 直接驱动）+ [Prefs.coerceMicSourceMode]
 * （setMicSourceMode 写路径收口的纯函数面；Prefs object 的 const 编译期内联、
 * &lt;clinit&gt; 为空，JVM 加载安全，同 [AudioCapture] 既有实证）。
 * readLoop 的 WARN 接线属 Android 路径 JVM 不可达（接线本体已存在，见
 * AudioCapture.readLoop 采麦启动日志后），回归靠变异锚（反转判定 →
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

    // ---- 常数表锁：pref 档位 → 采麦源映射（0→7 / 1→6 / 2→9 / 3→1，未知档归 7）----

    @Test
    fun `pref mode to source constant table locked`() {
        assertEquals(7, AudioCapture.expectedSourceFor(0)) // VOICE_COMMUNICATION
        assertEquals(6, AudioCapture.expectedSourceFor(1)) // VOICE_RECOGNITION
        assertEquals(9, AudioCapture.expectedSourceFor(2)) // UNPROCESSED
        assertEquals(7, AudioCapture.expectedSourceFor(99)) // 未知档归默认通话源（同旧 when-else 语义）
    }

    // ---- 锚组 4：第 4 档 MIC（板载麦链路；2026-10-17 17.242 A/B 定案——同一板载麦同一
    // ---- 时刻讯飞（MIC 链）能出字而 7/9/6 链仅底噪级，es8316 未死、衰的是三链预处理）----

    // 映射锚（变异锚 M1 本体）：3→1（MIC，API 1 常数）；删 `3 -> 1` 行 → else 归 7 → 本测红
    @Test
    fun `pref mode 3 maps to MIC source`() {
        assertEquals(1, AudioCapture.expectedSourceFor(3)) // MIC（板载麦链路）
    }

    // mode=3 漂移判定随映射单点走（usedSource=7 时告警、=1 时不告警；删映射 → 本组双红）
    @Test
    fun `mode 3 drift fires on non-mic source and stays silent on mic`() {
        assertTrue(AudioCapture.isSourceDrift(prefMode = 3, activeSource = 7))
        assertTrue(AudioCapture.isSourceDrift(prefMode = 3, activeSource = 0))
        // 命中 MIC 链不告警（漂移判定口径=期望源比对，同值即静默。DEFAULT=0≠MIC=1：
        // source=1 即首选 MIC 成功，金线无歧义；source=0+WARN 才是 fallback DEFAULT 形态）
        assertFalse(AudioCapture.isSourceDrift(prefMode = 3, activeSource = 1))
    }

    // setter 边界（变异锚 M2 本体）：第 4 档写路径可过 coerce、脏值双向收口
    @Test
    fun `mic source mode coercion admits mode 3 and clamps dirty values`() {
        assertEquals(3, Prefs.coerceMicSourceMode(3)) // MIC 档通过（coerce 上界回 2 → 红）
        assertEquals(0, Prefs.coerceMicSourceMode(-1)) // 下界收口
        assertEquals(3, Prefs.coerceMicSourceMode(99)) // 上界收口（脏值归 MIC 档而非 2）
    }

    // 档位常数锁：Prefs↔映射↔SettingsScreen 标签三方对齐的根基（键集不变，仅扩档位域）
    @Test
    fun `mic source mode constants locked 0-3`() {
        assertEquals(0, Prefs.MIC_SOURCE_COMMUNICATION)
        assertEquals(1, Prefs.MIC_SOURCE_RECOGNITION)
        assertEquals(2, Prefs.MIC_SOURCE_UNPROCESSED)
        assertEquals(3, Prefs.MIC_SOURCE_MIC)
    }
}
