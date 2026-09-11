package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** 两校区门户的 OAuth CAS 登录适配；门户不用普通 service ticket，而是消费 OAuth code。 */
internal object PortalSso {

    private const val TAG = "OneBNU/PortalSSO"
    private const val CAS_BEIJING = "cas.bnu.edu.cn"
    private const val CAS_ZHUHAI = "cas.bnuzh.edu.cn"
    private const val BEIJING_PORTAL = "one.bnu.edu.cn"
    private const val ZHUHAI_PORTAL = "one.bnuzh.edu.cn"
    private const val BEIJING_CLIENT = "nup"
    private const val ZHUHAI_CLIENT = "testnup"
    private const val TOKEN_COOKIE = "accessToken"

    private data class Config(
        val casHost: String,
        val portalHost: String,
        val portalPath: String,
        val clientId: String,
        val hasCasDelegate: Boolean,
    )

    private val configs = mapOf(
        Campus.BEIJING to Config(
            casHost = CAS_BEIJING,
            portalHost = BEIJING_PORTAL,
            portalPath = "/tp_nup",
            clientId = BEIJING_CLIENT,
            hasCasDelegate = true,
        ),
        Campus.ZHUHAI to Config(
            casHost = CAS_ZHUHAI,
            portalHost = ZHUHAI_PORTAL,
            portalPath = "/nup",
            clientId = ZHUHAI_CLIENT,
            hasCasDelegate = false,
        ),
    )

    private val accessTokens = mutableMapOf<Campus, String>()

    /** 判断入口是否是当前校区的门户页面。 */
    fun isPortalService(campus: Campus, service: String): Boolean {
        val config = configs[campus] ?: return false
        val url = service.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == config.portalHost &&
            (url.encodedPath == "/" && campus == Campus.ZHUHAI ||
                url.encodedPath == config.portalPath || url.encodedPath.startsWith("${config.portalPath}/"))
    }

    /**
     * 通过现有 CAS 会话取得门户 accessToken。
     *
     * CAS authorize 的第一跳必须保留 Location 里的 code，随后由应用侧完成门户页面
     * `cas.html` 中原本由 JavaScript 执行的 code 换 token；整个过程不需要再次提交密码。
     */
    fun establish(http: Http, auth: SessionAuthenticator, campus: Campus, service: String): Boolean {
        return synchronized(SsoCoordinator.lock) {
            establishLocked(http, auth, campus, service)
        }
    }

    private fun establishLocked(
        http: Http,
        auth: SessionAuthenticator,
        campus: Campus,
        service: String,
        retryToken: Boolean = true,
    ): Boolean {
        val config = configs[campus] ?: return false
        if (!isPortalService(campus, service) || !auth.hasSession()) return false

        val authorizeUrl = authorizationUrl(campus, service)
        val authorize = authorizeUrl.toHttpUrlOrNull()
        val redirect = authorize?.queryParameter("redirect_uri")?.toHttpUrlOrNull()
        Log.i(
            TAG,
            "${config.portalHost} OAuth authorize=${safeLocation(authorize)} redirect=${safeLocation(redirect)} " +
                "service=${safeLocation(redirect?.queryParameter("service")?.toHttpUrlOrNull())}",
        )
        val callback = findCallback(http, authorizeUrl, config) ?: return false
        val code = callback.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return false
        val casDelegate = callback.queryParameter("casDelegate")

        // 浏览器会先加载 cas.html，再由页面脚本调用 casToken；这一跳可能设置门户自己的
        // JSESSIONID / redirectURL。只拿 Location 而跳过页面请求时，网关会偶发返回空响应。
        val callbackPage = http.get(callback.toString(), referer = authorizeUrl)
        if (callbackPage.code !in 200..299) return false

        // 门户 cas.html 通过同源 AJAX 换 token，网关会校验这个 Referer；CAS code 本身仍是唯一凭证。
        val tokenResponse = http.getOnce(
            tokenUrl(config, code, casDelegate),
            // 门户的 cas.html 用 jQuery 从当前回调页发同源请求；保留完整回调 URL，
            // 某些网关会据此校验本次 OAuth code 的来源。
            referer = callback.toString(),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val token = parseAccessToken(tokenResponse.body)
            ?.takeIf { tokenResponse.code in 200..299 }
        Log.i(
            TAG,
            "${config.portalHost} token HTTP ${tokenResponse.code} → ${safeLocation(tokenResponse.location)}, " +
                "token=${token != null}",
        )
        if (token == null && retryToken) {
            // code 是一次性的；接口瞬时返回非 token 响应时重新走一遍 authorize，
            // 避免 WebView 只能看到空白或登录页。
            Log.w(TAG, "${config.portalHost} token 交换未完成，重试 OAuth")
            return establishLocked(http, auth, campus, service, retryToken = false)
        }
        token ?: return false

        synchronized(accessTokens) {
            accessTokens[campus] = token
        }
        return true
    }

    /** 供 WebView 同步，token 只存在进程内；退出登录时由 [clear] 清除。 */
    fun accessToken(campus: Campus): String? = synchronized(accessTokens) { accessTokens[campus] }

    fun clear(campus: Campus) {
        synchronized(accessTokens) {
            accessTokens.remove(campus)
        }
    }

    /** 暴露纯 URL 构造供单元测试锁定门户协议，避免将凭据写进测试样本。 */
    internal fun authorizationUrl(campus: Campus, service: String): String {
        val config = configs.getValue(campus)
        val redirectUri = HttpUrl.Builder()
            .scheme("https")
            .host(config.portalHost)
            .addPathSegments("${config.portalPath.removePrefix("/")}/cas.html")
            .addQueryParameter("service", service)
            .build()
        return HttpUrl.Builder()
            .scheme("https")
            .host(config.casHost)
            .addPathSegments("cas/oauth2.0/authorize")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri.toString())
            .addQueryParameter("scope", "all")
            .build()
            .toString()
    }

