package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 师大邮箱（学生邮件系统，由网易企业邮箱承载）的免密入口。
 *
 * 做法与数字京师首页的「邮件」卡片完全一致：带着门户 accessToken 调卡片接口
 * `sems-tp-nup/card/email/integration/getEmailInfo`，服务端返回一条**一次性**的网易免密链接
 * （`entry.qiye.163.com/login/ssoLogin?sso_token=…`），打开即进邮箱；手机 UA 落在 `mailh.qiye.163.com/m/`。
 * 卡片每次点击都重新要一条链接，这里同样一次一取、不缓存。校外时门户经 OneVPN 代理，接口也走同一条代理路径。
 * 珠海校区的门户由 aTrust 网关保护、应用侧换不到门户 token，暂不提供。
 */
internal object MailSso {

    private const val TAG = "OneBNU/MailSSO"
    private const val PORTAL_HOME = "https://one.bnu.edu.cn/tp_nup/index.html"
    private const val MAIL_DOMAIN = "qiye.163.com"
    private val JSON = "application/json;charset=UTF-8".toMediaType()

    /** 数字京师「邮件」卡片用的接口；响应里 `data.SSO_URL`（手机端优先 `MOBILE_SSO_URL`）就是免密链接。 */
    internal const val INFO_ENDPOINT =
        "https://one.bnu.edu.cn/gateway/sems-tp-nup/card/email/integration/getEmailInfo"

    /** 免密链接拿不到时的落地页：学校域名下的学生邮件系统入口，不带任何凭据。 */
    const val FALLBACK = "https://www.mail.bnu.edu.cn/"

    /** 卡片接口给出的邮箱信息；[unread] 服务端未给出时为 null。 */
    data class Info(val ssoUrl: String, val address: String, val unread: Int?)

    /**
     * 取一条新的免密链接；没有 CAS 会话、门户 token 换不到、接口拒绝时为 null。
     * 会发网络请求，只能在 IO 线程调用；与其他 SSO 流程共用一把锁，免得互相覆盖票据。
     */
    fun fetch(http: Http, auth: SessionAuthenticator, campus: Campus): Info? =
        synchronized(SsoCoordinator.lock) { fetchLocked(http, auth, campus, retry = true) }

    private fun fetchLocked(http: Http, auth: SessionAuthenticator, campus: Campus, retry: Boolean): Info? {
        if (campus != Campus.BEIJING || !auth.hasSession()) return null
        if (PortalSso.accessToken(campus) == null && !PortalSso.establish(http, auth, campus, PORTAL_HOME)) {
            Log.w(TAG, "门户 token 换取失败，邮箱免密不可用")
            return null
        }
        val token = PortalSso.accessToken(campus) ?: return null
        val viaProxy = PortalSso.usesProxy(campus)
        val endpoint = if (viaProxy) OneVpnSso.proxyUrl(INFO_ENDPOINT) else INFO_ENDPOINT
        val referer = if (viaProxy) OneVpnSso.proxyUrl(PORTAL_HOME) else PORTAL_HOME
        val response = http.post(
            endpoint,
            "{}".toRequestBody(JSON),
            referer = referer,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val info = parse(response.body)
        // 只记结构信息：令牌、邮箱地址和链接一律不进日志
        Log.i(TAG, "getEmailInfo HTTP ${response.code}, proxy=$viaProxy, ok=${info != null}, unread=${info?.unread}")
        if (info == null && retry) {
            // 门户 token 过期时网关不再给 code=200；清掉重换一次再试，别让用户直接落到登录页
            PortalSso.clear(campus)
            return fetchLocked(http, auth, campus, retry = false)
        }
        return info
    }

    /**
     * 解析卡片接口的响应。只接受网易企业邮箱域名下的 HTTPS 免密链接：
     * 这条链接会直接在内嵌页里打开，服务端给出别的地址也不能跟着去。
     */
    internal fun parse(body: String): Info? = runCatching {
        val json = JSONObject(body)
        if (json.optInt("code") != 200) return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        val url = listOf(data.optString("MOBILE_SSO_URL"), data.optString("SSO_URL"))
            .firstOrNull { it.isNotBlank() && isMailUrl(it) }
            ?: return@runCatching null
        val unread = data.optInt("UNREAD_EMAIL_NUM", -1).takeIf { it >= 0 }
        Info(url, data.optString("FULL_EMAIL_NAME"), unread)
    }.getOrNull()

    /** 邮箱页面允许留在内嵌页里的主机：网易企业邮箱的入口与邮箱本身（`entry.` / `mailh.` / `mailhz.qiye.163.com`）。 */
    fun isMailHost(host: String): Boolean = host == MAIL_DOMAIN || host.endsWith(".$MAIL_DOMAIN")

    private fun isMailUrl(url: String): Boolean =
        url.toHttpUrlOrNull()?.let { it.scheme == "https" && isMailHost(it.host) } == true
}
