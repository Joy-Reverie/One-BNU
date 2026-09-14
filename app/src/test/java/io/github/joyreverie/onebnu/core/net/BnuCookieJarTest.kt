package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.DeviceMarkStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 处理是登录能否「算成功」的判定依据，出错的表现是
 * 「明明登录成功却提示未登录」或者「每次登录都要短信验证」，
 * 都很难从界面上看出来，所以在这里锁死。
 */
class BnuCookieJarTest {

    private val casUrl = "https://cas.bnu.edu.cn/cas/login".toHttpUrl()
    private val zyfwUrl = "http://zyfw.bnu.edu.cn/frame/homes.html".toHttpUrl()
    private val zhCasUrl = "https://cas.bnuzh.edu.cn/cas/login".toHttpUrl()

    private class FakeMark(override var serverMark: String? = null) : DeviceMarkStore {
        var resetCount = 0
        override fun reset() {
            serverMark = null
            resetCount++
        }
    }

    private fun jar(device: DeviceMarkStore? = null) = BnuCookieJar(device)

    private fun cookie(header: String, url: okhttp3.HttpUrl = casUrl): Cookie =
        Cookie.parse(url, header)!!

    // ------------------------------------------------------------------
    // 会话判定
    // ------------------------------------------------------------------

    @Test
    fun `没有 CASTGC 时判定为未登录`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("JSESSIONID=abc; Path=/cas")))
        assertFalse(j.hasCasTicket())
    }

    @Test
    fun `主机名下的 CASTGC 判定为已登录`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("CASTGC=TGT-1-xyz; Path=/cas")))
        assertTrue(j.hasCasTicket())
    }

    /** 这条是回归测试：旧实现只查 `store["cas.bnu.edu.cn"]`，带 Domain 的会漏判。 */
    @Test
    fun `带 Domain 的 CASTGC 同样判定为已登录`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("CASTGC=TGT-1-xyz; Domain=.bnu.edu.cn; Path=/")))
        assertTrue("Domain=.bnu.edu.cn 的 CASTGC 不能漏判", j.hasCasTicket())
    }

    @Test
    fun `空值 CASTGC 不算登录`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("CASTGC=; Path=/cas")))
        assertFalse(j.hasCasTicket())
    }

    @Test
    fun `退出登录后判定为未登录`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("CASTGC=TGT-1-xyz; Path=/cas")))
        j.clear()
        assertFalse(j.hasCasTicket())
    }

    @Test
    fun `珠海 CAS 使用独立主机判定票据`() {
        val j = BnuCookieJar(casHost = "cas.bnuzh.edu.cn")
        j.saveFromResponse(zhCasUrl, listOf(Cookie.parse(zhCasUrl, "CASTGC=TGT-zh; Path=/cas")!!))
        assertTrue(j.hasCasTicket())
        assertTrue(j.loadForRequest(casUrl).isEmpty())
    }

    @Test
    fun `珠海设备标识只会种到珠海认证主机`() {
        val mark = FakeMark(serverMark = "ZH-MARK")
        val j = BnuCookieJar(mark, casHost = "cas.bnuzh.edu.cn")
        assertEquals("ZH-MARK", j.loadForRequest(zhCasUrl).single().value)
        assertTrue(j.loadForRequest(casUrl).isEmpty())
    }

    // ------------------------------------------------------------------
    // 作用域
    // ------------------------------------------------------------------

    @Test
    fun `教务的 Cookie 不会被带到认证站点`() {
        val j = jar()
        j.saveFromResponse(zyfwUrl, listOf(cookie("JSESSIONID=zyfw-session; Path=/", zyfwUrl)))
        assertTrue(j.loadForRequest(casUrl).isEmpty())
        assertEquals("zyfw-session", j.loadForRequest(zyfwUrl).single().value)
    }

    // ------------------------------------------------------------------
    // 设备标记
    // ------------------------------------------------------------------

    @Test
    fun `服务端下发的 devInfo 会被记住`() {
        val mark = FakeMark()
        val j = jar(mark)
        j.saveFromResponse(casUrl, listOf(cookie("devInfo=ABC123; Max-Age=2592000")))
        assertEquals("ABC123", mark.serverMark)
    }

    @Test
    fun `已记住的 devInfo 在新进程里会被带上`() {
        val mark = FakeMark(serverMark = "ABC123")
        val fresh = jar(mark) // 模拟应用重启后重建 CookieJar
        val sent = fresh.loadForRequest(casUrl).single()
        assertEquals("devInfo", sent.name)
        assertEquals("ABC123", sent.value)
    }

    @Test
    fun `退出登录保留设备标记，避免又被要求短信验证`() {
        val mark = FakeMark()
        val j = jar(mark)
        j.saveFromResponse(
            casUrl,
            listOf(cookie("devInfo=ABC123; Max-Age=2592000"), cookie("CASTGC=TGT-1; Path=/cas")),
        )
        j.clear()

        assertFalse(j.hasCasTicket())
        assertEquals("ABC123", mark.serverMark)
        assertEquals("ABC123", j.loadForRequest(casUrl).single().value)
    }

    @Test
    fun `重置设备标记后不再带上 devInfo`() {
        val mark = FakeMark()
        val j = jar(mark)
        j.saveFromResponse(casUrl, listOf(cookie("devInfo=ABC123; Max-Age=2592000")))
        j.clearIncludingDevice()

        assertNull(mark.serverMark)
        assertEquals(1, mark.resetCount)
        assertTrue(j.loadForRequest(casUrl).isEmpty())
    }


    // ------------------------------------------------------------------
    // 路径与主机收窄
    // ------------------------------------------------------------------

    /** OneVPN 代理下 wengine 自己与被代理的教务都在同一主机上各设一枚 JSESSIONID，按路径区分、都要发出去。 */
    @Test
    fun `同名不同路径的 Cookie 各自保留，路径更长的排在前面`() {
        val vpn = "https://onevpn.bnu.edu.cn/".toHttpUrl()
        val proxied = "https://onevpn.bnu.edu.cn/http/77726476706e69737468656265737421/frame/homes.html".toHttpUrl()
        val j = jar()
        j.saveFromResponse(vpn, listOf(cookie("JSESSIONID=vpn; Path=/", vpn)))
        j.saveFromResponse(proxied, listOf(cookie("JSESSIONID=zyfw; Path=/http/77726476706e69737468656265737421/", proxied)))

        assertEquals(listOf("zyfw", "vpn"), j.loadForRequest(proxied).map { it.value })
        assertEquals(listOf("vpn"), j.loadForRequest(vpn).map { it.value })
    }

    @Test
    fun `带 Domain 的 CASTGC 收窄到认证主机，不会带给其他子域`() {
        val j = jar()
        j.saveFromResponse(casUrl, listOf(cookie("CASTGC=TGT-1-xyz; Domain=.bnu.edu.cn; Path=/")))
        assertTrue(j.hasCasTicket())
        assertEquals("TGT-1-xyz", j.loadForRequest(casUrl).single().value)
        assertTrue(j.loadForRequest(zyfwUrl).isEmpty())
    }

    @Test
    fun `其他站点下发的 devInfo 不会覆盖认证主机的设备标记`() {
        val mark = FakeMark(serverMark = "ABC123")
        val j = jar(mark)
        j.saveFromResponse(zyfwUrl, listOf(cookie("devInfo=EVIL; Max-Age=2592000", zyfwUrl)))
        assertEquals("ABC123", mark.serverMark)
        assertEquals("ABC123", j.loadForRequest(casUrl).single().value)
    }
}
