package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.api.AsrContract
import com.xiaoduo.deskpet.voice.client.AsrClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AsrClient 可取消单测（spec §5.4 性能抓手：barge-in 封轮链同步取消在途识别）。
 *
 * 变异锚（删守卫必红）：
 * - 变异体 A：cancelInFlight 空转（不调 Call.cancel）→ 用例 1 future.get(3s) 超时红
 *   （无取消时 10s headers 延迟不中断）；
 * - 变异体 B：删 inFlightCall 注册（set）→ 同上红（取消打到空引用 no-op）。
 * 行为红线：正常识别语义零变化（用例 3 锁）；取消仅在显式调用时发生（用例 2 无在途 no-op）。
 */
class AsrClientCancelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // 取消用例中客户端断开后，服务端的延迟响应任务仍挂在派发队列上，
        // shutdown 会「等队清空」而放弃——断开本身是取消的预期产物，吞掉该噪音。
        runCatching { server.shutdown() }
    }

    private fun client() = AsrClient(
        http = OkHttpClient(),
        endpoint = server.url("/audio/asr/transcribe").toString(),
        logger = {},
    )

    @Test
    fun `取消在途识别——请求立即终止返失败`() {
        // headers 延迟 10s：不取消则 transcribe 挂满 10s；取消则 IOException 立即落地
        server.enqueue(
            MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"你好"}}""")
                .setHeadersDelay(10, TimeUnit.SECONDS),
        )
        val asr = client()
        val exec = Executors.newSingleThreadExecutor()
        try {
            // 显式 Callable：submit lambda 裸传会 SAM 解析到 Runnable 丢返回值（future.get 恒 null）
            val future = exec.submit(Callable { asr.transcribe(ByteArray(320)) })
            assertNotNull("请求应已到达服务端", server.takeRequest(5, TimeUnit.SECONDS))
            asr.cancelInFlight() // 显式取消在途识别
            val start = System.nanoTime()
            val r = future.get(3, TimeUnit.SECONDS) // 变异体：不取消 → 10s 延迟 → 此处超时红
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("取消应走既有失败路径 r=$r", r is AsrContract.Result.Error)
            assertTrue("取消应立即终止（实测 ${elapsedMs}ms）", elapsedMs < 2_000)
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `无在途识别时取消为 no-op 不抛`() {
        val asr = client()
        asr.cancelInFlight() // 无在途：静默 no-op
    }

    @Test
    fun `正常识别语义零变化——取消挂点不干扰成功路径`() {
        server.enqueue(MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":" 你好世界 "}}"""))
        val asr = client()
        val r = asr.transcribe(ByteArray(320))
        assertTrue(r is AsrContract.Result.Success)
        // 已收尾后再 cancel：幂等无害（Call.cancel 对完成调用为 no-op）
        asr.cancelInFlight()
        assertFalse((r as AsrContract.Result.Success).text.isEmpty())
    }
}
