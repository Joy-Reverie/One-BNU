package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneVpnSsoTest {

    private val expectedService = "https://onevpn.bnu.edu.cn/login?cas_login=true"
    private val trustedRelay = (
        "https://onevpn.bnu.edu.cn/https/77726476706e69737468656265737421f3f652d2253e7d1e7b0c9ce29b5b/" +
            "cas/login?service=https%3A%2F%2Fonevpn.bnu.edu.cn%2Flogin%3Fcas_login%3Dtrue"
        ).toHttpUrl()

    @Test
    fun `北京已有 CAS 会话时只接受预期的 OneVPN 中转`() {
        assertEquals(
            expectedService,
            OneVpnSso.serviceForRelayRedirect(trustedRelay, Campus.BEIJING, hasCasSession = true),
        )
    }

    @Test
    fun `不跨校区 认证状态或服务域转交票据`() {
        assertNull(OneVpnSso.serviceForRelayRedirect(trustedRelay, Campus.ZHUHAI, hasCasSession = true))
        assertNull(OneVpnSso.serviceForRelayRedirect(trustedRelay, Campus.BEIJING, hasCasSession = false))

        val externalService = (
            "https://onevpn.bnu.edu.cn/https/relay/cas/login?" +
                "service=https%3A%2F%2Fexample.com%2Flogin%3Fcas_login%3Dtrue"
            ).toHttpUrl()
        assertNull(OneVpnSso.serviceForRelayRedirect(externalService, Campus.BEIJING, hasCasSession = true))

        val serviceWithExtraParameter = (
            "https://onevpn.bnu.edu.cn/https/relay/cas/login?" +
                "service=https%3A%2F%2Fonevpn.bnu.edu.cn%2Flogin%3Fcas_login%3Dtrue%26next%3Dhttps%253A%252F%252Fexample.com"
            ).toHttpUrl()
        assertNull(OneVpnSso.serviceForRelayRedirect(serviceWithExtraParameter, Campus.BEIJING, hasCasSession = true))
    }
}
