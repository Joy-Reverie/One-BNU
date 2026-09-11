package io.github.joyreverie.onebnu.core.crypto

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** 珠海 CAS 动态 RSA 公钥的 PKCS#1 加密。 */
object RsaCrypto {
    fun encryptWithKey(value: String, publicKeyBase64: String): String {
        val der = Base64.getDecoder().decode(publicKeyBase64)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            Base64.getEncoder().encodeToString(doFinal(value.toByteArray(Charsets.UTF_8)))
        }
    }
}
