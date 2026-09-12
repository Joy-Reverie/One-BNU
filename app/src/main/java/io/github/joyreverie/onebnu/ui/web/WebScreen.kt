package io.github.joyreverie.onebnu.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
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
import androidx.compose.runtime.produceState
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
import io.github.joyreverie.onebnu.core.net.PortalSso
import io.github.joyreverie.onebnu.core.store.Campus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.util.Log

private const val TAG = "OneBNU/WebView"
private const val BEIJING_PORTAL_PC_HOME = "https://one.bnu.edu.cn/tp_nup/index.html"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
    /** 课程中心使用：优先走官方直连 CAS；历史 OneVPN 代理跳转也只接受白名单中转。 */
    useOneVpnSso: Boolean = false,
    /** 数字京师校园服务入口使用电脑端页面与桌面浏览器 UA。 */
    desktopMode: Boolean = false,
) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // 先由同一份 OkHttp CAS 会话完成标准 SSO，再把目标站点的会话 Cookie 交给 WebView。
    // 这样不依赖 WebView 是否接受手工写入的 CASTGC；会话失效时仍回到官方登录页。
    val campus = ServiceLocator.activeCampus
    val auth = ServiceLocator.auth
    val http = ServiceLocator.http
    val portalService = PortalSso.isPortalService(campus, url)
    val syncOneVpn = useOneVpnSso || portalService
    val target by produceState<String?>(
        if (useSso || useOneVpnSso || portalService) null else url,
        url,
        useSso,
        useOneVpnSso,
        portalService,
        campus,
    ) {
        value = if (useOneVpnSso) {
            withContext(Dispatchers.IO) {
                runCatching { OneVpnSso.establish(http, auth, campus, url) }
            }
            url
        } else if (portalService) {
            // 门户入口即使没有显式要求 SSO 也必须先兑换 accessToken；否则
            // 电脑端 index.html 只会留下空壳并一直显示加载层。失败时交给官方
            // cas.html 在 WebView 中完成 OAuth，避免直接打开未认证空页面。
            withContext(Dispatchers.IO) {
                val ready = if (auth.hasSession()) {
                    runCatching { PortalSso.establish(http, auth, campus, url) }.getOrDefault(false)
                } else {
                    false
                }
                if (ready) url else PortalSso.authorizationUrl(campus, url)
            }
        } else if (useSso) {
            withContext(Dispatchers.IO) {
                runCatching { auth.sso(url).url }.getOrNull()
            } ?: auth.ssoUrl(url)
        } else {
            url
        }
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
            if (target == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            } else {
                val pageUrl = portalWebViewUrl(
                    PortalSso.webViewUrl(campus, requireNotNull(target)),
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        syncCookiesToWebView(includeOneVpn = syncOneVpn)
                        WebView(ctx).apply {
                            if (desktopMode) settings.userAgentString = DESKTOP_USER_AGENT
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
                            if (isPortalPage(pageUrl)) {
                                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            }

                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                /** 已重定向一次就不再接管，CAS 会话失效时让官方页面正常显示登录表单。 */
                                var oneVpnSsoRedirected = false
                                var portalCookieRetried = false
                                var portalBlankRetried = false
                                var portalGuideBypassed = false
                                var portalAuthRetried = false

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
                                    Log.i(TAG, "WebView 页面完成 host=${url?.toHttpUrlOrNull()?.host} path=${url?.toHttpUrlOrNull()?.encodedPath}")
                                    if (
                                        isCrossDeviceGuide(url) && !portalGuideBypassed &&
                                        PortalSso.accessToken(campus) != null
                                    ) {
                                        portalGuideBypassed = true
                                        // 这是门户官方引导页的“访问电脑端”状态，不涉及凭据；
                                        // 由官方电脑端首页继续使用已同步的 accessToken，避免手机
                                        // H5 页面再次触发同一跨设备引导。
                                        view?.evaluateJavascript(
                                            "localStorage.setItem('cross_device_guide','true');" +
                                                "localStorage.setItem('open_pc','true');" +
                                                "localStorage.setItem('is_pc','true')",
                                        ) {
                                            view.loadUrl(
                                                if (url?.toHttpUrlOrNull()?.host == "onevpn.bnu.edu.cn") {
                                                    OneVpnSso.proxyUrl(BEIJING_PORTAL_PC_HOME)
                                                } else {
                                                    BEIJING_PORTAL_PC_HOME
                                                },
                                            )
                                        }
                                        return
                                    }
                                    if (isPortalPage(url)) {
                                        Log.i(TAG, "门户页面完成 host=${url?.toHttpUrlOrNull()?.host} path=${url?.toHttpUrlOrNull()?.encodedPath}")
                                    }
                                    if (isPortalHomePage(url)) {
                                        val cookieVisible = CookieManager.getInstance()
                                            .getCookie(url.orEmpty())
                                            ?.contains("accessToken=") == true
                                        Log.i(
                                            TAG,
                                            "门户 WebView 页面完成，accessToken Cookie=$cookieVisible " +
                                                "nativeToken=${PortalSso.accessToken(campus) != null}",
                                        )
                                        if (PortalSso.accessToken(campus) != null && !cookieVisible && !portalCookieRetried) {
                                            portalCookieRetried = true
                                            syncCookiesToWebView(includeOneVpn = syncOneVpn)
                                            view?.reload()
                                        }
                                        if (!portalBlankRetried) {
                                            view?.postDelayed({
                                                view.evaluateJavascript(
                                                    "JSON.stringify({length:(document.body&&document.body.innerText||'').trim().length,children:document.body?document.body.children.length:0})",
                                                ) { length ->
                                                    // 电脑端门户先渲染壳再异步填充文本；只要 body 已有 DOM，
                                                    // 就不能把它当成空白页重载，否则会和门户脚本互相触发刷新。
                                                    val blank = length.contains("\"length\":0") &&
                                                        length.contains("\"children\":0")
                                                    Log.i(TAG, "门户 WebView 主体文本为空=$blank")
                                                    if (blank && !portalBlankRetried) {
                                                        portalBlankRetried = true
                                                        view.reload()
                                                    }
                                                }
                                            }, 1500L)
                                        }
                                    }
                                }

                                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?,
                                ) {
                                    if (view?.isShown == true) {
                                        Log.w(TAG, "WebView 主页面加载失败 code=$errorCode host=${failingUrl?.toHttpUrlOrNull()?.host}")
                                    }
                                    super.onReceivedError(view, errorCode, description, failingUrl)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    candidate: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    Log.i(TAG, "WebView 页面开始 host=${candidate?.toHttpUrlOrNull()?.host} path=${candidate?.toHttpUrlOrNull()?.encodedPath}")
                                    if (
                                        isPortalLoginPage(candidate, desktopMode) &&
                                        PortalSso.accessToken(campus) != null &&
                                        !portalAuthRetried
                                    ) {
                                        portalAuthRetried = true
                                        PortalSso.clear(campus)
                                        syncCookiesToWebView(includeOneVpn = syncOneVpn)
                                        view?.stopLoading()
                                        view?.loadUrl(PortalSso.authorizationUrl(campus, url))
                                        return
                                    }
                                    if (isPortalPage(candidate)) {
                                        syncCookiesToWebView(includeOneVpn = syncOneVpn)
                                        injectPortalCookie(view)
                                    }
                                    // 部分 WebView 版本不会把服务端 302 交给 shouldOverrideUrlLoading；
                                    // 这里作为同一可信中转的兜底，不注入账号或密码。
                                    takeOneVpnSsoUrl(candidate)?.let { target ->
                                        view?.stopLoading()
                                        view?.loadUrl(target)
                                        return
                                    }
                                    progress = 10
                                }

                                private fun injectPortalCookie(view: WebView?) {
                                    val cookie = PortalSso.webViewCookie(campus)?.second ?: return
                                    view?.evaluateJavascript(
                                        "document.cookie=${JSONObject.quote(cookie)}",
                                        null,
                                    )
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        Log.w(TAG, "WebView 控制台错误 line=${message.lineNumber()}")
                                    }
                                    return true
                                }
                            }
                            Log.i(
                                TAG,
                                "同步认证 Cookie，CASTGC=${CookieManager.getInstance().getCookie(
                                    if (campus == Campus.BEIJING) "https://cas.bnu.edu.cn/cas/login" else "https://cas.bnuzh.edu.cn/cas/login",
                                )?.contains("CASTGC=") == true}",
                            )
                            post { loadUrl(pageUrl) }
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
}

