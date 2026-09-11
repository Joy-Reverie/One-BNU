package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalSsoTest {

    @Test
    fun `北京门户 OAuth 使用 nup 回调和 service`() {
        val service = "https://one.bnu.edu.cn/tp_nup/"
        val authorize = PortalSso.authorizationUrl(Campus.BEIJING, service).toHttpUrl()
        val redirect = authorize.queryParameter("redirect_uri")!!.toHttpUrl()

        assertEquals("cas.bnu.edu.cn", authorize.host)
        assertEquals("nup", authorize.queryParameter("client_id"))
        assertEquals("code", authorize.queryParameter("response_type"))
        assertEquals("all", authorize.queryParameter("scope"))
        assertEquals("one.bnu.edu.cn", redirect.host)
        assertEquals("/tp_nup/cas.html", redirect.encodedPath)
        assertEquals(service, redirect.queryParameter("service"))
    }

    @Test
    fun `珠海门户 OAuth 使用独立认证域和 testnup`() {
        val service = "https://one.bnuzh.edu.cn/"
        val authorize = PortalSso.authorizationUrl(Campus.ZHUHAI, service).toHttpUrl()
        val redirect = authorize.queryParameter("redirect_uri")!!.toHttpUrl()

        assertEquals("cas.bnuzh.edu.cn", authorize.host)
        assertEquals("testnup", authorize.queryParameter("client_id"))
        assertEquals("one.bnuzh.edu.cn", redirect.host)
        assertEquals("/nup/cas.html", redirect.encodedPath)
        assertEquals(service, redirect.queryParameter("service"))
        assertTrue(PortalSso.isPortalService(Campus.ZHUHAI, service))
        assertFalse(PortalSso.isPortalService(Campus.ZHUHAI, "https://one.bnu.edu.cn/tp_nup/"))
    }
}
