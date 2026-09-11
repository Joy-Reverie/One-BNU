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
 */
class BnuCookieJar(
    private val device: DeviceMarkStore? = null,
    private val casHost: String = DEFAULT_CAS_HOST,
) : CookieJar {

    private val store = LinkedHashMap<String, MutableMap<String, Cookie>>()

    init {
        // 把上次记下的设备标记放回去，让服务端认得这台机器。
        device?.serverMark?.let { seedDeviceCookie(it) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            val bucket = store.getOrPut(cookie.domain) { mutableMapOf() }
            if (cookie.expiresAt < System.currentTimeMillis()) {
                bucket.remove(cookie.name)
            } else {
                bucket[cookie.name] = cookie
                if (cookie.name == DEVICE_COOKIE) device?.serverMark = cookie.value
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Cookie>()
        val seen = HashSet<String>()
        for ((domain, bucket) in store) {
            if (!url.host.domainMatches(domain)) continue
            val it = bucket.entries.iterator()
            while (it.hasNext()) {
                val cookie = it.next().value
                if (cookie.expiresAt < now) { it.remove(); continue }
                if (!cookie.matches(url)) continue
                if (seen.add(cookie.name)) out += cookie
            }
        }
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
                bucket[CAS_TICKET]?.let { it.expiresAt >= now && it.value.isNotBlank() } == true
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

    private fun seedDeviceCookie(value: String) {
        val cookie = Cookie.Builder()
            .name(DEVICE_COOKIE)
            .value(value)
            // 北京、珠海各有独立认证主机；设备标记不能被错误地种到另一校区。
            .hostOnlyDomain(casHost)
            .path("/")
            .expiresAt(System.currentTimeMillis() + DEVICE_COOKIE_TTL_MS)
            .build()
        store.getOrPut(cookie.domain) { mutableMapOf() }[cookie.name] = cookie
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
