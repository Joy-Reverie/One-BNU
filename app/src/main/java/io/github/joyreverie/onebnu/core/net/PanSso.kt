package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.crypto.RsaCrypto
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * 师大云盘（pan.bnu.edu.cn，联想 Filez 企业网盘）的免二次登录。
 *
 * 云盘没有接统一认证（`/v2/authlogin/get?name=marne_sso_configs` 返回空表），账号密码与数字京师相同，
 * 所以照它手机版页面（`/H5`）自己的登录流程走一遍：取 RSA 公钥 → 加密密码（PKCS#1 v1.5，与页面里的
 * JSEncrypt 一致）→ `POST /v2/user/login`。会话由服务端下发成 `X-LENOVO-SESS-ID` 与 `S` 两枚 Cookie
 * （页面自己从不写这两枚），再调 `/v2/user/info/get` 取 `uid`、`account_id` —— 页面登录成功后
 * 也是把这两个写进 Cookie。
 *
 * 内嵌页打开前把这几枚 Cookie 写进 WebView。页面的路由守卫和每个接口请求都从 `document.cookie`
 * 读会话，读得到就直接进网盘，不再显示登录页。
 *
 * 内嵌页里已经有一份有效会话（上次写进去的，或用户在页面里自己登录的）就接着用，不重复登录。
 * 登录只试一次，服务端明确拒绝的那份凭据不再拿去试 —— 反复用错的密码去撞，只会招来网盘的
 * 验证码或锁定。拿不到会话就照常打开 [H5]，由网盘自己的登录页兜底。
 *
 * 北京、珠海两个校区的账号都能登录云盘，调用方传当前校区保存的那份。
 */
internal object PanSso {

    private const val TAG = "OneBNU/PanSSO"
    private const val HOST = "pan.bnu.edu.cn"
    private const val BASE = "https://$HOST"

    /** 网盘手机版页面。没有会话时它自己显示登录页，免登录不成功时也落在这里。 */
    const val H5 = "$BASE/H5"

    /** 读写 WebView Cookie 用的地址：页面在 `/H5`、接口在 `/v2/…`，会话 Cookie 都挂在根路径上。 */
    const val COOKIE_URL = "$BASE/"

    private const val LOGIN_URL = "$BASE/v2/user/login"
    private const val SESSION_COOKIE = "X-LENOVO-SESS-ID"
    private const val SIGN_COOKIE = "S"
    private const val UID_COOKIE = "uid"
    private const val ACCOUNT_COOKIE = "account_id"

    /** 页面「退出登录」时删的就是这几枚（外加自动登录用的 `AT`，应用不申请它）。 */
    private val SESSION_NAMES = listOf(SESSION_COOKIE, SIGN_COOKIE, UID_COOKIE, ACCOUNT_COOKIE)

    private val PEM_ARMOR = Regex("-----(BEGIN|END)[^-]*-----")

    /** 一份网盘会话。[uid]、[accountId] 在向 `info/get` 核实之前可能还不知道。 */
    data class Session(val token: String, val sign: String, val uid: String?, val accountId: String?)

    /** `info/get` 核实通过后拿到的身份；[accountId] 服务端没给时为 null。 */
    internal data class User(val uid: String, val accountId: String?)

    internal enum class LoginOutcome { OK, REJECTED, FAILED }

    private val lock = Any()

    /**
     * 服务端明确拒绝过的凭据（只记摘要）。同一份本进程内不再拿去试；改了密码、换了账号自然会再试。
     * 两个校区的账号分开记，来回切换校区也不会把被拒的那份再拿去撞一次。
     */
    @Volatile private var rejected: Set<String> = emptySet()

