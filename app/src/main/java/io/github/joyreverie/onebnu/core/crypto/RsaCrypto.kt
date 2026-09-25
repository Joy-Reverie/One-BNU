package io.github.joyreverie.onebnu.core.crypto

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 用服务端下发的 RSA 公钥（Base64 的 X.509 SPKI）做 PKCS#1 v1.5 加密，密文 Base64 输出。
 * 珠海 CAS 的动态公钥和师大云盘登录（见 `PanSso`）都用它。
 */
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
