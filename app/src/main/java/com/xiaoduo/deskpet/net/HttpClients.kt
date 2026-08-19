package com.xiaoduo.deskpet.net

import okhttp3.OkHttpClient
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp 单工厂（知识包 §6.3 落地要点）：
 * - Proxy.NO_PROXY 直连——系统代理会把内网流式缓冲卡死（实测 TTS 经代理 read timeout）
 * - 自签证书仅对 host 白名单放行（G1：勿全局放行）；白名单外走默认校验
 * - connect 5s（内网不可达快速失败）/ read 120s（长 SSE/TTS 流）
 */
object HttpClients {

    /** 内网自签证书白名单（知识包 §6.3：166 反代 / 97 引擎 / 24 Ollama）。 */
    val TRUSTED_HOSTS: Set<String> = setOf("10.0.18.166", "10.0.18.97", "10.0.18.24")

    const val CONNECT_TIMEOUT_S = 5L
    const val READ_TIMEOUT_S = 120L

    /** 全局共享实例（单工厂）。 */
    val shared: OkHttpClient by lazy { create() }

    fun create(): OkHttpClient {
        val defaultTm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
        val tm = WhitelistTrustManager(defaultTm, TRUSTED_HOSTS)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { host, session ->
                host in TRUSTED_HOSTS || defaultVerifier.verify(host, session)
            }
            .build()
    }

    /**
     * 白名单 TrustManager：能拿到对端 host（socket/SSLEngine 变体）时，白名单内直接放行
     * （内网自签证书）；拿不到 host 或白名单外一律委托系统默认校验，不做全局放行。
     */
    private class WhitelistTrustManager(
        private val delegate: X509TrustManager,
        private val trustedHosts: Set<String>,
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket) =
            (delegate as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, socket)
                ?: delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            (delegate as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, engine)
                ?: delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            // 无 host 信息，保守走默认校验
            delegate.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket) {
            if (socket.inetAddress?.hostAddress in trustedHosts) return
            (delegate as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                ?: delegate.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
            if (engine.peerHost in trustedHosts) return
            (delegate as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                ?: delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
