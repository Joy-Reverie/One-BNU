package io.github.joyreverie.onebnu.ui.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebScreenPortalTest {

    @Test
    fun `电脑端门户首页不是登录页`() {
        assertFalse(
            isPortalLoginPage("https://one.bnu.edu.cn/tp_nup/index.html", desktopMode = true),
        )
    }

    @Test
    fun `电脑端门户引导页仍允许认证回退`() {
        assertTrue(
            isPortalLoginPage("https://one.bnu.edu.cn/tp_nup/guide.html", desktopMode = true),
        )
    }

    @Test
    fun `普通门户模式保留旧登录页判定`() {
        assertTrue(
            isPortalLoginPage("https://one.bnu.edu.cn/tp_nup/index.html", desktopMode = false),
        )
    }

    @Test
    fun `OneVPN 电脑端门户登录跳转仍可被识别`() {
        assertTrue(
            isPortalLoginPage(
                "https://onevpn.bnu.edu.cn/https/proxy/tp_nup/guide.html",
                desktopMode = true,
            ),
        )
    }

    @Test
    fun `教务网页入口在蜂窝下走 OneVPN 代理地址而不是明文直连`() {
        assertTrue(isAcademicService("http://zyfw.bnu.edu.cn/"))
        assertFalse(isAcademicService("https://one.bnu.edu.cn/tp_nup/index.html"))

        // 建会话与加载页面必须是同一条代理路径：交原始地址的话 OneVpnSso 会先直连
        // zyfw:80，蜂窝下必然超时，最后落到 CAS 登录页 → 白屏（1.9.14 起的老毛病）
        val proxied = academicProxyUrl("http://zyfw.bnu.edu.cn/")
        assertTrue("应指向 OneVPN 主机，实际是 $proxied", proxied.startsWith("https://onevpn.bnu.edu.cn/http/"))
        assertFalse(proxied.contains("zyfw.bnu.edu.cn"))
    }
}
