package com.xiaoduo.deskpet.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioCapture 纯逻辑单测（S4 P3 收尾）：
 * - 溢出策略钉死：块流满则丢最旧（DROP_OLDEST），与 VadDetector pre-roll deque 同策；
 *   变异反向：若改回默认 SUSPEND（丢新块），慢消费者收到的将是 [0..64] 而非 [0]+[36..99]，本测试变红。
 * - 启动竞态钉死：pumpBlocks 判活只用协程体自带 isActive；UNDISPATCHED 启动（协程体在
 *   launch 返回前同步开跑 = 任何「先拿 Job 引用再判活」写法此刻必死）仍正常泵块。
 */
class AudioCapturePumpTest {

    private fun markerBlock(marker: Int) = ByteArray(VadDetector.BLOCK_BYTES) { marker.toByte() }

    /** 有界等待消费者追平（Unconfined 下通常同步完成，yield 兜底防调度差异）。 */
    private suspend fun awaitUntil(deadlineIters: Int = 1000, cond: () -> Boolean) {
        var spins = 0
        while (!cond() && spins < deadlineIters) {
            yield()
            spins++
        }
    }

    @Test
    fun `blocks flow overflow drops oldest keeping freshest and tryEmit never fails`() = runBlocking {
        val flow = AudioCapture.newBlocksFlow()
        val received = mutableListOf<Int>()
        val firstReceived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            flow.collect { b ->
                received.add(b.pcm[0].toInt() and 0xFF)
                firstReceived.complete(Unit)
                release.await() // 首块后装死，模拟消费侧 stall
            }
        }

        assertTrue(flow.tryEmit(StampedBlock(markerBlock(0), 0L)))
        firstReceived.await()
        assertEquals(listOf(0), received)

        // stall 期间连发 99 块：缓冲 64，DROP_OLDEST 应只留最新 64 块（36..99）
        for (i in 1..99) {
            assertTrue("DROP_OLDEST 下 tryEmit 不得失败（i=$i）", flow.tryEmit(StampedBlock(markerBlock(i), i.toLong())))
        }
        release.complete(Unit)
        awaitUntil { received.size == 65 }
        job.cancel()

        assertEquals((listOf(0) + (36..99).toList()), received)
    }

    @Test
    fun `pump loop starts pumping even when coroutine body runs before launch returns`() = runBlocking {
        // critic-G 启动竞态的确定性复现面：UNDISPATCHED = 协程体在 launch 返回前同步执行，
        // 此刻调用方手里的 Job 引用尚未赋值——旧写法 while(job?.isActive==true) 读到 null
        // 一次循环不进（0 emit），本测试断言新写法照常泵出 3 块。
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val emitted = mutableListOf<ByteArray>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            pumpBlocks(
                read = { buf, off, _ ->
                    val chunk = inbox.receive()
                    chunk.copyInto(buf, off)
                    chunk.size
                },
                emit = { emitted.add(it) },
            )
        }
        repeat(3) { inbox.send(markerBlock(it + 1)) }
        awaitUntil { emitted.size == 3 }

        assertEquals(3, emitted.size)
        assertTrue(emitted[0].contentEquals(markerBlock(1)))
        assertTrue(emitted[2].contentEquals(markerBlock(3)))
        job.cancel()
        assertNotNull(withTimeoutOrNull(2000) { job.join() })
    }

    @Test
    fun `partial reads accumulate until full block and non-positive reads emit nothing`() = runBlocking {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val emitted = mutableListOf<ByteArray>()
        val job = launch(Dispatchers.Unconfined) {
            pumpBlocks(
                read = { buf, off, _ ->
                    val chunk = inbox.receive()
                    chunk.copyInto(buf, off)
                    chunk.size
                },
                emit = { emitted.add(it) },
            )
        }
        val half = VadDetector.BLOCK_BYTES / 2
        inbox.send(ByteArray(half) { 7 })          // 半块：不 emit
        inbox.send(ByteArray(0))                    // n=0：跳过，不 emit
        inbox.send(ByteArray(half) { 8 })          // 补齐一块 → emit 1
        awaitUntil { emitted.size == 1 }
        assertEquals(1, emitted.size)
        assertEquals(VadDetector.BLOCK_BYTES, emitted[0].size)
        assertEquals(7.toByte(), emitted[0][0])
        assertEquals(8.toByte(), emitted[0][half])

        inbox.send(markerBlock(9))                  // 整块一次到位 → emit 2
        awaitUntil { emitted.size == 2 }
        assertEquals(2, emitted.size)
        assertTrue(emitted[1].contentEquals(markerBlock(9)))
        job.cancel()
        assertNotNull(withTimeoutOrNull(2000) { job.join() })
    }

    /** ROB-2 红测：read 持续返回非正（AudioRecord 卡错误态）时泵必须退出，
     *  不得零退避满速自旋到取消为止。修复前：无限 continue → 5s 内 reads 远超阈值 → 红。 */
    @Test
    fun `ROB-2 persistent non-positive reads terminate the pump instead of spinning`() = runBlocking {
        var reads = 0
        withTimeoutOrNull(5000) {
            pumpBlocks(
                read = { _, _, _ -> reads++; 0 },
                emit = { },
            )
        }
        assertTrue(
            "持续读失败须有出口（5s 内 reads=$reads，期望 ≤ 失败阈值）",
            reads <= 50,
        )
    }

    /** ROB-2 反例：孤立失败（失败<阈值即恢复）不得误杀泵——0 与有效数据交替照常出块。 */
    @Test
    fun `ROB-2 isolated failures do not abort the pump`() = runBlocking {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val emitted = mutableListOf<ByteArray>()
        val job = launch(Dispatchers.Unconfined) {
            pumpBlocks(
                read = { buf, off, _ ->
                    val chunk = inbox.receive()
                    chunk.copyInto(buf, off)
                    chunk.size
                },
                emit = { emitted.add(it) },
            )
        }
        val half = VadDetector.BLOCK_BYTES / 2
        // 失败-数据-失败-失败-数据 交替：每次恢复即清零计数，泵不得退出
        inbox.send(ByteArray(0)); inbox.send(ByteArray(half) { 1 })
        inbox.send(ByteArray(0)); inbox.send(ByteArray(0)); inbox.send(ByteArray(half) { 2 })
        awaitUntil { emitted.size == 1 }
        assertEquals(1, emitted.size)
        assertEquals(1.toByte(), emitted[0][0])
        assertEquals(2.toByte(), emitted[0][half])
        job.cancel()
        assertNotNull(withTimeoutOrNull(2000) { job.join() })
    }

    @Test
    fun `pump loop stops promptly on cancellation while blocked in read`() = runBlocking {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val emitted = mutableListOf<ByteArray>()
        val job = launch(Dispatchers.Unconfined) {
            pumpBlocks(
                read = { buf, off, _ ->
                    val chunk = inbox.receive()
                    chunk.copyInto(buf, off)
                    chunk.size
                },
                emit = { emitted.add(it) },
            )
        }
        inbox.send(markerBlock(1))
        awaitUntil { emitted.size == 1 }
        assertEquals(1, emitted.size)

        job.cancel() // 此刻正阻塞在 inbox.receive()
        assertNotNull("取消后须及时退出，不得挂死", withTimeoutOrNull(2000) { job.join() })
        assertTrue(job.isCompleted)
        assertEquals(1, emitted.size)
    }
}