private fun isPortalPage(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return parsed.host == "one.bnu.edu.cn" || parsed.host == "one.bnuzh.edu.cn" ||
        parsed.host == "onevpn.bnu.edu.cn" &&
        (parsed.encodedPath.contains("/tp_nup/") || parsed.encodedPath.contains("/nup/"))
}

private fun isPortalHomePage(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return when (parsed.host) {
        "one.bnu.edu.cn" -> parsed.encodedPath == "/tp_nup/" ||
            parsed.encodedPath == "/tp_nup/index.html" ||
            parsed.encodedPath.endsWith("/resource/defaults/html/h5/loginHome.html")
        "one.bnuzh.edu.cn" -> parsed.encodedPath == "/nup/" ||
            parsed.encodedPath == "/nup/index.html" ||
            parsed.encodedPath.endsWith("/resource/defaults/html/h5/loginHome.html")
        "onevpn.bnu.edu.cn" -> parsed.encodedPath.contains("/tp_nup/") ||
            parsed.encodedPath.contains("/nup/")
        else -> false
    }
}

internal fun isPortalLoginPage(url: String?, desktopMode: Boolean = false): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return when (parsed.host) {
        "one.bnu.edu.cn" -> if (desktopMode) {
            parsed.encodedPath == "/tp_nup/guide.html"
        } else {
            parsed.encodedPath == "/tp_nup/index.html" || parsed.encodedPath == "/tp_nup/guide.html"
        }
        "one.bnuzh.edu.cn" -> parsed.encodedPath == "/nup/index.html" ||
            parsed.encodedPath == "/nup/guide.html"
        "onevpn.bnu.edu.cn" -> parsed.encodedPath.endsWith("/tp_nup/index.html") ||
            parsed.encodedPath.endsWith("/tp_nup/guide.html") ||
            parsed.encodedPath.endsWith("/nup/index.html") ||
            parsed.encodedPath.endsWith("/nup/guide.html")
        else -> false
    }
}

