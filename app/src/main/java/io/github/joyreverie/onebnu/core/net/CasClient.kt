package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.crypto.KingoDes
import io.github.joyreverie.onebnu.core.store.DeviceIdentity
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 数字京师统一身份认证（cas.bnu.edu.cn）。
 *
 * 登录流程与网页端（`/cas/comm/bnu/js/login_bnu.js`）完全一致：
 *  1. GET /cas/login?service=… ，取出 lt、execution、表单 action、是否需要图形验证码
 *  2. rsa = strEnc(用户名 + 密码 + lt, "1","2","3")，ul/pl 为用户名与密码长度
 *  3. POST /cas/secondAuth (method=check) 探测是否触发二次认证
 *     - info == "noAuth" → 直接提交登录表单
 *     - 否则 info 是打码手机号，走 [sendSecondAuthSms] → [completeSecondAuth]
 *  4. POST 登录表单（网页端的 realSubmit），成功后 CASTGC 落入 CookieJar
 *
 * 二次认证是否触发取决于服务端是否认得这台设备，而它认的是 `devInfo` Cookie，
 * 见 [DeviceIdentity] 与 [BnuCookieJar]。
 */
class CasClient(
    private val http: Http,
    private val device: DeviceIdentity? = null,
) : SessionAuthenticator {

    companion object {
        const val CAS_BASE = "https://cas.bnu.edu.cn"
        private const val LOGIN = "$CAS_BASE/cas/login"

        /**
         * 登录链路埋点。刻意只记「走到哪一步、服务端怎么答」这类结构信息 ——
         * 用户名、密码、rsa、Cookie、验证码一律不进日志。
         */
        private const val TAG = "OneBNU/CAS"

        private val RE_LT = Regex("""name="lt"[^>]*value="([^"]+)"""")
        private val RE_EXECUTION = Regex("""name="execution"[^>]*value="([^"]+)"""")
        private val RE_ACTION = Regex("""<form[^>]*id="loginForm"[^>]*action="([^"]+)"""")
        private val RE_CODE_OPEN = Regex("""value="(true|false)"\s+ref="codeOpen"""")
        private val RE_TIPS = Regex("""id="tips"[^>]*>([^<]*)<""")
    }

    /**
     * 一次登录尝试的中间状态。
     *
     * 二次认证要在同一个 CAS 会话里续做：短信验证通过后，网页端调的是
     * `realSubmit()` —— 用**原来那份** lt / execution / rsa 提交登录表单。
     * 所以这些值必须留到二次认证完成，不能重新取登录页（那会换一个 lt）。
     */
    class Pending internal constructor(
        internal val formUrl: String,
        internal val pageUrl: String,
        internal val secondAuthUrl: String,
        internal val lt: String,
        internal val execution: String,
        internal val rsa: String,
        internal val ul: String,
        internal val pl: String,
    )

    /**
     * @param captcha 上一轮返回 [AuthResult.NeedCaptcha] 时用户填写的验证码
     */
    @Throws(IOException::class)
    override fun login(
        username: String,
        password: String,
        captcha: String,
    ): AuthResult = loginToService(username, password, captcha, "http://zyfw.bnu.edu.cn/")

    fun loginToService(
        username: String,
        password: String,
        captcha: String,
        // 默认落到教务系统而不是门户：应用真正依赖的是教务，
        // 而 one.bnu.edu.cn 存在分区解析（会 CNAME 到 onevpn），
        // 在部分运营商网络下行为不一致，没必要让登录依赖它。
        service: String,
    ): AuthResult {
        // 必须在取登录页之前判断：这一次 GET 本身就会让服务端补发 devInfo，
        // 取完再读就永远是 true，看不出「这台机器服务端认不认识」。
        val knownDevice = device?.serverMark != null
        val loginUrl = "$LOGIN?service=${enc(service)}"
        val page = http.get(loginUrl)
        Log.i(TAG, "登录页 HTTP ${page.code}, ${page.body.length}B, 登录前已知设备=$knownDevice")

        val lt = RE_LT.find(page.body)?.groupValues?.get(1)
            ?: return AuthResult.Failed(loginPageProblem(page).also { Log.w(TAG, "登录页解析失败: $it") })
        val execution = RE_EXECUTION.find(page.body)?.groupValues?.get(1) ?: "e1s1"
        val action = RE_ACTION.find(page.body)?.groupValues?.get(1)
            ?: return AuthResult.Failed("无法解析登录表单，认证页面结构可能已变更")
        val needCaptcha = RE_CODE_OPEN.find(page.body)?.groupValues?.get(1) == "true"

        val formUrl = absolute(action, page.url)
        val rsa = KingoDes.strEnc(username + password + lt, "1", "2", "3")
        val ul = username.length.toString()
        val pl = password.length.toString()

        if (needCaptcha && captcha.isBlank()) {
            return AuthResult.NeedCaptcha(captchaUrl())
        }

        val pending = Pending(
            formUrl = formUrl,
            pageUrl = page.url,
            secondAuthUrl = formUrl.substringBefore('?').replace(Regex("/login[^/]*$"), "/secondAuth"),
            lt = lt,
            execution = execution,
            rsa = rsa,
            ul = ul,
            pl = pl,
        )

        // --- 二次认证探测 ---
        val probe = postSecondAuth(
            pending,
            mapOf(
                "method" to "check",
                "captcha" to captcha,
                "ul" to ul,
                "pl" to pl,
                "rsa" to rsa,
            ),
        )
        val json = probe.json ?: return AuthResult.Failed(probe.problem!!)

        if (json.optString("result") != "true") {
            val err = json.optString("error").ifBlank { "用户名或密码错误" }
            Log.w(TAG, "secondAuth check 拒绝: $err")
            // failureTimes 为真表示后续需要图形验证码；服务端布尔与字符串都出现过
            if (json.optBoolean("failureTimes") || json.optString("failureTimes") == "true") {
                return AuthResult.NeedCaptcha(captchaUrl())
            }
            return AuthResult.Failed(err)
        }

        val info = json.optString("info")
        if (info.isNotEmpty() && info != "noAuth") {
            Log.i(TAG, "触发短信二次认证")
            return AuthResult.NeedSecondAuth(info, AuthPending(pending))
        }

        Log.i(TAG, "无需二次认证，直接提交表单")
        return submitLoginForm(pending)
    }

    /** 二次认证第一步：让服务端把短信验证码发到账号绑定的手机。 */
    @Throws(IOException::class)
    override fun sendSecondAuthSms(pending: AuthPending): SmsResult {
        val p = pending.value as? Pending ?: return SmsResult.Failed("二次认证状态已失效，请重新登录")
        val res = postSecondAuth(p, mapOf("method" to "send"))
        val json = res.json ?: return SmsResult.Failed(res.problem!!)
        // 网页端只在 result 明确为 "false" 时报错，其余按成功处理
        if (json.optString("result") == "false") {
            return SmsResult.Failed(json.optString("error").ifBlank { "验证码发送失败，请稍后重试" })
        }
        return SmsResult.Sent
    }

    /** 二次认证第二步：提交短信验证码，通过后立即完成登录（等价于网页端 realSubmit）。 */
    @Throws(IOException::class)
    override fun completeSecondAuth(pending: AuthPending, smsCode: String): AuthResult {
        val p = pending.value as? Pending ?: return AuthResult.Failed("二次认证状态已失效，请重新登录")
        val res = postSecondAuth(p, mapOf("method" to "login", "code" to smsCode))
        val json = res.json ?: return AuthResult.Failed(res.problem!!)
        if (json.optString("result") != "true") {
            return AuthResult.Failed(json.optString("error").ifBlank { "验证码不正确" })
        }
        return submitLoginForm(p)
    }

    /**
     * 用已有的 CASTGC 单点登录到目标子系统，返回落地页面。
     * 会话已失效时返回的是 CAS 登录页，调用方据此触发重新登录。
     */
    @Throws(IOException::class)
    override fun sso(service: String): HttpResult = synchronized(SsoCoordinator.lock) {
        http.get("$LOGIN?service=${enc(service)}")
    }

    override fun hasSession(): Boolean = http.cookies.hasCasTicket()

    override fun relogin(username: String, password: String): Boolean =
        login(username, password, "") is AuthResult.Success

    override fun logout() {
        runCatching { http.get("$CAS_BASE/cas/logout") }
        http.cookies.clear()
    }

    /** 让服务端重新把本机当作陌生设备（下次登录会重新要短信验证）。 */
    override fun resetDeviceIdentity() {
        http.cookies.clearIncludingDevice()
    }

    override fun captchaUrl(): String = "$CAS_BASE/cas/code?${System.currentTimeMillis()}"

    override fun ssoUrl(service: String): String = "$LOGIN?service=${enc(service)}"

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 网页端的 realSubmit()：提交登录表单，CASTGC 落地即成功。 */
    private fun submitLoginForm(p: Pending): AuthResult {
        val res = http.postFormOnce(
            p.formUrl,
            mapOf(
                "rsa" to p.rsa,
                "ul" to p.ul,
                "pl" to p.pl,
                "lt" to p.lt,
                "execution" to p.execution,
                "choosenumber" to "",
                "device" to (device?.id ?: ""),
                "_eventId" to "submit",
            ),
            referer = p.pageUrl,
        )

        val completed = http.finishSameHostRedirect(res, "cas.bnu.edu.cn", "/cas/login")
        val ok = http.cookies.hasCasTicket()
        Log.i(
            TAG,
            "提交表单 HTTP ${res.code} → ${safeLocation(res.url)}, " +
                "收尾=${safeLocation(completed.url)}, CASTGC=$ok",
        )
        if (ok) return AuthResult.Success

        val tip = RE_TIPS.find(completed.body)?.groupValues?.get(1)?.trim()
        if (!tip.isNullOrBlank()) return AuthResult.Failed(tip)
        return AuthResult.Failed("登录未能完成（HTTP ${completed.code}），请重试")
    }

    private fun safeLocation(url: okhttp3.HttpUrl?): String = url?.let {
        "${it.host}${it.encodedPath.substringBefore(';')}"
    } ?: "none"

    private class JsonOrProblem(val json: JSONObject?, val problem: String?)

    /**
     * POST /cas/secondAuth。
     *
     * 这个接口在出错时会返回 **HTTP 500 的 HTML 错误页**而不是 JSON。
     * 早先的实现一律吞成一句「认证服务返回异常」，什么线索都没有；
     * 这里把状态码和页面标题带出来，用户看到的至少能指向真实原因。
     */
    private fun postSecondAuth(p: Pending, form: Map<String, String>): JsonOrProblem {
        val res = http.postForm(
            p.secondAuthUrl,
            form + ("random" to Math.random().toString()),
            referer = p.pageUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val body = res.body.trim()
        runCatching { JSONObject(body) }.getOrNull()?.let { return JsonOrProblem(it, null) }

        val hint = when {
            body.contains("请求出错") || body.contains("出现了异常") ->
                "认证服务拒绝了这次请求（HTTP ${res.code}）。多为登录页已过期，请退回重试一次。"
            looksLikeHtml(body) ->
                "认证服务返回了网页而不是数据（HTTP ${res.code}），可能被网络中间设备劫持。"
            body.isEmpty() -> "认证服务返回空响应（HTTP ${res.code}）。"
            else -> "认证服务返回异常（HTTP ${res.code}）：${body.take(80)}"
        }
        return JsonOrProblem(null, hint)
    }

    /** 登录页拿不到 lt 时，区分「页面变了」和「压根没拿到登录页」。 */
    private fun loginPageProblem(page: HttpResult): String = when {
        page.code != 200 -> "认证服务返回 HTTP ${page.code}，暂时不可用"
        page.body.isBlank() -> "认证服务返回空页面，请检查网络"
        !looksLikeHtml(page.body) -> "拿到的不是认证页面，当前网络可能有登录门户拦截"
        else -> "无法解析登录页，认证服务可能已变更"
    }

    private fun looksLikeHtml(s: String): Boolean =
        s.startsWith("<") || s.contains("<html", ignoreCase = true)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun absolute(path: String, base: String): String =
        if (path.startsWith("http")) path else java.net.URI(base).resolve(path).toString()
}
