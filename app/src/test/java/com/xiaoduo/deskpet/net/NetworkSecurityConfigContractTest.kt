package com.xiaoduo.deskpet.net

import com.xiaoduo.deskpet.util.PrefContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约测试（S41）：network_security_config.xml 必须为密集架联动桥的默认同步地址
 * 主机（[PrefContract.DEFAULT_SHELF_BASE_URL]，回环 127.0.0.1）放行明文 HTTP。
 *
 * 背景：S41 装机首测实锤——默认配置下桥 POST 被系统策略拦死
 * （CLEARTEXT communication to 127.0.0.1 not permitted），联动全断。
 * 默认地址改了或白名单删了，本测试必须红（防两侧各自漂移再埋同款雷）。
 *
 * JVM 单测工作目录 = app 模块目录，直接读 res/xml 真实出厂文件（同 PetFrameTableContractTest 模式）。
 */
class NetworkSecurityConfigContractTest {

    private val configFile = File("src/main/res/xml/network_security_config.xml")

    private val configText: String by lazy {
        assertTrue("network_security_config.xml 不存在：$configFile", configFile.exists())
        configFile.readText()
    }

    /** 所有 cleartextTrafficPermitted="true" 的 domain-config 块内声明的域名集合 */
    private val cleartextDomains: Set<String> by lazy {
        val blockRegex =
            Regex("""<domain-config[^>]*cleartextTrafficPermitted="true"[^>]*>(.*?)</domain-config>""", RegexOption.DOT_MATCHES_ALL)
        val domainRegex = Regex("""<domain[^>]*>([^<]+)</domain>""")
        blockRegex.findAll(configText)
            .flatMap { block -> domainRegex.findAll(block.groupValues[1]) }
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    @Test
    fun `default shelf base url host is cleartext-permitted`() {
        val host = PrefContract.DEFAULT_SHELF_BASE_URL
            .removePrefix("http://").removePrefix("https://")
            .substringBefore(':').substringBefore('/')
        assertTrue(
            "默认密集架同步地址主机 $host 不在明文白名单：$cleartextDomains",
            host in cleartextDomains
        )
    }

    @Test
    fun `localhost alias is cleartext-permitted`() {
        assertTrue("localhost 不在明文白名单：$cleartextDomains", "localhost" in cleartextDomains)
    }

    @Test
    fun `no global cleartext base-config`() {
        // 踩坑 #39：勿全局放行——base-config 不得开明文
        assertFalse(
            "不允许 base-config 全局放行明文",
            configText.contains("<base-config cleartextTrafficPermitted=\"true\"")
        )
    }
}
