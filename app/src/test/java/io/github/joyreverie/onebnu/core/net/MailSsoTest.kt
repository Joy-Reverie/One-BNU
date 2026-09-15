package io.github.joyreverie.onebnu.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定数字京师「邮件」卡片接口的解析与内嵌页放行的主机范围；样本里不含真实账号。 */
class MailSsoTest {

    private fun body(data: String, code: Int = 200) =
        """{"code":$code,"msg":"操作成功","data":$data,"errorData":null}"""

    private val sso = "https://entry.qiye.163.com/login/ssoLogin?sso_token=0123456789abcdef&lang=0"

    @Test
    fun `解析卡片接口：免密链接、邮箱地址与未读数`() {
        val info = MailSso.parse(
            body("""{"SSO_URL":"$sso","RETURN_CODE":0,"FULL_EMAIL_NAME":"200000000000@mail.bnu.edu.cn","UNREAD_EMAIL_NUM":3}"""),
        )!!
        assertEquals(sso, info.ssoUrl)
        assertEquals("200000000000@mail.bnu.edu.cn", info.address)
        assertEquals(3, info.unread)
    }

    @Test
    fun `手机端链接优先，未读数缺失时为 null`() {
        val mobile = "https://mailh.qiye.163.com/m/main.jsp?sso_token=abc"
        val info = MailSso.parse(body("""{"SSO_URL":"$sso","MOBILE_SSO_URL":"$mobile","FULL_EMAIL_NAME":"x@mail.bnu.edu.cn"}"""))!!
        assertEquals(mobile, info.ssoUrl)
        assertNull(info.unread)
    }

    @Test
    fun `非 200、没有 data、链接不在网易企业邮箱域名下或不是 HTTPS 都不接受`() {
        assertNull(MailSso.parse(body("""{"SSO_URL":"$sso"}""", code = 401)))
        assertNull(MailSso.parse("""{"code":200,"msg":"ok"}"""))
        assertNull(MailSso.parse(body("""{"SSO_URL":"https://evil.example.com/login?sso_token=1"}""")))
        assertNull(MailSso.parse(body("""{"SSO_URL":"https://qiye.163.com.evil.example.com/login"}""")))
        assertNull(MailSso.parse(body("""{"SSO_URL":"http://entry.qiye.163.com/login/ssoLogin?sso_token=1"}""")))
        assertNull(MailSso.parse("not json"))
    }

    @Test
    fun `只放行网易企业邮箱的主机`() {
        assertTrue(MailSso.isMailHost("entry.qiye.163.com"))
        assertTrue(MailSso.isMailHost("mailh.qiye.163.com"))
        assertTrue(MailSso.isMailHost("mailhz.qiye.163.com"))
        assertTrue(MailSso.isMailHost("qiye.163.com"))
        assertFalse(MailSso.isMailHost("163.com"))
        assertFalse(MailSso.isMailHost("mail.163.com"))
        assertFalse(MailSso.isMailHost("qiye.163.com.evil.example.com"))
        assertFalse(MailSso.isMailHost("one.bnu.edu.cn"))
    }
}
