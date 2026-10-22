package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.crypto.RsaCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/** 锁定北京 CAS 的 RSA 登录方案：`/cas/rsa` 应答的解析，以及与 JSEncrypt 一致的 512 位 PKCS#1 加密。 */
class CasRsaLoginTest {

    // 服务端下发的是 512 位密钥，这里用同样长度的测试密钥
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(512) }.genKeyPair()
    private val spki = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun decrypt(cipherText: String): String =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            String(doFinal(Base64.getDecoder().decode(cipherText)), Charsets.UTF_8)
        }

    @Test
    fun `公钥应答：取出 publicKey，其余情况返回 null`() {
        assertEquals(spki, CasClient.parsePublicKey("""{"publicKey":"$spki"}"""))
        assertEquals(spki, CasClient.parsePublicKey("  {\"publicKey\":\"$spki\"}\n"))
        assertNull(CasClient.parsePublicKey("""{"publicKey":""}"""))
        assertNull(CasClient.parsePublicKey("<html>404</html>"))
        assertNull(CasClient.parsePublicKey(""))
    }

    @Test
    fun `用户名和密码分别加密，私钥解得开`() {
        val username = "200000000000"
        val password = "Ex@mple+Pass/word=24chr!"
        assertEquals(username, decrypt(RsaCrypto.encryptWithKey(username, spki)))
        assertEquals(password, decrypt(RsaCrypto.encryptWithKey(password, spki)))
    }
}
