package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.crypto.RsaCrypto
import okhttp3.Cookie
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/** 锁定师大云盘免登录的解析与会话交接规则；样本里的会话、uid 都是编的，不含真实账号。 */
class PanSsoTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()
    private val spki = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun pem(base64: String, lineBreaks: Boolean): String {
        val sep = if (lineBreaks) "\n" else ""
        val body = if (lineBreaks) base64.chunked(64).joinToString("\n") else base64
        return "-----BEGIN PUBLIC KEY-----$sep$body$sep-----END PUBLIC KEY-----"
    }

    private fun publicKeyBody(key: String) = JSONObject().put("public_key", key).toString()

    private fun cookie(name: String, value: String, domain: String, hostOnly: Boolean = true, path: String = "/") =
        Cookie.Builder().name(name).value(value).path(path).secure().httpOnly().run {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        }.build()

    @Test
    fun `公钥：PEM 带不带换行都能取出 SPKI，加密结果用私钥解得开`() {
        for (lineBreaks in listOf(true, false)) {
            val key = PanSso.publicKey(publicKeyBody(pem(spki, lineBreaks)))
            assertEquals(spki, key)
            val encrypted = RsaCrypto.encryptWithKey("示例密码-123", key!!)
            val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.DECRYPT_MODE, keyPair.private)
                String(doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
            }
            assertEquals("示例密码-123", plain)
        }
    }

    @Test
    fun `公钥：缺失、为空、不是 Base64、不是 JSON 都取不到`() {
        assertNull(PanSso.publicKey("{}"))
        assertNull(PanSso.publicKey(publicKeyBody("")))
        assertNull(PanSso.publicKey(publicKeyBody("-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----")))
        assertNull(PanSso.publicKey(publicKeyBody("-----BEGIN PUBLIC KEY-----!!!-----END PUBLIC KEY-----")))
        assertNull(PanSso.publicKey("<html>维护中</html>"))
    }

    @Test
    fun `登录判定：4xx 与 state 非 200 算拒绝，其余非 2xx 算失败`() {
        assertEquals(PanSso.LoginOutcome.OK, PanSso.loginOutcome(200, """{"uid":10001}"""))
        assertEquals(PanSso.LoginOutcome.OK, PanSso.loginOutcome(200, """{"state":"200"}"""))
        assertEquals(PanSso.LoginOutcome.OK, PanSso.loginOutcome(200, """{"state":200}"""))
        // 200 却不是 JSON：不下结论，交给后面的会话核实
        assertEquals(PanSso.LoginOutcome.OK, PanSso.loginOutcome(200, "<html></html>"))
        assertEquals(PanSso.LoginOutcome.REJECTED, PanSso.loginOutcome(200, """{"state":"401","code":"SampleError"}"""))
        assertEquals(PanSso.LoginOutcome.REJECTED, PanSso.loginOutcome(400, """{"code":"SampleError"}"""))
        assertEquals(PanSso.LoginOutcome.REJECTED, PanSso.loginOutcome(401, ""))
        assertEquals(PanSso.LoginOutcome.REJECTED, PanSso.loginOutcome(403, "<html></html>"))
        assertEquals(PanSso.LoginOutcome.FAILED, PanSso.loginOutcome(500, """{"code":"SampleError"}"""))
        assertEquals(PanSso.LoginOutcome.FAILED, PanSso.loginOutcome(502, "<html>Bad Gateway</html>"))
        assertEquals(PanSso.LoginOutcome.FAILED, PanSso.loginOutcome(302, ""))
    }

    @Test
    fun `会话核实：带 uid 才算有效，account_id 可缺`() {
        assertEquals(
            PanSso.User("10001", "2"),
            PanSso.parseUser(200, """{"uid":10001,"account_id":2,"user_name":"示例用户"}"""),
        )
        assertEquals(PanSso.User("10001", null), PanSso.parseUser(200, """{"uid":"10001"}"""))
        assertEquals(PanSso.User("10001", null), PanSso.parseUser(200, """{"uid":10001,"account_id":null}"""))
        assertNull(PanSso.parseUser(401, """{"uid":10001}"""))
        assertNull(PanSso.parseUser(200, """{"state":"401","uid":10001}"""))
        assertNull(PanSso.parseUser(200, """{"uid":null}"""))
        assertNull(PanSso.parseUser(200, """{"uid":""}"""))
        assertNull(PanSso.parseUser(200, """{"user_name":"示例用户"}"""))
        assertNull(PanSso.parseUser(200, "not json"))
    }

    @Test
    fun `从 WebView 的 Cookie 头取现有会话`() {
        assertEquals(
            PanSso.Session("tok0001", "sig0001", "10001", "2"),
            PanSso.sessionFromCookies("X-LENOVO-SESS-ID=tok0001; S=sig0001; uid=10001; account_id=2; lang=zh"),
        )
        // 值里本身带等号的原样保留
        assertEquals("c2lnMDAx==", PanSso.sessionFromCookies("X-LENOVO-SESS-ID=tok0001; S=c2lnMDAx==")?.sign)
        // uid 写成字符串 null（页面在还不知道时就这么写）当没有，会话本身照样可用
        assertEquals(
            PanSso.Session("tok0001", "sig0001", null, null),
            PanSso.sessionFromCookies("X-LENOVO-SESS-ID=tok0001; S=sig0001; uid=null; account_id=null"),
        )
    }

    @Test
    fun `会话不全、值为空或为 null 字样都当没有`() {
        assertNull(PanSso.sessionFromCookies(null))
        assertNull(PanSso.sessionFromCookies(""))
        assertNull(PanSso.sessionFromCookies("X-LENOVO-SESS-ID=tok0001"))
        assertNull(PanSso.sessionFromCookies("S=sig0001; uid=10001"))
        assertNull(PanSso.sessionFromCookies("X-LENOVO-SESS-ID=null; S=sig0001"))
        assertNull(PanSso.sessionFromCookies("X-LENOVO-SESS-ID=; S=sig0001"))
    }

    @Test
    fun `同名 Cookie 以最后一个为准，与页面自己的 getCookie 一致`() {
        val session = PanSso.sessionFromCookies("X-LENOVO-SESS-ID=old; S=sig-old; X-LENOVO-SESS-ID=new; S=sig-new")
        assertEquals("new", session?.token)
        assertEquals("sig-new", session?.sign)
    }

    @Test
    fun `写进 WebView 的会话：host-only、根路径、页面脚本读得到`() {
        val all = PanSso.cookiesFor(PanSso.Session("tok0001", "sig0001", "10001", "2"))
        assertEquals(
            listOf(
                "X-LENOVO-SESS-ID=tok0001; Path=/; Secure",
                "S=sig0001; Path=/; Secure",
                "uid=10001; Path=/",
                "account_id=2; Path=/",
            ),
            all,
        )
        // 页面用 document.cookie 读会话，带 HttpOnly 它就读不到；带 Domain 会把会话放给整个子域
        assertTrue(all.none { "HttpOnly" in it || "Domain" in it })
        assertEquals(
            listOf("X-LENOVO-SESS-ID=tok0001; Path=/; Secure", "S=sig0001; Path=/; Secure"),
            PanSso.cookiesFor(PanSso.Session("tok0001", "sig0001", null, null)),
        )
    }

    @Test
    fun `转交的其余 Cookie 只认云盘主机自己的，会话几枚不重复转交`() {
        val forwarded = PanSso.passthrough(
            listOf(
                cookie("SERVERID", "node-a", "pan.bnu.edu.cn"),
                cookie("route", "r1", "pan.bnu.edu.cn", path = "/v2"),
                cookie("X-LENOVO-SESS-ID", "tok0001", "pan.bnu.edu.cn"),
                cookie("S", "sig0001", "pan.bnu.edu.cn"),
                // 别的北师大系统下发到整个域名的
                cookie("CASTGC", "TGT-sample", "bnu.edu.cn", hostOnly = false),
                cookie("other", "x", "one.bnu.edu.cn"),
            ),
        )
        assertEquals(
            listOf("SERVERID=node-a; Path=/; Secure; HttpOnly", "route=r1; Path=/v2; Secure; HttpOnly"),
            forwarded,
        )
    }

    @Test
    fun `核实会话的地址：参数顺序照页面，还不知道的写成 null`() {
        assertEquals(
            "https://pan.bnu.edu.cn/v2/user/info/get?X-LENOVO-SESS-ID=tok0001&S=sig0001&_=1700000000000" +
                "&uid=null&account_id=null&language=zh",
            PanSso.infoUrl(PanSso.Session("tok0001", "sig0001", null, null), 1_700_000_000_000L),
        )
        assertEquals(
            "https://pan.bnu.edu.cn/v2/user/info/get?X-LENOVO-SESS-ID=tok0001&S=sig0001&_=1" +
                "&uid=10001&account_id=2&language=zh",
            PanSso.infoUrl(PanSso.Session("tok0001", "sig0001", "10001", "2"), 1L),
        )
    }

    @Test
    fun `换账号时作废的 Cookie：四枚会话都覆盖，host-only 与带 Domain 两种写法都有`() {
        val expired = PanSso.expiredCookies()
        assertEquals(8, expired.size)
        assertTrue(expired.all { "Max-Age=0" in it && "Path=/" in it })
        for (name in listOf("X-LENOVO-SESS-ID", "S", "uid", "account_id")) {
            val forName = expired.filter { it.startsWith("$name=;") }
            assertEquals(2, forName.size)
            assertEquals(1, forName.count { "Domain=pan.bnu.edu.cn" in it })
        }
        assertFalse(expired.any { "bnu.edu.cn" in it && "pan.bnu.edu.cn" !in it })
    }
}
