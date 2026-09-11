package io.github.joyreverie.onebnu.data.remote

import io.github.joyreverie.onebnu.core.crypto.RsaCrypto
import io.github.joyreverie.onebnu.core.net.AuthPending
import io.github.joyreverie.onebnu.core.net.AuthResult
import io.github.joyreverie.onebnu.core.net.Http
import io.github.joyreverie.onebnu.core.net.HttpResult
import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.net.SmsResult
import io.github.joyreverie.onebnu.core.net.encodedService
import org.json.JSONObject
import java.io.IOException

/** 珠海校区独立统一认证（cas.bnuzh.edu.cn）。 */
class ZhuhaiCasClient(private val http: Http) : SessionAuthenticator {

    companion object {
        const val CAS_BASE = "https://cas.bnuzh.edu.cn"
        const val PORTAL_BASE = "https://one.bnuzh.edu.cn"
        const val JWXT_BASE = "https://jwxt.bnuzh.edu.cn"
        private const val LOGIN = "$CAS_BASE/cas/login"
        private val LT = Regex("""id="lt"[^>]*value="([^"]+)"""")
        private val EXECUTION = Regex("""name="execution"[^>]*value="([^"]+)"""")
        private val ACTION = Regex("""<form[^>]*id="loginForm"[^>]*action="([^"]+)"""")
        private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE)
    }

    private data class Pending(
        val formUrl: String,
        val pageUrl: String,
        val lt: String,
        val execution: String,
        val rsa: String,
        val service: String,
    )

    override fun login(username: String, password: String, captcha: String): AuthResult {
        // 珠海教务要求账号从珠海统一认证进入；使用教务首页作为 CAS service，
        // 成功后 CAS 会签发一次性 ticket，教务再把它换成本地 JSESSIONID。
        val service = "$JWXT_BASE/caslogin"
        val page = http.get("$LOGIN?service=${encodedService(service)}")
        val lt = LT.find(page.body)?.groupValues?.get(1)
            ?: return AuthResult.Failed(pageProblem(page))
        val action = ACTION.find(page.body)?.groupValues?.get(1)
            ?: return AuthResult.Failed("无法解析珠海认证页面")
        val rsaResponse = http.postForm("$CAS_BASE/cas/rsa", emptyMap(), referer = page.url)
        val publicKey = runCatching { JSONObject(rsaResponse.body).optString("publicKey") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failed("珠海认证未返回加密公钥")
        val pending = Pending(
            formUrl = absolute(action, page.url),
            pageUrl = page.url,
            lt = lt,
            execution = EXECUTION.find(page.body)?.groupValues?.get(1) ?: "e1s1",
            rsa = "",
            service = service,
        )
        val response = http.postForm(
            pending.formUrl,
            mapOf(
                "rsa" to "",
                "ul" to RsaCrypto.encryptWithKey(username, publicKey),
                "pl" to RsaCrypto.encryptWithKey(password, publicKey),
                "lt" to pending.lt,
                "execution" to pending.execution,
                "choosenumber" to "",
                "_eventId" to "submit",
            ),
            referer = pending.pageUrl,
        )
        if (!http.cookies.hasCasTicket()) return AuthResult.Failed(errorText(response.body))
        // Http 已接管 CAS ticket → jwxt → jsessionid 的整条跳转链，最终 body 就是教务首页。
        return if (looksLikeJwxtHome(response.body)) AuthResult.Success
        else AuthResult.Failed("珠海教务系统未能建立会话，请重试")
    }

    override fun sendSecondAuthSms(pending: AuthPending): SmsResult =
        SmsResult.Failed("珠海认证不使用北京校区短信二次认证")

    override fun completeSecondAuth(pending: AuthPending, smsCode: String): AuthResult =
        AuthResult.Failed("珠海认证不使用北京校区短信二次认证")

    override fun captchaUrl(): String = "$CAS_BASE/cas/code?${System.currentTimeMillis()}"

    override fun hasSession(): Boolean = http.cookies.hasCasTicket()

    override fun sso(service: String): HttpResult {
        val target = if (service.contains("jwxt.bnuzh.edu.cn")) service else "$PORTAL_BASE/nup/"
        return http.get("$LOGIN?service=${encodedService(target)}")
    }

    override fun relogin(username: String, password: String): Boolean =
        login(username, password, "") is AuthResult.Success

    override fun logout() {
        runCatching { http.get("$CAS_BASE/cas/logout") }
        http.cookies.clear()
    }

    override fun ssoUrl(service: String): String =
        "$LOGIN?service=${encodedService(service)}"

    private fun pageProblem(page: HttpResult): String = when {
        page.code != 200 -> "珠海认证服务返回 HTTP ${page.code}"
        page.body.isBlank() -> "珠海认证服务返回空页面"
        else -> "无法解析珠海认证页面"
    }

    private fun errorText(body: String): String =
        TITLE.find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "珠海登录失败，请检查账号和密码"

    private fun looksLikeJwxtHome(body: String): Boolean =
        body.contains("KINGOSOFT") && body.contains("frame_current")

    private fun absolute(path: String, base: String): String =
        if (path.startsWith("http")) path else java.net.URI(base).resolve(path).toString()
}