    /**
     * 准备好一份可交给内嵌页的会话，返回要写进 WebView 的 Cookie（对 [COOKIE_URL] 逐条 `setCookie`）；
     * 拿不到会话时为 null，调用方照常打开 [H5]。
     *
     * [webViewCookie] 是 WebView 里这个站点现有的 Cookie 头：其中的会话仍然有效就接着用，
     * 否则用已保存的账号密码登录一次。[userAgent] 传内嵌页的 UA，让服务端看到的始终是同一个客户端。
     * 会发网络请求，只能在 IO 线程调用。
     */
    fun prepare(
        http: Http,
        username: String,
        password: String,
        webViewCookie: String?,
        userAgent: String? = null,
    ): List<String>? = synchronized(lock) {
        val headers = buildMap {
            // 与页面发 XHR 时一样要 JSON；登录失败的原因也在 JSON 里
            put("Accept", "application/json, text/plain, */*")
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        sessionFromCookies(webViewCookie)?.let { existing ->
            verify(http, existing, headers)?.let { return@synchronized cookiesFor(it) }
        }
        if (username.isBlank() || password.isBlank()) return@synchronized null
        val credential = fingerprint(username, password)
        if (credential in rejected) return@synchronized null
        val fresh = login(http, username, password, headers, onRejected = { rejected = rejected + credential })
            ?: return@synchronized null
        // 登录时服务端可能还下发了别的 Cookie（负载均衡的粘滞标记等），一并交给内嵌页
        cookiesFor(fresh) + passthrough(http.cookies.loadForRequest(COOKIE_URL.toHttpUrl()))
    }

    /** 退出登录、换账号时调用：之前被拒绝的凭据可以再试一次。 */
    fun reset() {
        rejected = emptySet()
    }

    /**
     * 换账号时让内嵌页里的云盘会话立即失效：对 [COOKIE_URL] 逐条 `setCookie`。
     * host-only 与带 Domain 的两种写法都覆盖到，别的站点在内嵌页里的登录态不受影响。
     */
    fun expiredCookies(): List<String> = SESSION_NAMES.flatMap { name ->
        listOf("$name=; Max-Age=0; Path=/", "$name=; Max-Age=0; Path=/; Domain=$HOST")
    }

    private fun login(
        http: Http,
        username: String,
        password: String,
        headers: Map<String, String>,
        onRejected: () -> Unit,
    ): Session? {
        val key = publicKey(http.get("$BASE/v2/system/get_publickey?language=zh", referer = H5, headers = headers).body)
        if (key == null) {
            Log.w(TAG, "没取到登录公钥")
            return null
        }
        // 罐里可能还留着上一份（已失效的）会话：先记下来，登录后只认这次新下发的
        val before = http.cookies.loadForRequest(LOGIN_URL.toHttpUrl())
        val response = http.postForm(
            "$LOGIN_URL?language=zh",
            // 字段与顺序照页面：没有多域登录（get_domains 为空）时 bind_type=false、timesid=0
            linkedMapOf(
                "user_slug" to username,
                "password" to RsaCrypto.encryptWithKey(password, key),
                "auto_login" to "false",
                "bind_type" to "false",
                "timesid" to "0",
            ),
            referer = H5,
            headers = headers + ("Origin" to BASE),
        )
        val outcome = loginOutcome(response.code, response.body)
        // 只记结构信息：账号、密码、会话一律不进日志；错误码是服务端的固定串
        Log.i(TAG, "user/login HTTP ${response.code}, outcome=$outcome, error=${errorCode(response.body)}")
        when (outcome) {
            LoginOutcome.REJECTED -> {
                onRejected()
                return null
            }
            LoginOutcome.FAILED -> return null
            LoginOutcome.OK -> Unit
        }
        // 按登录接口自己的地址取：服务端若没写 Path，会话 Cookie 落在 /v2/user 下，按根路径取会漏掉
        val issued = http.cookies.loadForRequest(LOGIN_URL.toHttpUrl())
        val body = runCatching { JSONObject(response.body) }.getOrNull()
        fun newlyIssued(name: String) = issued.valueOf(name)?.takeIf { it != before.valueOf(name) }
        val token = newlyIssued(SESSION_COOKIE) ?: body?.field(SESSION_COOKIE) ?: issued.valueOf(SESSION_COOKIE)
        val sign = newlyIssued(SIGN_COOKIE) ?: body?.field("S") ?: body?.field("s") ?: issued.valueOf(SIGN_COOKIE)
        if (token == null || sign == null) {
            Log.w(TAG, "登录成功但没拿到会话，token=${token != null} sign=${sign != null}")
            return null
        }
        return verify(http, Session(token, sign, uid = null, accountId = null), headers)
    }

    /** 向 `info/get` 核实会话，顺带取回 uid 与 account_id；无效时为 null。 */
    private fun verify(http: Http, session: Session, headers: Map<String, String>): Session? {
        // 会话同时放进 Cookie 罐：请求头里的 Cookie 与查询参数保持一致，
        // 不会带着罐里上一份（已失效的）会话去核实内嵌页里的这一份
        http.cookies.saveFromResponse(COOKIE_URL.toHttpUrl(), jarCookies(session))
        val response = http.get(infoUrl(session, System.currentTimeMillis()), referer = H5, headers = headers)
        val user = parseUser(response.code, response.body)
        Log.i(TAG, "user/info/get HTTP ${response.code}, ok=${user != null}")
        return user?.let { session.copy(uid = it.uid, accountId = it.accountId ?: session.accountId) }
    }

    /**
     * `info/get` 的地址。页面的请求拦截器把会话参数原样（不转义）拼在查询串里，
     * 顺序是 `X-LENOVO-SESS-ID`、`S`、`_`（时间戳）、`uid`、`account_id`，最后是 `language`；
     * Cookie 里还没有的值写成字符串 `null` —— 这里照抄。
     */
    internal fun infoUrl(session: Session, now: Long): String =
        "$BASE/v2/user/info/get".toHttpUrl().newBuilder()
            .addEncodedQueryParameter(SESSION_COOKIE, session.token)
            .addEncodedQueryParameter(SIGN_COOKIE, session.sign)
            .addEncodedQueryParameter("_", now.toString())
            .addEncodedQueryParameter(UID_COOKIE, session.uid ?: "null")
            .addEncodedQueryParameter(ACCOUNT_COOKIE, session.accountId ?: "null")
            .addEncodedQueryParameter("language", "zh")
            .build()
            .toString()

    /** `get_publickey` 返回的 PEM（可能不带换行）去掉首尾标记，剩下的 Base64 即 SPKI 公钥。 */
    internal fun publicKey(body: String): String? = runCatching {
        val base64 = JSONObject(body).optString("public_key").replace(PEM_ARMOR, "").filterNot(Char::isWhitespace)
        base64.takeIf { it.isNotEmpty() && Base64.getDecoder().decode(it).isNotEmpty() }
    }.getOrNull()

    /**
     * 登录响应的判定。4xx 是服务端明确拒绝（密码不对、要验证码、错误次数超限、账号冻结……），
     * 这份凭据不再拿去试；5xx 与网络问题下次打开时还可以再试。HTTP 200 但 `state` 不是 200
     * 同样算拒绝 —— 页面的响应拦截器也是这么判的。200 的响应体不是 JSON 时不下结论，交给后面的会话核实。
     */
    internal fun loginOutcome(code: Int, body: String): LoginOutcome {
        if (code in 400..499) return LoginOutcome.REJECTED
        if (code !in 200..299) return LoginOutcome.FAILED
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return LoginOutcome.OK
        val state = json.optString("state")
        return if (state.isNotEmpty() && state != "200") LoginOutcome.REJECTED else LoginOutcome.OK
    }

    /** `info/get` 的响应：会话有效时带 `uid`；失效时是 4xx，或者 `state` 不为 200。 */
    internal fun parseUser(code: Int, body: String): User? = runCatching {
        if (code !in 200..299) return@runCatching null
        val json = JSONObject(body)
        val state = json.optString("state")
        if (state.isNotEmpty() && state != "200") return@runCatching null
        val uid = json.field(UID_COOKIE) ?: return@runCatching null
        User(uid, json.field(ACCOUNT_COOKIE))
    }.getOrNull()

    /** 从 WebView 的 Cookie 头里取出现有会话；同名时以最后一个为准，与页面自己的 getCookie 一致。 */
    internal fun sessionFromCookies(header: String?): Session? {
        if (header.isNullOrBlank()) return null
        val values = header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty() || value == "null") null else name to value
        }.toMap()
        val token = values[SESSION_COOKIE] ?: return null
        val sign = values[SIGN_COOKIE] ?: return null
        return Session(token, sign, values[UID_COOKIE], values[ACCOUNT_COOKIE])
    }

    /**
     * 会话写进 WebView 的样子：一律 host-only、`Path=/`，**不带 HttpOnly** —— 页面要用
     * `document.cookie` 读它们，自己「退出登录」时也按 `path=/` 删。uid、account_id 与页面自己写的一样不带 Secure。
     */
    internal fun cookiesFor(session: Session): List<String> = buildList {
        add("$SESSION_COOKIE=${session.token}; Path=/; Secure")
        add("$SIGN_COOKIE=${session.sign}; Path=/; Secure")
        session.uid?.let { add("$UID_COOKIE=$it; Path=/") }
        session.accountId?.let { add("$ACCOUNT_COOKIE=$it; Path=/") }
    }

    /**
     * 服务端下发的其余 Cookie 原样转交（保留路径、Secure、HttpOnly）。只转交云盘主机自己的：
     * 别的北师大系统下发到整个域名的 Cookie 不借这个机会塞进内嵌页。
     */
    internal fun passthrough(cookies: List<Cookie>): List<String> =
        cookies.filter { it.domain == HOST && it.name !in SESSION_NAMES }.map { c ->
            buildString {
                append("${c.name}=${c.value}; Path=${c.path}")
                if (c.secure) append("; Secure")
                if (c.httpOnly) append("; HttpOnly")
            }
        }

    private fun jarCookies(session: Session): List<Cookie> =
        listOfNotNull(
            SESSION_COOKIE to session.token,
            SIGN_COOKIE to session.sign,
            session.uid?.let { UID_COOKIE to it },
            session.accountId?.let { ACCOUNT_COOKIE to it },
        ).map { (name, value) ->
            Cookie.Builder().name(name).value(value).hostOnlyDomain(HOST).path("/").secure().build()
        }

    private fun fingerprint(username: String, password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$username\u0000$password".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun List<Cookie>.valueOf(name: String): String? =
        firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() && it != "null" }

    /** 数字与字符串一视同仁地取成字符串；缺失、空串、`null` 都当没有。 */
    private fun JSONObject.field(name: String): String? =
        opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    /** 失败响应里的错误码（`code` 字段）；只截一小段进日志，不含账号信息。 */
    private fun errorCode(body: String): String? =
        runCatching { JSONObject(body).optString("code").take(48).ifBlank { null } }.getOrNull()
}
