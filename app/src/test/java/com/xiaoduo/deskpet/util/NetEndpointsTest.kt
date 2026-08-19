package com.xiaoduo.deskpet.util

import com.xiaoduo.deskpet.net.NetEndpoints
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 端点派生（知识包 §6.1/§2.2：登录 :8080，Agent 必须 443 反代）。
 */
class NetEndpointsTest {

    @Test
    fun `login url appends path to platform base`() {
        assertEquals(
            "http://10.0.18.166:8080/login",
            NetEndpoints.loginUrl("http://10.0.18.166:8080")
        )
        assertEquals(
            "http://10.0.18.166:8080/login",
            NetEndpoints.loginUrl("http://10.0.18.166:8080/")
        )
    }

    @Test
    fun `agent stream goes through 443 reverse proxy not 8080`() {
        assertEquals(
            "https://10.0.18.166/ai-server/agent/stream",
            NetEndpoints.agentStreamUrl("http://10.0.18.166:8080")
        )
    }

    @Test
    fun `agent stream falls back to base plus path when host unparsable`() {
        assertEquals(
            "not a url/ai-server/agent/stream",
            NetEndpoints.agentStreamUrl("not a url")
        )
    }

    @Test
    fun `upload goes through 443 reverse proxy not 8080`() {
        assertEquals(
            "https://10.0.18.166/ai-server/common/file/upload",
            NetEndpoints.uploadUrl("http://10.0.18.166:8080")
        )
    }

    @Test
    fun `upload falls back to base plus path when host unparsable`() {
        assertEquals(
            "not a url/ai-server/common/file/upload",
            NetEndpoints.uploadUrl("not a url")
        )
    }

    @Test
    fun `tts urls derive from tts base`() {
        assertEquals(
            "http://10.0.18.97:9803/audio/tts/stream",
            NetEndpoints.ttsStreamUrl("http://10.0.18.97:9803")
        )
        assertEquals(
            "http://10.0.18.97:9803/audio/tts/synthesize",
            NetEndpoints.ttsSynthesizeUrl("http://10.0.18.97:9803/")
        )
    }
}
