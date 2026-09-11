package io.github.joyreverie.onebnu.core.net

import java.io.IOException
import java.net.URLEncoder

/** 登录过程中的二次认证中间态。具体内容只由对应的认证实现解释。 */
class AuthPending internal constructor(internal val value: Any)

sealed interface AuthResult {
    object Success : AuthResult
    data class NeedCaptcha(val captchaUrl: String) : AuthResult
    data class NeedSecondAuth(val maskedPhone: String, val pending: AuthPending) : AuthResult
    data class Failed(val message: String) : AuthResult
}

sealed interface SmsResult {
    object Sent : SmsResult
    data class Failed(val message: String) : SmsResult
}

/** 教务仓库只依赖这些会话能力，因此北京 CAS 与珠海 CAS 可以完全分开实现。 */
interface SessionAuthenticator {
    @Throws(IOException::class)
    fun login(username: String, password: String, captcha: String): AuthResult

    @Throws(IOException::class)
    fun sendSecondAuthSms(pending: AuthPending): SmsResult

    @Throws(IOException::class)
    fun completeSecondAuth(pending: AuthPending, smsCode: String): AuthResult

    fun captchaUrl(): String

    fun hasSession(): Boolean

    @Throws(IOException::class)
    fun sso(service: String): HttpResult

    /** 会话过期时静默重登；需要额外交互的认证返回 false。 */
    fun relogin(username: String, password: String): Boolean

    fun logout()

    /** 北京认证使用设备标记；珠海认证没有这一步。 */
    fun resetDeviceIdentity() = Unit

    fun ssoUrl(service: String): String
}

internal fun encodedService(service: String): String = URLEncoder.encode(service, "UTF-8")
