package com.xiaoduo.deskpet.net

import android.content.Context
import android.content.SharedPreferences
import com.xiaoduo.deskpet.util.SecurePrefStore

/**
 * 平台 token 条目：36 字符 UUID + 获取时间戳（Spec 数据模型 TokenEntry）。
 * SEC-3(ocr[144])：覆写 toString 脱敏——「严禁入日志」在 data class 默认 toString /
 * 调试输出 / 崩溃现场同样成立，原始 token 不得随对象打印泄露。
 */
data class TokenEntry(val token: String, val obtainedAtMs: Long) {
    override fun toString(): String = "TokenEntry(token=${mask(token)}, obtainedAtMs=$obtainedAtMs)"

    companion object {
        /** 脱敏：保留前 4 字符便于人工比对；空/过短 token 只出占位符，不泄原文。 */
        fun mask(token: String): String =
            if (token.length >= 8) "${token.take(4)}…(masked)" else "<masked>"
    }
}

/**
 * token 新鲜度纯逻辑（零 Android import，时钟注入，JVM 可测）。
 * 契约：知识包 §2.2——RuoYi 30min 过期，客户端缓存 25 分钟留 5min 余量。
 */
object TokenPolicy {
    const val TTL_MS: Long = 25L * 60 * 1000

    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 36 字符 UUID 格式校验（知识包：code==200 取 36 字符 UUID token）。 */
    fun isValidToken(token: String): Boolean = token.length == 36 && UUID_REGEX.matches(token)

    /** null 条目 / 非法格式 / 超龄 / 时钟回拨（负年龄）一律视为不新鲜。 */
    fun isFresh(entry: TokenEntry?, nowMs: Long): Boolean {
        if (entry == null || !isValidToken(entry.token)) return false
        val age = nowMs - entry.obtainedAtMs
        return age in 0 until TTL_MS
    }
}

/** token 持久化（SEC-3 迁加密库，迁移收敛在本类内部，方法签名零变化）；写日志严禁带 token 明文。 */
class TokenStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // 新库 xiaoduo_net_secure（AES256-GCM）；旧明文库 xiaoduo_net 只读回落+搬迁写回、永不删除
    //（同文件旧库仍被禁改的 NetConfig 直读——只搬不删，NetConfig 零影响）。
    private val sp: SharedPreferences = SecurePrefStore.migrating(
        context.applicationContext, SECURE_FILE, FILE,
    )

    fun load(): TokenEntry? {
        val token = sp.getString(KEY_TOKEN, null) ?: return null
        val at = sp.getLong(KEY_OBTAINED_AT, 0L)
        return TokenEntry(token, at)
    }

    /** 保存新 token 并以注入时钟打时间戳。 */
    fun saveFresh(token: String): TokenEntry {
        val entry = TokenEntry(token, clock())
        sp.edit().putString(KEY_TOKEN, entry.token).putLong(KEY_OBTAINED_AT, entry.obtainedAtMs).apply()
        return entry
    }

    /** 缓存未过期则返回，否则 null（调用方透明重登）。 */
    fun cached(): TokenEntry? = load().takeIf { TokenPolicy.isFresh(it, clock()) }

    fun clear() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_OBTAINED_AT).apply()
    }

    companion object {
        private const val FILE = "xiaoduo_net"
        private const val SECURE_FILE = "xiaoduo_net_secure"
        private const val KEY_TOKEN = "platform_token"
        private const val KEY_OBTAINED_AT = "platform_token_obtained_at"
    }
}
