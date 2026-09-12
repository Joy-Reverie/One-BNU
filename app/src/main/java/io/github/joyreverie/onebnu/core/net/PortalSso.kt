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
    private const val ZHUHAI_PORTAL_PATH = "/nup"
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
            portalPath = ZHUHAI_PORTAL_PATH,
            clientId = ZHUHAI_CLIENT,
            // cas.html 直接把缺省的 JS null 拼进查询串，官方请求实际是 casDelegate=null。
            hasCasDelegate = true,
        ),
    )

    private val accessTokens = mutableMapOf<Campus, String>()
    private val proxyCampuses = mutableSetOf<Campus>()

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

        // 珠海门户入口现在由 aTrust 网关保护，入口与 cas.html 都需要执行官方
        // JavaScript challenge。应用侧 OkHttp 无法代替这个 challenge；交给 WebView
        // 走同一条官方 OAuth 回调，WebView 会复用下面同步进去的 CASTGC，不会再次填密码。
        if (campus == Campus.ZHUHAI) return false

        val authorizeUrl = authorizationUrl(campus, service)
        val authorize = authorizeUrl.toHttpUrlOrNull() ?: return false
        val redirect = authorize.queryParameter("redirect_uri")?.toHttpUrlOrNull()
        Log.i(
            TAG,
            "${config.portalHost} OAuth authorize=${safeLocation(authorize)} redirect=${safeLocation(redirect)} " +
                "service=${safeLocation(redirect?.queryParameter("service")?.toHttpUrlOrNull())}",
        )
        // 官方网页流程会先访问门户入口，再进入 CAS OAuth；这一步负责建立门户自己的
        // JSESSIONID。若直接从 CAS authorize 开始，casToken 接口会返回「会话已过期」。
        val entry = http.getOnce(service)
        Log.i(TAG, "${config.portalHost} entry HTTP ${entry.code} → ${safeLocation(entry.location)}")
        val useOneVpnProxy = entry.location?.let { location ->
            location.host == "onevpn.bnu.edu.cn" && location.encodedPath == "/login"
        } == true
        if (useOneVpnProxy && !OneVpnSso.hasProxySession(http)) {
            runCatching {
                OneVpnSso.establish(http, auth, campus, OneVpnSso.COURSE_CENTER)
            }.onSuccess { ok -> Log.i(TAG, "门户 OAuth 前 OneVPN 会话预热=$ok") }
                .onFailure { error -> Log.w(TAG, "门户 OAuth 前 OneVPN 预热失败=${error::class.java.simpleName}") }
        }
        val proxyPortal = useOneVpnProxy && OneVpnSso.hasProxySession(http)
        val callback = findCallback(http, authorizeUrl, config) ?: return false
        val code = callback.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return false
        val casDelegate = callback.queryParameter("casDelegate")

        // 浏览器会先加载 cas.html，再由页面脚本调用 casToken；这一跳可能设置门户自己的
        // JSESSIONID / redirectURL。只拿 Location 而跳过页面请求时，网关会偶发返回空响应。
        val callbackRequest = if (proxyPortal) OneVpnSso.proxyUrl(callback.toString()) else callback.toString()
        val callbackPage = http.get(callbackRequest, referer = callbackRequest)
        if (callbackPage.code !in 200..299) return false
        Log.i(
            TAG,
            "${config.portalHost} callback HTTP ${callbackPage.code}, cookies=" +
                http.cookies.loadForRequest(callbackRequest.toHttpUrlOrNull()!!)
                    .map { it.name }.distinct().joinToString(","),
        )

        // 门户 cas.html 通过同源 AJAX 换 token，网关会校验这个 Referer；CAS code 本身仍是唯一凭证。
        val tokenEndpoint = tokenUrl(config, code, casDelegate)
        val tokenResponse = http.getOnce(
            if (proxyPortal) OneVpnSso.proxyUrl(tokenEndpoint) else tokenEndpoint,
            // 门户的 cas.html 用 jQuery 从当前回调页发同源请求；保留完整回调 URL，
            // 某些网关会据此校验本次 OAuth code 的来源。
            referer = callbackRequest,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val token = parseAccessToken(tokenResponse.body)
            ?.takeIf { tokenResponse.code in 200..299 }
        val tokenBody = tokenResponse.body.trim()
        val tokenKeys = runCatching {
            JSONObject(tokenBody).keys().asSequence().joinToString(",")
        }.getOrDefault("non-json")
        val tokenMessage = runCatching { JSONObject(tokenBody).optString("message") }
            .getOrDefault("")
            .replace(Regex("\\s+"), " ")
            .take(80)
        Log.i(
            TAG,
            "${config.portalHost} token HTTP ${tokenResponse.code} → ${safeLocation(tokenResponse.location)}, " +
                "token=${token != null}, body=${tokenBody.length}B/json=${tokenBody.startsWith("{")}, " +
                "keys=$tokenKeys, message=$tokenMessage",
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
            if (proxyPortal) proxyCampuses += campus else proxyCampuses -= campus
        }
        return true
    }

    /** 供 WebView 同步，token 只存在进程内；退出登录时由 [clear] 清除。 */
    fun accessToken(campus: Campus): String? = synchronized(accessTokens) { accessTokens[campus] }

    fun clear(campus: Campus) {
        synchronized(accessTokens) {
            accessTokens.remove(campus)
            proxyCampuses.remove(campus)
        }
    }

    fun webViewUrl(campus: Campus, service: String): String = synchronized(accessTokens) {
        // OAuth authorize URL 必须直接访问当前校区 CAS；只把真正的门户页面交给
        // OneVPN 代理，否则会把 CAS 地址包装成门户代理路径，WebView 得到空页。
        if (campus in proxyCampuses && isPortalService(campus, service)) {
            OneVpnSso.proxyUrl(service)
        } else {
            service
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
            // 官方 cas.html 用默认 Path=/ 写入；保持同样范围，避免首页与回调页
            // 读取到不同的 accessToken 副本。
            "$TOKEN_COOKIE=$token; Path=/; Secure"
    }

    fun webViewCookieTarget(campus: Campus): Pair<String, String>? {
        val config = configs[campus] ?: return null
        return "https://${config.portalHost}${config.portalPath}/" to
            "$TOKEN_COOKIE=; Max-Age=0; Path=/; Secure"
    }

    /** OneVPN 页面使用自己的宿主名，Cookie 需要在代理宿主上再种一份。 */
    fun webViewProxyCookie(campus: Campus): Pair<String, String>? = synchronized(accessTokens) {
        if (campus !in proxyCampuses) return@synchronized null
        val config = configs[campus] ?: return@synchronized null
        val token = accessTokens[campus] ?: return@synchronized null
        OneVpnSso.proxyUrl("https://${config.portalHost}${config.portalPath}/") to
            "$TOKEN_COOKIE=$token; Path=/; Secure"
    }
}
