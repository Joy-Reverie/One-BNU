package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * OneVPN 的官方 CAS 跳转识别。
 *
 * OneVPN 会先用一个匿名会话记住用户原本要打开的页面，再把浏览器带到其代理的
 * `cas/login?service=https://onevpn…/login?cas_login=true`。北京校区已有 CAS 会话时，
 * 可以把这一跳改为**直达 CAS** 的标准 SSO；密码永不进入 WebView。
 *
 * 珠海当前使用独立的 `cas.bnuzh.edu.cn`，而这个 OneVPN 入口明确指向 `cas.bnu.edu.cn`。
 * 未经学校确认的跨认证域凭据复用不安全，所以珠海不做自动跨域登录，保留官方登录页。
 */
internal object OneVpnSso {
    private const val ONEVPN_HOST = "onevpn.bnu.edu.cn"
    private const val ONEVPN_LOGIN_PATH = "/login"
    const val LOGIN_SERVICE = "https://onevpn.bnu.edu.cn/login?cas_login=true"
    const val COURSE_CENTER_BASE =
        "https://onevpn.bnu.edu.cn/https/77726476706e69737468656265737421fbf45b8469326645300d8db9d6562d/www/dd/vue/spa/jw-pyfa#"
    const val COURSE_CENTER = "${COURSE_CENTER_BASE}/pyfa"

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
     * 在应用侧完成一次完整的 OneVPN 中转：先建立原页面的匿名返回状态，再通过现有 CAS
     * 会话兑换 OneVPN ticket，最后访问目标页让 OneVPN 会话 Cookie 落地。
     */
    fun establish(http: Http, auth: SessionAuthenticator, campus: Campus, target: String): Boolean {
        if (campus != Campus.BEIJING || !auth.hasSession()) return false
        val initial = http.getOnce(target)
        if (initial.code in 200..299 && initial.location == null) return true

        val login = initial.location?.takeIf { it.host == ONEVPN_HOST && it.pathSegments.lastOrNull() == "login" }
            ?: return false
        val relay = http.getOnce(login.toString())
        val service = serviceForRelayRedirect(relay.location, campus, hasCasSession = true) ?: return false
        auth.sso(service)

        val landed = http.get(target)
        return landed.code in 200..299 && landed.url.toHttpUrlOrNull()?.host == ONEVPN_HOST
    }
}
