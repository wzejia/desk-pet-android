package com.xiaoduo.deskpet.agui

import android.content.Context
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.net.NetEndpoints
import com.xiaoduo.deskpet.net.TokenStore
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs

/**
 * AguiClient 的 Android 装配层：把 net/ 的端点派生、TokenStore 与全局 OkHttp 单例
 * 适配进纯 Kotlin 的 AguiClient（IO 胶水，不放协议逻辑）。
 */
object AguiClientFactory {

    // CONC-7：槽位逻辑见 [ThreadIdSlots]（自 PrefsThreadIdStore 提取，注入化 JVM 可测）。


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
            listUrl = NetEndpoints.agentListUrl(platformBase),
            credentials = { NetConfig.account(app) to NetConfig.password(app) },
            tokenCache = TokenStoreCache(TokenStore(app)),
            threadStore = PrefsThreadIdStore(app),
            agentIdProvider = { Prefs.agentId(app) },
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
     * 升级兼容与槽位防混叠语义统一收进 [ThreadIdSlots]（CONC-7）。
     */
    private class PrefsThreadIdStore(context: Context) : AguiClient.ThreadIdStore {
        private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        private val slots = ThreadIdSlots(
            get = { sp.getString(it, null) },
            put = { k, v -> sp.edit().putString(k, v).apply() },
            del = { sp.edit().remove(it).apply() },
        )

        override fun load(key: String): String? = slots.load(key)

        override fun save(key: String, threadId: String) = slots.save(key, threadId)

        override fun remove(key: String) = slots.remove(key)

        companion object {
            private const val FILE = "xiaoduo_agui"
        }
    }
}

/**
 * threadId 槽位逻辑（CONC-7，纯逻辑注入化，JVM 可测；自 PrefsThreadIdStore 提取）：
 * - DEFAULT 键即 legacy 槽（"thread_id"，板端存量数据兼容：语音路径/旧版直接读写）；
 * - 非默认键各占 "thread_id_{key}" 槽；首载未命中时把 legacy **copy**（不 move）到自己
 *   槽位并落持久迁移旗（"thread_id_legacy_claimed"）——默认会话的服务端连续性不动，
 *   旗后的新会话各自 createThreadId，不再 adopt（防两会话共享同一服务端 thread）；
 * - promotion 的 check-then-act 收进同一把锁。
 */
internal class ThreadIdSlots(
    private val get: (String) -> String?,
    private val put: (String, String) -> Unit,
    private val del: (String) -> Unit,
) {
    private val lock = Any()

    fun load(key: String): String? = synchronized(lock) {
        get(slot(key))?.takeIf { it.isNotBlank() }?.let { return it }
        if (key == AguiClient.DEFAULT_THREAD_KEY) return null
        // CONC-7：legacy 只被认领一次（持久旗，跨进程重启有效）——旗后的新会话各自
        // createThreadId，不再 adopt（旧 move 语义会偷默认会话 threadId / 让两会话共享
        // 同一服务端 thread）。
        if (get(CLAIM_KEY) != null) return null
        val legacy = get(LEGACY_KEY)?.takeIf { it.isNotBlank() } ?: return null
        put(slot(key), legacy) // copy 不 move：默认会话（读写 legacy 槽）不受影响
        put(CLAIM_KEY, "1")
        legacy
    }

    fun save(key: String, threadId: String) {
        synchronized(lock) { put(slot(key), threadId) }
    }

    fun remove(key: String) {
        synchronized(lock) { del(slot(key)) }
    }

    private fun slot(key: String): String =
        if (key == AguiClient.DEFAULT_THREAD_KEY) LEGACY_KEY else "thread_id_$key"

    companion object {
        const val LEGACY_KEY = "thread_id"

        /** 一次性迁移持久旗：首个非默认会话 adopt legacy 时落笔（跨进程重启有效）。 */
        const val CLAIM_KEY = "thread_id_legacy_claimed"
    }
}