private fun isCrossDeviceGuide(url: String?): Boolean =
    url?.toHttpUrlOrNull()?.let {
        (it.host == "one.bnu.edu.cn" || it.host == "onevpn.bnu.edu.cn") &&
            it.encodedPath.endsWith("/resource/defaults/html/guide/crossDeviceGuide.html")
    } == true

private fun portalWebViewUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    return if (parsed.host == "one.bnu.edu.cn" && parsed.encodedPath == "/tp_nup/") {
        BEIJING_PORTAL_PC_HOME
    } else {
        url
    }
}

/** 把 OkHttp 里的 CAS/教务会话写进 WebView，实现免密打开。 */
private fun syncCookiesToWebView(includeOneVpn: Boolean = true) {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    val jar = ServiceLocator.http.cookies
    val casHost: String
    val domains = if (ServiceLocator.activeCampus == Campus.BEIJING) {
        casHost = "cas.bnu.edu.cn"
        listOf(
            "https://cas.bnu.edu.cn/cas/login",
            "https://one.bnu.edu.cn/",
            "https://onevpn.bnu.edu.cn/",
            "https://kczx.bnu.edu.cn/",
            "http://zyfw.bnu.edu.cn/",
        )
    } else {
        casHost = "cas.bnuzh.edu.cn"
        listOf("https://cas.bnuzh.edu.cn/cas/login", "https://one.bnuzh.edu.cn/nup/", "https://jwxt.bnuzh.edu.cn/")
    }
    // 旧版本可能把 CASTGC 按 `.bnu.edu.cn` / `.bnuzh.edu.cn` 写入过 WebView。
    // 先清掉这个跨子域副本，再只为 CAS 主机种 host-only 票据，避免 OneVPN 等子域收到它。
    val parentDomain = casHost.substringAfter('.')
    cm.setCookie(
        "https://$casHost/",
        "${BnuCookieJar.CAS_TICKET}=; Max-Age=0; Path=/; Domain=$parentDomain",
    )
    cm.setCookie(
        "https://$casHost/",
        "${BnuCookieJar.CAS_TICKET}=; Max-Age=0; Path=/; Domain=$casHost",
    )
    PortalSso.webViewCookieTarget(ServiceLocator.activeCampus)?.let { (url, cookie) ->
        cm.setCookie(url, cookie)
    }
    if (includeOneVpn) {
        PortalSso.webViewProxyCookie(ServiceLocator.activeCampus)?.let { (url, cookie) ->
            cm.setCookie(url, cookie)
        }
    }
    PortalSso.webViewCookie(ServiceLocator.activeCampus)?.let { (url, cookie) ->
        cm.setCookie(url, cookie)
    }
    for (domain in domains.filter { includeOneVpn || it != "https://onevpn.bnu.edu.cn/" }) {
        val url = domain.toHttpUrlOrNull() ?: continue
        for (c in jar.loadForRequest(url)) {
            val isCasTicket = c.name == BnuCookieJar.CAS_TICKET
            // CAS ticket 只交给认证主机；即使服务端给了 `.bnu.edu.cn` 域属性，也不能
            // 因同步到 WebView 而扩大到 OneVPN / 门户等其他子域。
            if (isCasTicket && url.host != casHost) continue
            cm.setCookie(
                                domain,
                                c.webViewValue(hostOnly = isCasTicket),
            )
        }
    }
    cm.flush()
}

/** 保留服务端原有的 secure / HttpOnly 属性；CAS ticket 则故意省略 Domain 以成为 host-only Cookie。 */
private fun Cookie.webViewValue(hostOnly: Boolean): String = buildString {
    append("$name=$value; Path=$path")
    if (!hostOnly) {
        append("; Domain=$domain")
    }
    if (secure) append("; Secure")
    if (httpOnly) append("; HttpOnly")
}
