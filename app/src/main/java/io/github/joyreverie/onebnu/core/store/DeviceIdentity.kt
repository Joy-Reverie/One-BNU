package io.github.joyreverie.onebnu.core.store

import android.content.Context
import java.security.SecureRandom

/**
 * 设备标记的存取口子。
 *
 * CookieJar 只需要读写这一个值，抽成接口后其 Cookie 逻辑可以脱离 Android
 * Context 做单元测试 —— 「登录成功却显示未登录」正是出在那段逻辑上。
 */
interface DeviceMarkStore {
    var serverMark: String?
    fun reset()
}

/**
 * 本机设备标识。
 *
 * 认证页面用 FingerprintJS 算出一个 `visitorId` 随登录表单一起提交（`device` 字段），
 * 服务端据此判断「是不是没见过的设备」，没见过就触发短信二次认证。
 *
 * 应用之前这个字段一直传空，加上会话 Cookie 全部只存内存（服务端下发的 `devInfo`
 * 一退出就丢），服务端每次看到的都是全新设备 —— 于是每次登录都要短信验证，
 * 验证过也不算数。这里给出一个**安装级别**的稳定标识来消除这个循环。
 *
 * 刻意不采集任何硬件信息（IMEI、Android ID、机型等）：随机数生成一次存本机即可满足
 * 「同一台设备保持一致」，卸载重装就换新的，比真做指纹更难被用来跨应用追踪。
 * 用户想切断关联，清除应用数据即可。
 */
class DeviceIdentity(context: Context) : DeviceMarkStore {

    private val prefs = context.getSharedPreferences("onebnu_device", Context.MODE_PRIVATE)

    /** 32 位十六进制，与 FingerprintJS visitorId 形态一致。 */
    val id: String
        get() = prefs.getString(KEY_ID, null) ?: newId().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    /**
     * 服务端在登录页下发的 `devInfo` Cookie —— 它自己用来认设备的那份记号。
     * 属于设备标识而非凭据，需要跨进程留存，否则二次认证永远过不去。
     */
    override var serverMark: String?
        get() = prefs.getString(KEY_MARK, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_MARK) else putString(KEY_MARK, value)
            }.apply()
        }

    /** 让服务端重新把本机当成陌生设备。 */
    override fun reset() {
        prefs.edit().clear().apply()
    }

    private fun newId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_MARK = "server_mark"
    }
}
