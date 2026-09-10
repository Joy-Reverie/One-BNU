package io.github.joyreverie.onebnu.core.crypto

/**
 * 北师大统一身份认证（cas.bnu.edu.cn）登录所用的加密算法。
 *
 * 逐位对应 CAS 登录页 `/cas/comm/js/des.js` 中的 `strEnc(data, key1, key2, key3)`。
 * 这是一个非标准的 DES 变体：
 *  - 明文按每 4 个 UTF-16 码元切块，每块补足成 64 bit（每字符 16 bit，高位在前）
 *  - 密钥同样按 4 字符切块，每块做一轮 DES；三个密钥依次串联
 *  - 每块输出 16 个大写十六进制字符
 *
 * 登录时的调用为 `strEnc(用户名 + 密码 + lt, "1", "2", "3")`。
 * 该算法由服务端指定，不能替换成标准 DES —— 二者结果并不相同。
 */
object KingoDes {

    private val LOOP = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val S_BOXES = arrayOf(
        // S1
        arrayOf(
            intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
            intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
            intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
            intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        ),
        // S2
        arrayOf(
            intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
            intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
            intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
            intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        ),
        // S3
        arrayOf(
            intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
            intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
            intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
            intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        ),
        // S4
        arrayOf(
            intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
            intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
            intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
            intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        ),
        // S5
        arrayOf(
            intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
            intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
            intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
            intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        ),
        // S6
        arrayOf(
            intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
            intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
            intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
            intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        ),
        // S7
        arrayOf(
            intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
            intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
            intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
            intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        ),
        // S8
        arrayOf(
            intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
            intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
            intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
            intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
        ),
    )

    private val P_BOX = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    private val FP_BOX = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    private val PC2_BOX = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /**
     * 与 des.js `strEnc` 等价。空串返回空串。
     */
    fun strEnc(data: String, firstKey: String?, secondKey: String?, thirdKey: String?): String {
        if (data.isEmpty()) return ""

        // 三个密钥依次串联；每个密钥自身按 4 字符切块，每块都是独立一轮 DES
        val keyBlocks = ArrayList<IntArray>(3)
        for (key in listOf(firstKey, secondKey, thirdKey)) {
            if (!key.isNullOrEmpty()) keyBlocks += keyBytes(key)
        }
        if (keyBlocks.isEmpty()) return ""

        // 预生成子密钥，避免每个数据块重复推导
        val subKeys = keyBlocks.map { generateKeys(it) }

        val out = StringBuilder(data.length / 4 * 16 + 16)
        var i = 0
        while (i < data.length) {
            val chunk = data.substring(i, minOf(i + 4, data.length))
            var block = strToBt(chunk)
            for (ks in subKeys) block = encBlock(block, ks)
            out.append(bt64ToHex(block))
            i += 4
        }
        return out.toString()
    }

    /**
     * 与 des.js `getKeyBytes` 等价：密钥按 4 字符切块，每块 64 bit。
     * 每一块都会独立走一轮 DES，所以长密钥会带来更多轮次。
     */
    private fun keyBytes(key: String): List<IntArray> {
        val blocks = ArrayList<IntArray>((key.length + 3) / 4)
        var i = 0
        while (i < key.length) {
            blocks += strToBt(key.substring(i, minOf(i + 4, key.length)))
            i += 4
        }
        return blocks
    }

    /**
     * 与 des.js `strToBt` 等价：最多取 4 个 UTF-16 码元，每个展开成 16 bit（高位在前），
     * 不足 4 个的位置补 0，共 64 bit。
     */
    private fun strToBt(str: String): IntArray {
        val bt = IntArray(64)
        val n = minOf(str.length, 4)
        for (i in 0 until n) {
            val k = str[i].code
            for (j in 0 until 16) {
                bt[16 * i + j] = (k ushr (15 - j)) and 1
            }
        }
        return bt
    }

    /** 与 des.js `bt64ToHex` 等价。 */
    private fun bt64ToHex(bits: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            var v = 0
            for (j in 0 until 4) v = (v shl 1) or bits[i * 4 + j]
            sb.append(HEX_DIGITS[v])
        }
        return sb.toString()
    }

    /** 与 des.js `initPermute` 等价。 */
    private fun initPermute(originalData: IntArray): IntArray {
        val ip = IntArray(64)
        var m = 1
        var n = 0
        for (i in 0 until 4) {
            var k = 0
            for (j in 7 downTo 0) {
                ip[i * 8 + k] = originalData[j * 8 + m]
                ip[i * 8 + k + 32] = originalData[j * 8 + n]
                k++
            }
            m += 2
            n += 2
        }
        return ip
    }

    /** 与 des.js `expandPermute` 等价（32 bit → 48 bit）。 */
    private fun expandPermute(right: IntArray): IntArray {
        val ep = IntArray(48)
        for (i in 0 until 8) {
            ep[i * 6 + 0] = if (i == 0) right[31] else right[i * 4 - 1]
            ep[i * 6 + 1] = right[i * 4 + 0]
            ep[i * 6 + 2] = right[i * 4 + 1]
            ep[i * 6 + 3] = right[i * 4 + 2]
            ep[i * 6 + 4] = right[i * 4 + 3]
            ep[i * 6 + 5] = if (i == 7) right[0] else right[i * 4 + 4]
        }
        return ep
    }

    /** 与 des.js `sBoxPermute` 等价（48 bit → 32 bit）。 */
    private fun sBoxPermute(expandByte: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val row = expandByte[m * 6 + 0] * 2 + expandByte[m * 6 + 5]
            val col = expandByte[m * 6 + 1] * 8 +
                expandByte[m * 6 + 2] * 4 +
                expandByte[m * 6 + 3] * 2 +
                expandByte[m * 6 + 4]
            val v = S_BOXES[m][row][col]
            out[m * 4 + 0] = (v ushr 3) and 1
            out[m * 4 + 1] = (v ushr 2) and 1
            out[m * 4 + 2] = (v ushr 1) and 1
            out[m * 4 + 3] = v and 1
        }
        return out
    }

    private fun permute(src: IntArray, table: IntArray): IntArray {
        val out = IntArray(table.size)
        for (i in table.indices) out[i] = src[table[i]]
        return out
    }

    private fun xor(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size)
        for (i in a.indices) out[i] = a[i] xor b[i]
        return out
    }

    /** 与 des.js `generateKeys` 等价：由 64 bit 密钥块生成 16 组 48 bit 子密钥。 */
    private fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            var k = 7
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * k + i]
                k--
            }
        }
        val keys = Array(16) { IntArray(48) }
        for (i in 0 until 16) {
            repeat(LOOP[i]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            for (m in 0 until 48) keys[i][m] = key[PC2_BOX[m]]
        }
        return keys
    }

    /** 与 des.js `enc` 等价（一轮完整 DES）。 */
    private fun encBlock(dataByte: IntArray, keys: Array<IntArray>): IntArray {
        val ip = initPermute(dataByte)
        var left = IntArray(32)
        var right = IntArray(32)
        for (k in 0 until 32) {
            left[k] = ip[k]
            right[k] = ip[32 + k]
        }
        for (i in 0 until 16) {
            val tempLeft = left
            left = right
            right = xor(permute(sBoxPermute(xor(expandPermute(right), keys[i])), P_BOX), tempLeft)
        }
        val finalData = IntArray(64)
        for (i in 0 until 32) {
            finalData[i] = right[i]
            finalData[32 + i] = left[i]
        }
        return permute(finalData, FP_BOX)
    }
}
