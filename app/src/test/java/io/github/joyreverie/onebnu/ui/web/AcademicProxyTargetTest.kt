package io.github.joyreverie.onebnu.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicProxyTargetTest {

    private val target = academicProxyUrl("http://zyfw.bnu.edu.cn/")

    @Test
    fun `教务网页入口交给 OneVPN 的是代理地址`() {
        assertTrue(isAcademicService("http://zyfw.bnu.edu.cn/"))
        // 交原始地址的话 OneVpnSso 会先直连 80 端口，蜂窝下必然超时
        assertEquals(
            "https://onevpn.bnu.edu.cn/http/" +
                "77726476706e69737468656265737421eaee478b69326645300d8db9d6562d/",
            target,
        )
    }

    @Test
    fun `停在 OneVPN 自己的页面上时要走回教务代理路径`() {
        // 票据换完后 wengine 把人留在门户 / 拒绝页，原来的教务地址丢了
        assertTrue(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/", target))
        assertTrue(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/vpn/resource.html", target))
    }

    @Test
    fun `停在登录页上绝不能顶掉它`() {
        // 实测过：OneVPN 把人送到它代理出来的统一认证登录页时，如果这时跳回目标地址，
        // 会在「目标地址 → 登录页」之间来回打转，用户永远登不进去
        assertFalse(
            leftAcademicProxyPath(
                "https://onevpn.bnu.edu.cn/https/77726476706e69737468656265737421f3f652d2253e7d1e7b0c9ce29b5b/cas/login",
                target,
            ),
        )
        assertFalse(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/login", target))
        assertFalse(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/login?cas_login=true", target))
        assertFalse(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/wengine-vpn-token-login?token=x", target))
        assertFalse(leftAcademicProxyPath("https://onevpn.bnu.edu.cn/token-login?token=x", target))
    }

    @Test
    fun `教务在自己的代理前缀下跳转不算掉出去`() {
        assertFalse(leftAcademicProxyPath(target, target))
        assertFalse(
            leftAcademicProxyPath(
                "https://onevpn.bnu.edu.cn/http/" +
                    "77726476706e69737468656265737421eaee478b69326645300d8db9d6562d/frame/home.jsp?x=1",
                target,
            ),
        )
        // 站外地址与空值都不触发
        assertFalse(leftAcademicProxyPath("https://one.bnu.edu.cn/tp_nup/", target))
        assertFalse(leftAcademicProxyPath(null, target))
    }

    @Test
    fun `wengine 的 token 兑换最后一跳要自己补上`() {
        // WebView 默认带 X-Requested-With，wengine 因此回 XHR 版链路：
        // /wengine-vpn-token-login 返回 200 空 body，页面就停在空白上
        assertEquals(
            "https://onevpn.bnu.edu.cn/token-login?token=abc123",
            oneVpnTokenLoginFollowUp("https://onevpn.bnu.edu.cn/wengine-vpn-token-login?token=abc123"),
        )
        // 没有 token、别的路径、别的主机都不补
        assertNull(oneVpnTokenLoginFollowUp("https://onevpn.bnu.edu.cn/wengine-vpn-token-login"))
        assertNull(oneVpnTokenLoginFollowUp("https://onevpn.bnu.edu.cn/token-login?token=abc123"))
        assertNull(oneVpnTokenLoginFollowUp("https://example.com/wengine-vpn-token-login?token=abc123"))
        assertNull(oneVpnTokenLoginFollowUp(null))
    }
}