    private fun tokenUrl(config: Config, code: String, casDelegate: String?): String = HttpUrl.Builder()
        .scheme("https")
        .host(config.portalHost)
        .addPathSegments("gateway/sems-authc/oauth2/casToken")
        .addPathSegment(code)
        .addPathSegment(config.clientId)
        .apply { addQueryParameter("casDelegate", casDelegate ?: if (config.hasCasDelegate) "null" else "") }
        .build()
        .toString()

    private fun isCallback(config: Config, url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == config.portalHost &&
            url.encodedPath == "${config.portalPath}/cas.html"

    /** CAS 可能先经过 callbackAuthorize；只允许这一条内部路径继续跟随，拒绝其他跳转。 */
    private fun findCallback(http: Http, authorizeUrl: String, config: Config): HttpUrl? {
        var current = authorizeUrl
        repeat(4) {
            val response = http.getOnce(current)
            val location = response.location ?: run {
                Log.i(TAG, "${config.portalHost} authorize HTTP ${response.code} → no-location")
                return null
            }
            Log.i(
                TAG,
                "${config.portalHost} authorize HTTP ${response.code} → ${safeLocation(location)}?" +
                    location.queryParameterNames.joinToString(","),
            )
            if (isCallback(config, location)) return location
            if (
                !isCallbackAuthorize(config, location) && !isCasLogin(config, location)
            ) {
                return null
            }

            if (isCasLogin(config, location)) {
                // OAuth authorize 先生成自己的 JSESSIONID，再把它交给 CAS login；
                // 用同一个 Http 客户端跟随，才能保留这次 authorize 的 session_state。
                Log.i(
                    TAG,
                    "${config.portalHost} CAS login target=" +
                        safeLocation(location.queryParameter("service")?.toHttpUrlOrNull()),
                )
                return followAuthorization(http, location, config)
            }
            current = location.toString()
        }
        return null
    }

    private fun followAuthorization(http: Http, login: HttpUrl, config: Config): HttpUrl? {
        var current = login
        repeat(8) {
            val response = http.getOnce(current.toString())
            val next = response.location ?: run {
                Log.i(TAG, "${config.portalHost} OAuth hop HTTP ${response.code} → ${safeLocation(response.url)}")
                return response.url.takeIf { isCallback(config, it) }
            }
            Log.i(TAG, "${config.portalHost} OAuth hop HTTP ${response.code} → ${safeLocation(next)}")
            if (isCallback(config, next)) return next
            if (!isCallbackAuthorize(config, next) && !isCasLogin(config, next)) return null
            current = next
        }
        return null
    }

    private fun isCallbackAuthorize(config: Config, url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == config.casHost &&
            url.encodedPath == "/cas/oauth2.0/callbackAuthorize"

    private fun isCasLogin(config: Config, url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != config.casHost || url.encodedPath != "/cas/login") return false
        val service = url.queryParameter("service")?.toHttpUrlOrNull() ?: return false
        return isCallbackAuthorize(config, service)
    }

    private fun safeLocation(url: HttpUrl?): String = url?.let {
        "${it.host}${it.encodedPath.substringBefore(';')}"
    } ?: "none"

    private fun parseAccessToken(body: String): String? = runCatching {
        val json = JSONObject(body)
        if (json.optInt("code") != 200) return@runCatching null
        json.optJSONObject("data")?.optString("accessToken")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 供 WebView 使用的 Cookie 属性：门户脚本需要读取它，因此不能加 HttpOnly。 */
    fun webViewCookie(campus: Campus): Pair<String, String>? {
        val config = configs[campus] ?: return null
        val token = accessToken(campus) ?: return null
        return "https://${config.portalHost}${config.portalPath}/" to
            "$TOKEN_COOKIE=$token; Path=${config.portalPath}; Secure"
    }

    fun webViewCookieTarget(campus: Campus): Pair<String, String>? {
        val config = configs[campus] ?: return null
        return "https://${config.portalHost}${config.portalPath}/" to
            "$TOKEN_COOKIE=; Max-Age=0; Path=${config.portalPath}; Secure"
    }
}
