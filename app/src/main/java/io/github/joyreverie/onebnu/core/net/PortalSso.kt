package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** 两校区门户的 OAuth CAS 登录适配；门户不用普通 service ticket，而是消费 OAuth code。 */
internal object PortalSso {

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
        val config = configs[campus] ?: return false
        if (!isPortalService(campus, service) || !auth.hasSession()) return false

        val callback = findCallback(http, authorizationUrl(campus, service), config) ?: return false
        val code = callback.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return false

        val tokenResponse = http.getOnce(tokenUrl(config, code))
        val token = parseAccessToken(tokenResponse.body)
            ?.takeIf { tokenResponse.code in 200..299 }
            ?: return false

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

    private fun tokenUrl(config: Config, code: String): String = HttpUrl.Builder()
        .scheme("https")
        .host(config.portalHost)
        .addPathSegments("gateway/sems-authc/oauth2/casToken")
        .addPathSegment(code)
        .addPathSegment(config.clientId)
        .apply {
            if (config.hasCasDelegate) addQueryParameter("casDelegate", "null")
        }
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
            val location = response.location ?: return null
            if (isCallback(config, location)) return location
            if (
                location.scheme != "https" || location.host != config.casHost ||
                location.encodedPath != "/cas/oauth2.0/callbackAuthorize"
            ) {
                return null
            }
            current = location.toString()
        }
        return null
    }

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
