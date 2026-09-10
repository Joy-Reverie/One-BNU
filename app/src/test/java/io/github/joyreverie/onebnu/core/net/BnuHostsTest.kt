package io.github.joyreverie.onebnu.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议升级规则。
 *
 * 这是「登录走到最后一步失败 / 登进去读不到教务数据」的直接原因所在：
 * 教务的 302 会把请求降级到明文的统一认证，而明文只放行了教务与图书馆，
 * 于是被 Android 的明文策略掐断。规则一旦写错就整个应用不可用，故锁死。
 */
class BnuHostsTest {

    private fun up(url: String) = BnuHosts.upgraded(url.toHttpUrl()).toString()

    @Test
    fun `明文的统一认证升级为 HTTPS`() {
        assertEquals(
            "https://cas.bnu.edu.cn/cas/login",
            up("http://cas.bnu.edu.cn/cas/login"),
        )
    }

    @Test
    fun `升级时保留路径与查询串`() {
        assertEquals(
            "https://cas.bnu.edu.cn/cas/login?service=http%3A%2F%2Fzyfw.bnu.edu.cn%2F",
            up("http://cas.bnu.edu.cn/cas/login?service=http%3A%2F%2Fzyfw.bnu.edu.cn%2F"),
        )
    }

    @Test
    fun `只有 HTTP 的教务系统保持明文`() {
        assertEquals("http://zyfw.bnu.edu.cn/frame/homes.html", up("http://zyfw.bnu.edu.cn/frame/homes.html"))
    }

    @Test
    fun `只有 HTTP 的图书馆各入口保持明文`() {
        for (h in listOf("www.lib.bnu.edu.cn", "lib.bnu.edu.cn", "libone.bnu.edu.cn")) {
            assertEquals("http://$h/", up("http://$h/"))
        }
    }

    @Test
    fun `已经是 HTTPS 的不动`() {
        assertEquals("https://one.bnu.edu.cn/tp_nup/", up("https://one.bnu.edu.cn/tp_nup/"))
    }

    @Test
    fun `校外域名一律不动 —— 升级只针对北师大主机`() {
        assertEquals("http://example.com/", up("http://example.com/"))
        // 后缀相似但并非北师大的域名不能被误判
        assertEquals("http://notbnu.edu.cn/", up("http://notbnu.edu.cn/"))
        assertEquals("http://bnu.edu.cn.evil.com/", up("http://bnu.edu.cn.evil.com/"))
    }

    @Test
    fun `域名归属判定`() {
        assertTrue(BnuHosts.isBnu("bnu.edu.cn"))
        assertTrue(BnuHosts.isBnu("cas.bnu.edu.cn"))
        assertFalse(BnuHosts.isBnu("notbnu.edu.cn"))
        assertFalse(BnuHosts.isBnu("bnu.edu.cn.evil.com"))
    }

    @Test
    fun `仅明文主机名单与放行名单一致`() {
        assertTrue(BnuHosts.isHttpOnly("zyfw.bnu.edu.cn"))
        assertFalse(BnuHosts.isHttpOnly("cas.bnu.edu.cn"))
        assertFalse(BnuHosts.isHttpOnly("one.bnu.edu.cn"))
    }
}
