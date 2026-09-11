package io.github.joyreverie.onebnu.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.net.BnuHosts
import io.github.joyreverie.onebnu.core.net.BnuCookieJar
import io.github.joyreverie.onebnu.core.net.OneVpnSso
import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 内嵌浏览器。
 *
 * 用于图书馆、门户等没有稳定接口、也不适合抓取的系统：
 * 把应用已经持有的 CAS 会话同步给 WebView，用户不必再登录一次。
 *
 * 安全上做了收紧：不开 JS 接口桥、不允许文件域访问、只在北师大域名内跳转，
 * 站外链接一律交给系统浏览器。OneVPN SSO 走标准 CAS service ticket，不向网页填充密码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    title: String,
    url: String,
    useSso: Boolean,
    onBack: () -> Unit,
    /** 仅课程中心使用：识别可信的 OneVPN → 北京 CAS 中转，并改走当前 CAS 会话。 */
    useOneVpnSso: Boolean = false,
) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val target = remember(url, useSso) {
        if (useSso) ServiceLocator.api.ssoUrl(url) else url
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (canGoBack) webView?.goBack() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "刷新") }
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(webView?.url ?: url),
                                ),
                            )
                        }
                    }) { Icon(Icons.Filled.OpenInBrowser, "在浏览器中打开") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    syncCookiesToWebView()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // 收紧：不放开本地文件与内容提供者访问
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        // 教务系统只有 HTTP，门户是 HTTPS，允许混合内容会削弱 HTTPS 页面，故禁用
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            /** 已重定向一次就不再接管，CAS 会话失效时让官方页面正常显示登录表单。 */
                            var oneVpnSsoRedirected = false

                            fun takeOneVpnSsoUrl(candidate: String?): String? {
                                if (!useOneVpnSso || oneVpnSsoRedirected) return null
                                val service = OneVpnSso.serviceForRelayRedirect(
                                    candidate?.toHttpUrlOrNull(),
                                    campus = ServiceLocator.activeCampus,
                                    hasCasSession = ServiceLocator.auth.hasSession(),
                                ) ?: return null
                                oneVpnSsoRedirected = true
                                // `ssoUrl` 只会指向当前北京 CAS；密码仍只留在 SecureStore，
                                // WebView 只收到 CAS 返回的一次性 service ticket。
                                return ServiceLocator.auth.ssoUrl(service)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url ?: return false
                                takeOneVpnSsoUrl(u.toString())?.let { target ->
                                    view?.loadUrl(target)
                                    return true
                                }
                                val host = u.host.orEmpty()
                                // 校外链接交给系统浏览器，避免在内嵌页里输入账号
                                if (!BnuHosts.isBnu(host)) {
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, u),
                                        )
                                    }
                                    return true
                                }
                                // 教务会 302 到明文的统一认证，而明文策略只放行了教务和图书馆，
                                // WebView 撞上这一跳会直接白屏。和 OkHttp 侧一样，把它升回 HTTPS。
                                if (u.scheme == "http" && !BnuHosts.isHttpOnly(host)) {
                                    view?.loadUrl(u.buildUpon().scheme("https").build().toString())
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                // 部分 WebView 版本不会把服务端 302 交给 shouldOverrideUrlLoading；
                                // 这里作为同一可信中转的兜底，不执行或注入网页脚本。
                                takeOneVpnSsoUrl(url)?.let { target ->
                                    view?.stopLoading()
                                    view?.loadUrl(target)
                                    return
                                }
                                progress = 10
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(target)
                        webView = this
                    }
                },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** 把 OkHttp 里的 CAS/教务会话写进 WebView，实现免密打开。 */
private fun syncCookiesToWebView() {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    val jar = ServiceLocator.http.cookies
    val casHost: String
    val domains = if (ServiceLocator.activeCampus == Campus.BEIJING) {
        casHost = "cas.bnu.edu.cn"
        listOf("https://cas.bnu.edu.cn/", "https://one.bnu.edu.cn/", "http://zyfw.bnu.edu.cn/")
    } else {
        casHost = "cas.bnuzh.edu.cn"
        listOf("https://cas.bnuzh.edu.cn/", "https://one.bnuzh.edu.cn/", "https://jwxt.bnuzh.edu.cn/")
    }
    // 旧版本可能把 CASTGC 按 `.bnu.edu.cn` / `.bnuzh.edu.cn` 写入过 WebView。
    // 先清掉这个跨子域副本，再只为 CAS 主机种 host-only 票据，避免 OneVPN 等子域收到它。
    val parentDomain = casHost.substringAfter('.')
    cm.setCookie(
        "https://$casHost/",
        "${BnuCookieJar.CAS_TICKET}=; Max-Age=0; Path=/; Domain=$parentDomain",
    )
    for (domain in domains) {
        val url = domain.toHttpUrlOrNull() ?: continue
        for (c in jar.loadForRequest(url)) {
            val isCasTicket = c.name == BnuCookieJar.CAS_TICKET
            // CAS ticket 只交给认证主机；即使服务端给了 `.bnu.edu.cn` 域属性，也不能
            // 因同步到 WebView 而扩大到 OneVPN / 门户等其他子域。
            if (isCasTicket && url.host != casHost) continue
            cm.setCookie(domain, c.webViewValue(hostOnly = isCasTicket))
        }
    }
    cm.flush()
}

/** 保留服务端原有的 secure / HttpOnly 属性；CAS ticket 则故意省略 Domain 以成为 host-only Cookie。 */
private fun Cookie.webViewValue(hostOnly: Boolean): String = buildString {
    append("$name=$value; Path=$path")
    if (!hostOnly) append("; Domain=$domain")
    if (secure) append("; Secure")
    if (httpOnly) append("; HttpOnly")
}
