package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.Campus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsoWarmupTest {

    @Test
    fun `北京登录预热覆盖门户教务和课程中心`() {
        val services = SsoWarmup.targetsFor(Campus.BEIJING).map { it.service }

        assertEquals(
            listOf(
                "https://one.bnu.edu.cn/tp_nup/index.html",
                "http://zyfw.bnu.edu.cn/",
                OneVpnSso.COURSE_CENTER,
            ),
            services,
        )
        assertTrue(services.all { "password" !in it && "username" !in it })
    }

    @Test
    fun `珠海登录预热只使用珠海认证域`() {
        val services = SsoWarmup.targetsFor(Campus.ZHUHAI).map { it.service }

        assertEquals(
            listOf(
                "https://one.bnuzh.edu.cn/nup/",
                "https://jwxt.bnuzh.edu.cn/caslogin",
            ),
            services,
        )
        assertFalse(services.any { "bnu.edu.cn" in it && "bnuzh.edu.cn" !in it })
    }
}
