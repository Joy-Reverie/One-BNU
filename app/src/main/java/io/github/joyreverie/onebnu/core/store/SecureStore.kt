package io.github.joyreverie.onebnu.core.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 账号凭据存储。
 *
 * 使用 [EncryptedSharedPreferences]：键与值分别以 AES256-SIV / AES256-GCM 加密，
 * 主密钥由 Android Keystore 持有并且不可导出，应用之外（含 root 前的备份、adb backup）读不到明文。
 * 同时在 AndroidManifest 里禁用了备份，避免密文随云备份外流。
 *
 * 若设备 Keystore 异常导致无法初始化，退化为「不持久化」——宁可每次手动登录，
 * 也不把密码明文写进普通 SharedPreferences。
 */
class SecureStore private constructor(private val prefs: SharedPreferences?) {

    companion object {
        private const val FILE = "onebnu_credentials"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_REMEMBER = "remember"

        fun create(context: Context): SecureStore {
            val prefs = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull()
            return SecureStore(prefs)
        }
    }

    /** Keystore 不可用时为 false，界面上应提示「本设备无法安全保存密码」。 */
    val available: Boolean get() = prefs != null

    var username: String
        get() = prefs?.getString(KEY_USER, "").orEmpty()
        set(value) { prefs?.edit()?.putString(KEY_USER, value)?.apply() }

    var password: String
        get() = prefs?.getString(KEY_PASS, "").orEmpty()
        set(value) { prefs?.edit()?.putString(KEY_PASS, value)?.apply() }

    var remember: Boolean
        get() = prefs?.getBoolean(KEY_REMEMBER, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_REMEMBER, value)?.apply() }

    val hasCredentials: Boolean
        get() = remember && username.isNotBlank() && password.isNotBlank()

    fun save(user: String, pass: String, remember: Boolean) {
        val editor = prefs?.edit() ?: return
        editor.putBoolean(KEY_REMEMBER, remember)
        if (remember) {
            editor.putString(KEY_USER, user).putString(KEY_PASS, pass)
        } else {
            // 不记住时连用户名一起清掉，避免留下可关联的身份信息
            editor.remove(KEY_USER).remove(KEY_PASS)
        }
        editor.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
