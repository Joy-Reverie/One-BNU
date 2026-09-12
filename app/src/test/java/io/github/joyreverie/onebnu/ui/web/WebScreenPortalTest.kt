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
}
