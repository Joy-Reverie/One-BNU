package io.github.joyreverie.onebnu.core.net

import io.github.joyreverie.onebnu.core.store.DeviceIdentity
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** 移动端 UA —— 门户对桌面 UA 会下发另一套页面。 */
private const val UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

private val GBK: Charset = Charset.forName("GBK")

class HttpResult(
    val url: String,
    val code: Int,
    val body: String,
)

/**
 * 教务系统与统一认证共用的 HTTP 封装。
 *
 * 两个关键点：
 *
 * 1. **编码**：教务系统（KINGOSOFT）大量页面以 GBK 返回且不带 charset 头，
 *    按 UTF-8 解会得到乱码，因此显式判定。
 *
 * 2. **重定向自己接管**：见 [BnuHosts.upgraded]。教务会 302 到**明文的**统一认证，
 *    而认证站点不在明文放行名单里，OkHttp 自动跟随时会被 Android 的明文策略掐断。
 *    只有把跳转握在手里，才能在跟随之前把协议升回 HTTPS。
 */
class Http(val client: OkHttpClient, val cookies: BnuCookieJar) {

    companion object {
        /** 跳转上限，防止服务端配置异常时打转。 */
        private const val MAX_REDIRECTS = 12

        /**
         * [device] 用于把服务端认设备的 `devInfo` Cookie 跨进程留存，见 [DeviceIdentity]。
         */
        fun create(device: DeviceIdentity? = null): Http {
            val jar = BnuCookieJar(device)
            val client = OkHttpClient.Builder()
                .cookieJar(jar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                // 跳转由 execute() 自己走，中途需要把明文降级升回 HTTPS
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build()
            return Http(client, jar)
        }
    }

    @Throws(IOException::class)
    fun get(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): HttpResult =
        execute(newRequest(url, referer, headers).get().build())

    @Throws(IOException::class)
    fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return post(url, body, referer, headers)
    }

    @Throws(IOException::class)
    fun post(
        url: String,
        body: RequestBody,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult = execute(newRequest(url, referer, headers).post(body).build())

    private fun newRequest(url: String, referer: String?, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder()
            .url(BnuHosts.upgraded(url.toHttpUrl()))
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
        if (referer != null) b.header("Referer", referer)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    @Throws(IOException::class)
    private fun execute(request: Request): HttpResult {
        var current = request
        repeat(MAX_REDIRECTS + 1) {
            val res = client.newCall(current).execute()
            // followUp 只看响应头，不碰响应体；确定不再跳转时才读 body
            val next = followUp(current, res)
            if (next == null) return res.use { read(it) }
            res.close()
            current = next
        }
        throw IOException("重定向次数过多（超过 $MAX_REDIRECTS 次）")
    }

    @Throws(IOException::class)
    private fun read(res: okhttp3.Response): HttpResult {
        val bytes = res.body?.bytes() ?: ByteArray(0)
        val declared = res.header("Content-Type")
            ?.let { Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return HttpResult(res.request.url.toString(), res.code, decode(bytes, declared))
    }

    /**
     * 构造下一跳；不是重定向就返回 null。
     *
     * 方法与请求体的处理跟浏览器一致：303 一律转 GET，301/302 对 POST 也转 GET
     * （RFC 允许保留，但现实中所有浏览器都转，服务端也按这个预期写），
     * 307/308 原样保留。
     */
    private fun followUp(req: Request, res: okhttp3.Response): Request? {
        if (res.code !in 300..399) return null
        val location = res.header("Location")?.takeIf { it.isNotBlank() } ?: return null
        val target = BnuHosts.upgraded(res.request.url.resolve(location) ?: return null)

        val keepMethod = res.code == 307 || res.code == 308
        val builder = req.newBuilder().url(target)
        if (keepMethod) {
            builder.method(req.method, req.body)
        } else {
            builder.get().removeHeader("Content-Type").removeHeader("Content-Length")
        }
        // 跨主机跳转时不要把上一站的 Referer 带过去
        if (target.host != res.request.url.host) builder.removeHeader("Referer")
        return builder.build()
    }

    /**
     * 优先用响应头声明的编码；未声明时先按 UTF-8 试解，
     * 出现替换字符则判定为 GBK —— 教务系统正是这种情况。
     */
    private fun decode(bytes: ByteArray, declared: String?): String {
        if (bytes.isEmpty()) return ""
        if (declared != null) {
            runCatching { return String(bytes, Charset.forName(declared)) }
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        return runCatching { String(bytes, GBK) }.getOrDefault(utf8)
    }
}

/**
 * 北师大各主机的协议策略。
 *
 * 起因是一条很隐蔽的链路：`http://zyfw.bnu.edu.cn/` 会 302 到
 * **`http://cas.bnu.edu.cn/cas/login?...`** —— 明文的统一认证
 * （认证站点的 80 端口确实直接返回登录页，不会自己跳 HTTPS）。
 * 教务只有 HTTP，应用给它开了明文放行；认证站点没开，于是跟随这一跳时
 * 被 Android 的明文策略掐断（`CLEARTEXT ... not permitted`），
 * 表现就是登录的最后一步失败、以及每次 SSO 进教务都读不到数据。
 *
 * 不能靠「把 cas 也加进明文白名单」来解决 —— 那等于允许密码和票据走明文。
 * 这里只做**单向升级**：凡是能用 HTTPS 的北师大主机，明文一律改走 HTTPS；
 * 确认过只有 HTTP 的那几台保持原样。
 */
internal object BnuHosts {

    /** 确认过没有 HTTPS 端口的主机，与 network_security_config.xml 的放行名单保持一致。 */
    private val HTTP_ONLY = setOf(
        "zyfw.bnu.edu.cn",
        "www.lib.bnu.edu.cn",
        "lib.bnu.edu.cn",
        "libone.bnu.edu.cn",
    )

    fun isBnu(host: String): Boolean = host == "bnu.edu.cn" || host.endsWith(".bnu.edu.cn")

    fun isHttpOnly(host: String): Boolean = host in HTTP_ONLY

    /** 明文的北师大主机若支持 HTTPS，则升级；其余原样返回。 */
    fun upgraded(url: HttpUrl): HttpUrl =
        if (url.scheme == "http" && isBnu(url.host) && !isHttpOnly(url.host)) {
            url.newBuilder().scheme("https").build()
        } else {
            url
        }
}
