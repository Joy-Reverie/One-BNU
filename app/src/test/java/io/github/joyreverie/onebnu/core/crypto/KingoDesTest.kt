package io.github.joyreverie.onebnu.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 期望值全部由 cas.bnu.edu.cn 线上的 `/cas/comm/js/des.js` 真实执行产生，
 * 用来锁死 Kotlin 移植与服务端算法完全一致。任何一条不通过都意味着登录会失败。
 */
class KingoDesTest {

    private fun enc(s: String) = KingoDes.strEnc(s, "1", "2", "3")

    @Test
    fun `空串返回空串`() {
        assertEquals("", enc(""))
    }

    @Test
    fun `不足一块时右侧补零`() {
        assertEquals("A62B4F77D5F8C6C7", enc("a"))
        assertEquals("CAAEB082C8E0C499", enc("ab"))
        assertEquals("39644174795FB4D0", enc("abc"))
    }

    @Test
    fun `整块与跨块`() {
        assertEquals("A9CF2704230383D1", enc("abcd"))
        assertEquals("A9CF2704230383D100DE5835FF643FD8", enc("abcde"))
        assertEquals("C1BB5938DF9F2190B89172CB54C8C33A4586D7F17173A6A2", enc("1234567890"))
    }

    @Test
    fun `非 ASCII 按 UTF-16 码元处理`() {
        assertEquals("4CF84E12E14AFE35", enc("中文测试"))
        assertEquals("859FF318DC530218F8D619E69ED926B8", enc("Ω≈ç√∫˜µ"))
    }

    @Test
    fun `符号`() {
        assertEquals(
            "4D664074FEB53189306C5E244F43DEF996F9AB4A270287FF" +
                "C63AC00C006DBAEEE5C8188C5E5F384637390B2577DA61B5" +
                "0AC677125B1BE64F0489EA0E9A28F7B1",
            enc("A1!@#\$%^&*()_+-=[]{}|;:,.<>?/"),
        )
    }

    @Test
    fun `相同块产生相同密文 —— ECB 特性，与 des_js 一致`() {
        val out = enc("x".repeat(100))
        assertEquals(400, out.length)
        assertEquals("0544DACD17157627", out.substring(0, 16))
        assertEquals(out.substring(0, 16), out.substring(16, 32))
    }

    @Test
    fun `登录载荷形态 —— 用户名加密码加 lt`() {
        // 输入为虚构账号，期望值由线上 des.js 对同一输入实跑得出，逐字符一致
        assertEquals(
            "69EBC4CACEF7C03D315CB4B8654EACF2315CB4B8654EACF2" +
                "7D6B982EC91FCCA440D6C5A9B2ABE5BE3F6F922E0CC0AE8A" +
                "F84CB4AE9243F30F9A216BCFE6054686",
            enc("200000000000" + "Passw0rd!x" + "LT-1-cas"),
        )
    }

    @Test
    fun `单密钥路径`() {
        assertEquals("4A60B51D4FD386C1", KingoDes.strEnc("abcd", "1", "", ""))
        assertEquals("4A60B51D4FD386C1", KingoDes.strEnc("abcd", "1", null, null))
    }
}
