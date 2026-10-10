package com.xiaoduo.deskpet.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * SEC-3（ocr[145]，spec 附录 B 3 已批准「效果优先」）：EncryptedSharedPreferences 透明迁移。
 *
 * 读路径：加密新库优先 → miss 回落旧明文库并搬迁写回（=标记）→ 两库皆空用默认。
 * 旧库数据只能搬不能删（红线：板子存量数据在用，且 xiaoduo_net 旧库仍被禁改的
 * NetConfig 直读）；回读兼容永久保留，无清库步骤。
 *
 * 分层：[SecurePrefStrategy] 纯 Kotlin（零 Android import，JVM 可测，挂变异锚）；
 * [MigratingSharedPreferences] 只依赖 SharedPreferences 接口（JVM 可测）；
 * [SecurePrefStore] 真正触碰 Android Keystore / security-crypto（JVM 测不了，
 * 跨重启真机集成验证不在 P3-T4，挂账主对话部署批——spec 附录 B 3「跨重启验证」）。
 */

/** 迁移读裁决（纯函数）：新库优先 → 旧库回落（触发搬迁）→ 默认。 */
object SecurePrefStrategy {
    enum class Source { NEW, LEGACY, DEFAULT }

    data class Decision(val source: Source, val migrate: Boolean)

    fun decide(hasNew: Boolean, hasLegacy: Boolean): Decision = when {
        hasNew -> Decision(Source.NEW, migrate = false)
        hasLegacy -> Decision(Source.LEGACY, migrate = true) // 回落读必须带搬迁
        else -> Decision(Source.DEFAULT, migrate = false) // 默认值不落库
    }
}

/**
 * 「新加密库 + 旧明文库」迁移读包装：读按 [SecurePrefStrategy] 裁决，LEGACY 命中即
 * 搬迁写回新库（标记）；写只进新库；旧库永不写永不删。
 * 对外即 [SharedPreferences]，消费方（Prefs/TokenStore 的调用点）签名零变化。
 */
class MigratingSharedPreferences(
    private val secure: SharedPreferences,
    private val legacy: SharedPreferences,
) : SharedPreferences {

    override fun getString(key: String, defValue: String?): String? =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getString(key, defValue)
            SecurePrefStrategy.Source.LEGACY ->
                legacy.getString(key, null)?.also { migrated ->
                    secure.edit().putString(key, migrated).apply()
                } ?: defValue
            SecurePrefStrategy.Source.DEFAULT -> defValue
        }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getStringSet(key, defValues)
            SecurePrefStrategy.Source.LEGACY ->
                legacy.getStringSet(key, null)?.also { migrated ->
                    secure.edit().putStringSet(key, migrated).apply()
                } ?: defValues
            SecurePrefStrategy.Source.DEFAULT -> defValues
        }

    override fun getInt(key: String, defValue: Int): Int =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getInt(key, defValue)
            SecurePrefStrategy.Source.LEGACY -> legacy.getInt(key, defValue).also { migrated ->
                secure.edit().putInt(key, migrated).apply()
            }
            SecurePrefStrategy.Source.DEFAULT -> defValue
        }

    override fun getLong(key: String, defValue: Long): Long =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getLong(key, defValue)
            SecurePrefStrategy.Source.LEGACY -> legacy.getLong(key, defValue).also { migrated ->
                secure.edit().putLong(key, migrated).apply()
            }
            SecurePrefStrategy.Source.DEFAULT -> defValue
        }

    override fun getFloat(key: String, defValue: Float): Float =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getFloat(key, defValue)
            SecurePrefStrategy.Source.LEGACY -> legacy.getFloat(key, defValue).also { migrated ->
                secure.edit().putFloat(key, migrated).apply()
            }
            SecurePrefStrategy.Source.DEFAULT -> defValue
        }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        when (SecurePrefStrategy.decide(secure.contains(key), legacy.contains(key)).source) {
            SecurePrefStrategy.Source.NEW -> secure.getBoolean(key, defValue)
            SecurePrefStrategy.Source.LEGACY -> legacy.getBoolean(key, defValue).also { migrated ->
                secure.edit().putBoolean(key, migrated).apply()
            }
            SecurePrefStrategy.Source.DEFAULT -> defValue
        }

    override fun contains(key: String): Boolean =
        secure.contains(key) || legacy.contains(key)

    /** 写路径唯一目的地 = 新加密库（旧库只读）。 */
    override fun edit(): SharedPreferences.Editor = secure.edit()

    override fun getAll(): MutableMap<String, *> =
        LinkedHashMap<String, Any?>(legacy.all).apply { putAll(secure.all) }

    // 写只进新库，监听委托新库即可
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = secure.registerOnSharedPreferenceChangeListener(l)

    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = secure.unregisterOnSharedPreferenceChangeListener(l)
}

/**
 * 迁移库工厂。安全垫：真机 AndroidKeyStore 坏态（部分板子/ROM 实证存在）会让 ESP 创建
 * 抛异常——此时整体退回旧明文库直读，行为与历史版本完全一致，绝不因加密库崩 App。
 * 同文件实例进程级缓存（对齐 getSharedPreferences 单例语义，防双 ESP 实例互盲）。
 */
@Suppress("DEPRECATION") // Google 已整体弃用 security-crypto（1.1.0 为终版稳定）；引入经附录 B 3 批准
object SecurePrefStore {

    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    fun migrating(context: Context, secureFile: String, legacyFile: String): SharedPreferences =
        cache.computeIfAbsent(secureFile) { open(context.applicationContext, secureFile, legacyFile) }

    private fun open(context: Context, secureFile: String, legacyFile: String): SharedPreferences {
        val legacy = context.getSharedPreferences(legacyFile, Context.MODE_PRIVATE)
        val secure = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                secureFile,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { e ->
            Log.w(
                "SecurePrefs",
                "encrypted prefs unavailable, fallback to legacy file=$secureFile: ${e.javaClass.simpleName}",
            )
            return legacy
        }
        return MigratingSharedPreferences(secure, legacy)
    }
}
