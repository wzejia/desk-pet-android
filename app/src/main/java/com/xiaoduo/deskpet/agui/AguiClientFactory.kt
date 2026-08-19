package com.xiaoduo.deskpet.agui

import android.content.Context
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.NetEndpoints
import com.xiaoduo.deskpet.net.TokenStore
import com.xiaoduo.deskpet.util.PetLogger

/**
 * AguiClient 的 Android 装配层：把 net/ 的端点派生、TokenStore 与全局 OkHttp 单例
 * 适配进纯 Kotlin 的 AguiClient（IO 胶水，不放协议逻辑）。
 */
object AguiClientFactory {

    /**
     * @param logger 默认落 PetLogger（tag=AguiClient）；UI 自检可叠加状态回调。
     */
    fun create(
        context: Context,
        logger: (String) -> Unit = { msg -> PetLogger.i("AguiClient", msg) },
    ): AguiClient {
        val app = context.applicationContext
        val platformBase = NetConfig.platformBase(app)
        return AguiClient(
            http = HttpClients.shared,
            loginUrl = NetEndpoints.loginUrl(platformBase),
            streamUrl = NetEndpoints.agentStreamUrl(platformBase),
            uploadUrl = NetEndpoints.uploadUrl(platformBase),
            credentials = { NetConfig.account(app) to NetConfig.password(app) },
            tokenCache = TokenStoreCache(TokenStore(app)),
            threadStore = PrefsThreadIdStore(app),
            logger = logger,
        )
    }

    /** net/TokenStore（SharedPreferences + TokenPolicy 25min TTL）→ AguiClient.TokenCache。 */
    private class TokenStoreCache(private val store: TokenStore) : AguiClient.TokenCache {
        override fun cached(): String? = store.cached()?.token
        override fun saveFresh(token: String) {
            store.saveFresh(token)
        }
        override fun clear() = store.clear()
    }

    /**
     * threadId 本地持久化（独立 prefs 文件，不与端点配置混存；按会话 key 存取）。
     * 升级兼容：旧版单会话键 "thread_id" 在首个非默认 key 未命中时一次性提升——
     * 服务端记忆随旧会话无缝续接（旧历史即当前会话），提升后删除旧键，
     * 后续新会话各自 createThreadId。默认 key（语音路径等）直接读写旧键，行为不变。
     */
    private class PrefsThreadIdStore(context: Context) : AguiClient.ThreadIdStore {
        private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun load(key: String): String? {
            sp.getString(storageKey(key), null)?.takeIf { it.isNotBlank() }?.let { return it }
            if (key != AguiClient.DEFAULT_THREAD_KEY) {
                val legacy = sp.getString(LEGACY_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
                sp.edit().putString(storageKey(key), legacy).remove(LEGACY_KEY).apply()
                return legacy
            }
            return null
        }

        override fun save(key: String, threadId: String) {
            sp.edit().putString(storageKey(key), threadId).apply()
        }

        override fun remove(key: String) {
            sp.edit().remove(storageKey(key)).apply()
        }

        private fun storageKey(key: String): String =
            if (key == AguiClient.DEFAULT_THREAD_KEY) LEGACY_KEY else "thread_id_$key"

        companion object {
            private const val FILE = "xiaoduo_agui"
            private const val LEGACY_KEY = "thread_id"
        }
    }
}
