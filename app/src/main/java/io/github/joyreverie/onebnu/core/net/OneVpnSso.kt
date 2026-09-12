package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 课程中心的 CAS 会话建立。
 *
 * 北京课程中心当前优先使用官方直连域名，通过课程中心桥接页取得它自己的 CAS service；
 * 旧版 OneVPN 代理地址仍保留严格白名单中转。已有 CAS 会话时可以把这些跳转改为**直达 CAS**
 * 的标准 SSO；密码永不进入 WebView。
 *
 * 珠海当前使用独立的 `cas.bnuzh.edu.cn`，而这个 OneVPN 入口明确指向 `cas.bnu.edu.cn`。
 * 未经学校确认的跨认证域凭据复用不安全，所以珠海不做自动跨域登录，保留官方登录页。
 */
internal object OneVpnSso {
    private const val TAG = "OneBNU/OneVPN"
    private const val ONEVPN_HOST = "onevpn.bnu.edu.cn"
    private const val HOST_CRYPT_KEY = "wrdvpnisthebest!"
    private const val COURSE_CENTER_HOST = "kczx.bnu.edu.cn"
    private const val ONEVPN_LOGIN_PATH = "/login"
    const val LOGIN_SERVICE = "https://onevpn.bnu.edu.cn/login?cas_login=true"
    const val COURSE_CENTER_BASE =
        "https://kczx.bnu.edu.cn/www/dd/vue/spa/jw-pyfa#"
    const val COURSE_CENTER = "${COURSE_CENTER_BASE}/pyfa"

    /**
     * OneVPN 的 HTTPS 代理入口。学校旧教务只监听 HTTP 80 端口，移动网络经常无法直连；
     * OneVPN 用 HTTPS 接收请求，再在校内转发到原始主机。
     *
     * Wengine 的主机段不是明文，而是「AES-CFB(host)」的十六进制结果，前面拼接同一份
     * 16 字节 key 的十六进制值。该格式来自 OneVPN 返回页面中的公开配置，和浏览器入口保持一致。
     */
    internal fun proxyBase(scheme: String, host: String): String =
        proxyUrl("$scheme://$host/").removeSuffix("/")

    internal fun hasProxySession(http: Http): Boolean =
        http.cookies.loadForRequest("https://$ONEVPN_HOST/".toHttpUrlOrNull()!!)
            .any { it.name.startsWith("wengine_vpn_ticket") }

    internal fun proxyUrl(url: String): String {
        val target = url.toHttpUrlOrNull() ?: return url
        if (target.scheme != "http" && target.scheme != "https") return url
        if (!BnuHosts.isBnu(target.host)) return url

        val path = target.encodedPath.ifBlank { "/" }
        val query = target.encodedQuery?.let { "?$it" }.orEmpty()
        return "https://$ONEVPN_HOST/${target.scheme}/${encryptedHost(target.host)}$path$query"
    }

