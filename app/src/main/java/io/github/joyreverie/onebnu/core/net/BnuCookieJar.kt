package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.DeviceMarkStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 内存态 CookieJar。
 *
 * 会话 Cookie（CASTGC / JSESSIONID）只存在于进程生命周期内，退出应用即失效，
 * 避免长期驻留的凭据被其他应用或备份读取。
 *
 * 唯一的例外是 [DEVICE_COOKIE]：那是服务端用来认设备的记号，不是凭据。
 * 它必须跨进程留存，否则每次启动都被当成新设备、每次登录都要重新做短信二次认证。
 *
 * 一枚 Cookie 由 (名字, 域, 路径) 共同标识（RFC 6265）。以前只按 (域, 名字) 存，
 * OneVPN 代理下教务、门户与 wengine 自己落在同一主机不同路径上的同名 `JSESSIONID` 会互相覆盖。
 */
class BnuCookieJar(
    private val device: DeviceMarkStore? = null,
    private val casHost: String = DEFAULT_CAS_HOST,
) : CookieJar {

    /** 域 → (名字 + 路径 → Cookie)。 */
    private val store = LinkedHashMap<String, MutableMap<String, Cookie>>()

    init {
        // 把上次记下的设备标记放回去，让服务端认得这台机器。
        device?.serverMark?.let { seedDeviceCookie(it) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (raw in cookies) {
            val cookie = scoped(url, raw)
            val bucket = store.getOrPut(cookie.domain) { mutableMapOf() }
            val key = keyOf(cookie)
            if (cookie.expiresAt < now) {
                bucket.remove(key)
            } else {
                bucket[key] = cookie
                // 设备标记只认认证主机自己下发的；别的站点下发的同名 Cookie 不能覆盖持久化的那份。
                if (cookie.name == DEVICE_COOKIE && url.host == casHost) device?.serverMark = cookie.value
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Cookie>()
        for ((domain, bucket) in store) {
            if (!url.host.domainMatches(domain)) continue
            val it = bucket.entries.iterator()
            while (it.hasNext()) {
                val cookie = it.next().value
                if (cookie.expiresAt < now) { it.remove(); continue }
                if (cookie.matches(url)) out += cookie
            }
        }
        // 与浏览器一致：路径更具体的排在前面，服务端取第一个同名值时拿到的是最贴近这条路径的那份。
        out.sortByDescending { it.path.length }
        return out
    }

    /**
     * CAS 的票据授予 Cookie，存在即代表统一认证会话仍然有效。
     *
     * 不能只查 `store["cas.bnu.edu.cn"]` —— 服务端若给 CASTGC 带上
     * `Domain=.bnu.edu.cn`，OkHttp 存的键就是 `bnu.edu.cn`，硬查主机名会漏掉，
     * 表现为「明明登录成功却提示未登录」。这里按域匹配规则找。
     */
    @Synchronized
    fun hasCasTicket(): Boolean {
        val now = System.currentTimeMillis()
        return store.any { (domain, bucket) ->
            casHost.domainMatches(domain) &&
                bucket.values.any { it.name == CAS_TICKET && it.expiresAt >= now && it.value.isNotBlank() }
        }
    }

    /** 清会话，但保留设备标记 —— 换账号登录不该让这台机器重新变「陌生设备」。 */
    @Synchronized
    fun clear() {
        val mark = device?.serverMark
        store.clear()
        mark?.let { seedDeviceCookie(it) }
    }

    /** 连设备标记一起清掉，用于「彻底退出」。 */
    @Synchronized
    fun clearIncludingDevice() {
        store.clear()
        device?.reset()
    }

    /**
     * CASTGC 只该留在认证主机上：服务端若带了 `Domain=.bnu.edu.cn`，这里收窄成 host-only，
     * 不让全局票据随请求扩散到其他子域。
     */
    private fun scoped(url: HttpUrl, cookie: Cookie): Cookie {
        if (cookie.name != CAS_TICKET || cookie.hostOnly) return cookie
        return Cookie.Builder()
            .name(cookie.name)
            .value(cookie.value)
            .hostOnlyDomain(url.host)
            .path(cookie.path)
            .expiresAt(cookie.expiresAt)
            .apply {
                if (cookie.secure) secure()
                if (cookie.httpOnly) httpOnly()
            }
            .build()
    }

    /** 名字和路径都不可能含换行，用它分隔最稳妥。 */
    private fun keyOf(cookie: Cookie): String = cookie.name + "\n" + cookie.path

    private fun seedDeviceCookie(value: String) {
        val cookie = Cookie.Builder()
            .name(DEVICE_COOKIE)
            .value(value)
            // 北京、珠海各有独立认证主机；设备标记不能被错误地种到另一校区。
            .hostOnlyDomain(casHost)
            .path("/")
            .expiresAt(System.currentTimeMillis() + DEVICE_COOKIE_TTL_MS)
            .build()
        store.getOrPut(cookie.domain) { mutableMapOf() }[keyOf(cookie)] = cookie
    }

    private fun String.domainMatches(domain: String): Boolean =
        this == domain || (endsWith(domain) && this[length - domain.length - 1] == '.')

    companion object {
        private const val DEFAULT_CAS_HOST = "cas.bnu.edu.cn"
        /** CAS 的全局登录票据；写入 WebView 时必须只留在 CAS 主机，不能扩散给其他子域。 */
        internal const val CAS_TICKET = "CASTGC"

        /** 服务端认设备用的 Cookie 名。 */
        const val DEVICE_COOKIE = "devInfo"

        /** 服务端给的是 30 天，这里跟随。 */
        private const val DEVICE_COOKIE_TTL_MS = 30L * 24 * 3600 * 1000
    }
}
