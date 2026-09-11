package io.github.joyreverie.onebnu.core.net

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import io.github.joyreverie.onebnu.core.store.Campus

/**
 * 网络自检。
 *
 * 登录失败时只说一句「登录失败」没法排查，尤其是「电脑上能用、手机上不行」
 * 这类环境差异。这里对每个依赖的主机分别做 DNS / 连接 / 协议三层检查，
 * 把具体失败点和原始异常呈现出来，用户可以直接把报告发出来。
 */
class NetworkDiagnostics(
    private val http: Http,
    private val campus: Campus = Campus.BEIJING,
) {

    data class Check(
        val name: String,
        val target: String,
        val ok: Boolean,
        val detail: String,
        val millis: Long,
        /** 该项失败是否会导致完全无法使用。 */
        val critical: Boolean,
    )

    data class Report(val checks: List<Check>) {
        val allCriticalPassed: Boolean get() = checks.none { it.critical && !it.ok }

        /** 一句话结论，直接给用户看。 */
        val summary: String
            get() {
                val failed = checks.filter { !it.ok }
                return when {
                    failed.isEmpty() -> "全部检查通过，网络环境正常"
                    failed.any { it.critical } ->
                        "无法访问：" + failed.filter { it.critical }.joinToString("、") { it.name }
                    else -> "核心功能可用，但以下项不可达：" + failed.joinToString("、") { it.name }
                }
            }

        fun asText(): String = buildString {
            appendLine("One BNU 网络诊断")
            appendLine(summary)
            appendLine()
            checks.forEach {
                appendLine("${if (it.ok) "[通过]" else "[失败]"} ${it.name}")
                appendLine("  目标：${it.target}")
                appendLine("  结果：${it.detail}（${it.millis}ms）")
            }
        }
    }

    fun run(): Report = (if (campus == Campus.BEIJING) listOf(
        dns("域名解析 · 统一认证", "cas.bnu.edu.cn", critical = true),
        dns("域名解析 · 教务系统", "zyfw.bnu.edu.cn", critical = true),
        httpCheck(
            "统一认证 (HTTPS)", "https://cas.bnu.edu.cn/cas/login", critical = true,
        ) { body -> if (body.contains("name=\"lt\"")) null else "页面结构异常，可能被网络中间设备改写" },
        httpCheck(
            "教务系统 (HTTP)", "${ZyfwBase}/", critical = true,
        ) { null },
        httpCheck(
            "图书馆 (HTTP)", "http://www.lib.bnu.edu.cn/", critical = false,
        ) { null },
        httpCheck(
            "数字京师门户 (HTTPS)", "https://one.bnu.edu.cn/tp_nup/", critical = false,
        ) { null },
    ) else listOf(
        dns("域名解析 · 珠海统一认证", "cas.bnuzh.edu.cn", critical = true),
        dns("域名解析 · 珠海门户", "one.bnuzh.edu.cn", critical = true),
        dns("域名解析 · 珠海教务", "jwxt.bnuzh.edu.cn", critical = true),
        httpCheck("珠海统一认证 (HTTPS)", "https://cas.bnuzh.edu.cn/cas/login", critical = true) { body ->
            if (body.contains("loginForm")) null else "页面结构异常，可能被网络中间设备改写"
        },
        httpCheck("珠海门户 (HTTPS)", "https://one.bnuzh.edu.cn/nup/", critical = true) { null },
        httpCheck("珠海教务 (HTTPS)", "https://jwxt.bnuzh.edu.cn/frame/homes.html", critical = true) { null },
    )).let(::Report)

    private fun dns(name: String, host: String, critical: Boolean): Check {
        val t0 = System.currentTimeMillis()
        return try {
            val addrs = InetAddress.getAllByName(host)
            Check(
                name, host, true,
                addrs.joinToString(", ") { it.hostAddress ?: "?" },
                System.currentTimeMillis() - t0, critical,
            )
        } catch (e: UnknownHostException) {
            Check(
                name, host, false,
                "解析失败，通常是没有网络、DNS 异常，或该域名在当前网络被拦截",
                System.currentTimeMillis() - t0, critical,
            )
        } catch (e: Exception) {
            Check(name, host, false, e.describe(), System.currentTimeMillis() - t0, critical)
        }
    }

    private inline fun httpCheck(
        name: String,
        url: String,
        critical: Boolean,
        validate: (String) -> String?,
    ): Check {
        val t0 = System.currentTimeMillis()
        return try {
            val res = http.get(url)
            val ms = System.currentTimeMillis() - t0
            val problem = validate(res.body)
            if (problem != null) {
                Check(name, url, false, "HTTP ${res.code}，但$problem", ms, critical)
            } else {
                Check(name, url, true, "HTTP ${res.code}，返回 ${res.body.length} 字节", ms, critical)
            }
        } catch (e: Exception) {
            Check(name, url, false, e.describe(), System.currentTimeMillis() - t0, critical)
        }
    }

    companion object {
        const val ZyfwBase = "http://zyfw.bnu.edu.cn"

        /** 把异常翻成用户能据以行动的说法。 */
        fun Throwable.describe(): String = when (this) {
            is UnknownServiceException ->
                if (message?.contains("CLEARTEXT") == true) {
                    "系统安全策略拦截了明文 HTTP（应用未对该域名放行）"
                } else {
                    "协议不被支持：${message.orEmpty()}"
                }
            is UnknownHostException -> "域名无法解析，请检查网络或 DNS"
            is java.net.SocketTimeoutException -> "连接超时，当前网络到该服务器不通或过慢"
            is java.net.ConnectException -> "无法建立连接：${message.orEmpty()}"
            is SSLException -> "TLS 握手失败：${message.orEmpty()}"
            is IOException -> "网络错误：${message ?: this::class.java.simpleName}"
            else -> "${this::class.java.simpleName}：${message.orEmpty()}"
        }
    }
}