    private fun encryptedHost(host: String): String {
        val key = HOST_CRYPT_KEY.toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(key),
        )
        val encrypted = cipher.doFinal(host.toByteArray(StandardCharsets.UTF_8))
        val hex = encrypted.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
        return HOST_CRYPT_KEY.toByteArray(StandardCharsets.UTF_8)
            .joinToString(separator = "") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) } + hex
    }

    /**
     * 若 [redirect] 是已知、受信的 OneVPN → CAS 中转，则返回 OneVPN 要求的 CAS service。
     * 返回值只能交给当前北京 CAS 的 `ssoUrl()` 使用；绝不解析或填写任何密码字段。
     */
    fun serviceForRelayRedirect(
        redirect: HttpUrl?,
        campus: Campus,
        hasCasSession: Boolean,
    ): String? {
        if (campus != Campus.BEIJING || !hasCasSession) return null
        val relay = redirect ?: return null
        if (
            relay.scheme != "https" ||
            relay.host != ONEVPN_HOST ||
            relay.port != 443 ||
            !relay.encodedPath.endsWith("/cas/login") ||
            relay.querySize != 1 ||
            relay.queryParameterName(0) != "service"
        ) {
            return null
        }

        val service = relay.queryParameter("service")?.toHttpUrlOrNull() ?: return null
        if (
            service.scheme != "https" ||
            service.host != ONEVPN_HOST ||
            service.port != 443 ||
            service.encodedPath != ONEVPN_LOGIN_PATH ||
            service.querySize != 1 ||
            service.queryParameterName(0) != "cas_login" ||
            service.queryParameter("cas_login") != "true"
        ) {
            return null
        }
        return service.toString()
    }

    /**
     * 在应用侧完成一次完整的课程中心中转，并让目标站点会话 Cookie 落地。
     */
    fun establish(http: Http, auth: SessionAuthenticator, campus: Campus, target: String): Boolean {
        return synchronized(SsoCoordinator.lock) {
            establishLocked(http, auth, campus, target)
        }
    }

    private fun establishLocked(http: Http, auth: SessionAuthenticator, campus: Campus, target: String): Boolean {
        if (campus != Campus.BEIJING || !auth.hasSession()) return false
        val targetUrl = target.toHttpUrlOrNull() ?: return false
        if (targetUrl.host == COURSE_CENTER_HOST) {
            return establishDirectCourseCenter(http, auth, targetUrl)
        }
        val initial = http.getOnce(target)
        Log.i(TAG, "课程中心 initial HTTP ${initial.code} → ${safeLocation(initial.location)}")
        if (initial.code in 200..299 && initial.location == null) return !looksLikeLogin(initial.body)

        val login = initial.location?.takeIf { it.host == ONEVPN_HOST && it.pathSegments.lastOrNull() == "login" }
            ?: return false
        val relay = http.getOnce(login.toString())
        Log.i(TAG, "课程中心 login HTTP ${relay.code} → ${safeLocation(relay.location)}")
        val relayUrl = relay.location ?: return false

        // 先让北京 CAS 直接为 OneVPN service 签发 ST；访问 OneVPN 的代理 CAS 登录页
        // 会重新显示登录表单，不能把那一页当成 CAS SSO 的结果。
        val service = serviceForRelayRedirect(relayUrl, campus, hasCasSession = true) ?: return false
        val casLogin = auth.ssoUrl(service)
        val ticketResponse = http.getOnce(casLogin, referer = login.toString())
        val ticketLogin = ticketResponse.location ?: return false
        Log.i(TAG, "课程中心 CAS HTTP ${ticketResponse.code} → ${safeLocation(ticketLogin)}")
        if (
            ticketLogin.host != ONEVPN_HOST || ticketLogin.encodedPath != ONEVPN_LOGIN_PATH ||
            ticketLogin.queryParameter("ticket").isNullOrBlank()
        ) return false

        val tokenResponse = http.getOnce(
            ticketLogin.toString(),
            referer = casLogin,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val tokenHop = tokenResponse.location ?: return false
        if (!isVpnEndpoint(tokenHop, "wengine-vpn-token-login")) return false
        Log.i(TAG, "课程中心 token hop 1 → ${safeLocation(tokenHop)}")

        val nextResponse = http.getOnce(tokenHop.toString(), referer = ticketLogin.toString())
        val tokenLogin = nextResponse.location ?: tokenHop.newBuilder()
            .encodedPath("/token-login")
            .build()
        if (!isVpnEndpoint(tokenLogin, "token-login")) return false
        Log.i(TAG, "课程中心 token hop 2 HTTP ${nextResponse.code} → ${safeLocation(tokenLogin)}")

        val completed = http.getOnce(tokenLogin.toString(), referer = tokenHop.toString())
        val root = completed.location
        Log.i(TAG, "课程中心 token login HTTP ${completed.code} → ${safeLocation(root)}")
        if (
            root == null || root.host != ONEVPN_HOST ||
            root.encodedPath != "/" && root.encodedPath != targetUrl.encodedPath
        ) return false

        // 课程中心自身还会为后端 CAS 建立一次应用会话。该 CAS 登录页仍在
        // OneVPN 的受信代理路径下，使用同一份北京 CAS 会话完成这一层 ticket 兑换。
        val firstLanding = http.getOnce(targetUrl.toString())
        Log.i(TAG, "课程中心目标 HTTP ${firstLanding.code} → ${safeLocation(firstLanding.location)}")
        if (firstLanding.code in 200..299 && firstLanding.location == null) {
            return !looksLikeLogin(firstLanding.body)
        }
        val innerCasLogin = findInnerCasLogin(http, firstLanding) ?: return false
        if (!isProxyCasLogin(innerCasLogin)) return false
        // 先请求一次代理登录页，建立 OneVPN 为内层 CAS 维护的会话上下文。
        http.getOnce(innerCasLogin.toString(), referer = targetUrl.toString())
        val innerService = innerCasLogin.queryParameter("service")?.toHttpUrlOrNull() ?: return false
        if (!BnuHosts.isBnu(innerService.host)) return false
        // 旧教务的内层 CAS service 可能仍是明文 zyfw 地址；若把它原样交给 CAS，
        // OkHttp 会再次直连移动网络不可达的 80 端口。让票据回到同一条 OneVPN 代理路径。
        // 这里不能调用 auth.sso()：它会自动跟随 CAS 返回的明文 zyfw 地址，
        // 在流量网络下再次落到不可达的 80 端口。只取 CAS 的一跳 Location，
        // 再把票据落点改写成同一条 OneVPN HTTPS 代理路径。
        val innerTicket = http.getOnce(auth.ssoUrl(innerService.toString()), referer = innerCasLogin.toString())
        val ticketDestination = innerTicket.location ?: return false
        val proxiedDestination = if (
            ticketDestination.scheme == "http" && ticketDestination.host == "zyfw.bnu.edu.cn"
        ) proxyUrl(ticketDestination.toString()) else ticketDestination.toString()
        val innerSso = http.getOnce(proxiedDestination, referer = innerCasLogin.toString())
        Log.i(TAG, "课程中心内层 CAS HTTP ${innerSso.code} → ${safeLocation(innerSso.url)}")
        if (innerSso.url.host == "cas.bnu.edu.cn" || looksLikeLogin(innerSso.body)) {
            return false
        }

        val landed = http.get(target)
        val landedUrl = landed.url.toHttpUrlOrNull()
        val ok = landedUrl?.host == ONEVPN_HOST && !looksLikeLogin(landed.body)
        Log.i(TAG, "课程中心 landed HTTP ${landed.code} → ${safeLocation(landedUrl)}, ok=$ok")
        return ok
    }

    /** 课程中心本身公开提供 HTTPS 入口，优先使用它，避免 OneVPN 浏览器脚本的 Cookie 桥接。 */
    private fun establishDirectCourseCenter(http: Http, auth: SessionAuthenticator, target: HttpUrl): Boolean {
        val initial = http.getOnce(target.toString())
        Log.i(TAG, "课程中心直连 initial HTTP ${initial.code} → ${safeLocation(initial.location)}")
        if (initial.code in 200..299 && initial.location == null) return !looksLikeLogin(initial.body)

        val first = initial.location ?: return false
        val login = if (isDirectCourseCenterCasBridge(first)) {
            http.getOnce(first.toString(), referer = target.toString()).location ?: return false
        } else {
            first
        }
        if (
            login.scheme != "https" || login.host != "cas.bnu.edu.cn" ||
            login.encodedPath != "/cas/login" || login.querySize != 1 ||
            login.queryParameterName(0) != "service"
        ) return false
        val service = login.queryParameter("service")?.toHttpUrlOrNull() ?: return false
        if (
            service.host != COURSE_CENTER_HOST ||
            service.encodedPath != "/www/public/home/cas-bnu" ||
            service.querySize != 1 || service.queryParameterName(0) != "redirectUrl"
        ) return false

        val sso = auth.sso(service.toString())
        val ssoUrl = sso.url.toHttpUrlOrNull()
        Log.i(TAG, "课程中心直连 CAS HTTP ${sso.code} → ${safeLocation(ssoUrl)}")
        if (ssoUrl?.host != COURSE_CENTER_HOST || looksLikeLogin(sso.body)) return false

        val landed = http.get(target.toString())
        val landedUrl = landed.url.toHttpUrlOrNull()
        val ok = landed.code in 200..299 && landedUrl?.host == COURSE_CENTER_HOST && !looksLikeLogin(landed.body)
        Log.i(TAG, "课程中心直连 landed HTTP ${landed.code} → ${safeLocation(landedUrl)}, ok=$ok")
        return ok
    }

    private fun isDirectCourseCenterCasBridge(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == COURSE_CENTER_HOST &&
            url.encodedPath == "/www/public/home/cas-bnu" &&
            url.querySize == 1 && url.queryParameterName(0) == "url" &&
            !url.queryParameter("url").isNullOrBlank()

    private fun safeLocation(url: HttpUrl?): String = url?.let {
        val names = it.queryParameterNames.joinToString(",")
        "${it.host}${it.encodedPath.substringBefore(';')}${if (names.isBlank()) "" else "?$names"}"
    } ?: "none"

    private fun looksLikeLogin(body: String): Boolean =
        body.contains("统一身份认证") || body.contains("id=\"loginForm\"") ||
            body.contains("name=\"lt\"") && body.contains("name=\"execution\"")

    private fun isVpnEndpoint(url: HttpUrl, path: String): Boolean =
        url.scheme == "https" && url.host == ONEVPN_HOST &&
            (url.encodedPath == "/" + path || url.encodedPath.endsWith("/" + path)) &&
            url.querySize == 1 && url.queryParameterName(0) == "token"

    private fun isProxyCasLogin(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == ONEVPN_HOST &&
            (url.encodedPath.startsWith("/http/") || url.encodedPath.startsWith("/https/")) &&
            url.encodedPath.endsWith("/cas/login") &&
            url.querySize == 1 && url.queryParameterName(0) == "service" &&
            !url.queryParameter("service").isNullOrBlank()

    private fun isCourseCenterCasBridge(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == ONEVPN_HOST &&
            (url.encodedPath.startsWith("/http/") || url.encodedPath.startsWith("/https/")) &&
            url.encodedPath.endsWith("/www/public/home/cas-bnu") &&
            url.querySize == 1 && url.queryParameterName(0) == "url" &&
            !url.queryParameter("url").isNullOrBlank()

    private fun findInnerCasLogin(http: Http, first: HttpOnceResult): HttpUrl? {
        var response = first
        repeat(4) {
            val location = response.location ?: return null
            if (isProxyCasLogin(location)) return location
            if (!isCourseCenterCasBridge(location)) return null
            response = http.getOnce(location.toString(), referer = response.url.toString())
            Log.i(TAG, "课程中心目标桥接 HTTP ${response.code} → ${safeLocation(response.location)}")
        }
        return null
    }

}
