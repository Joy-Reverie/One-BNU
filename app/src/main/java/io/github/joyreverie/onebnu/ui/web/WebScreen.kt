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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.util.Log

private const val TAG = "OneBNU/WebView"

/** 蜂窝下建教务代理会话的等待上限；超时就先把页面加载出来。 */
private const val ACADEMIC_PROXY_TIMEOUT_MS = 20_000L
private const val BEIJING_PORTAL_PC_HOME = "https://one.bnu.edu.cn/tp_nup/index.html"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** 学校电脑端 CSS 在 Android WebView 中偶尔把 100vh 根容器算成 0px。 */
private const val PORTAL_LAYOUT_FIX = """
(function() {
    var h = Math.max(window.innerHeight || 0, document.documentElement.clientHeight || 0, 1);
    var px = h + 'px';
    document.documentElement.style.setProperty('height', px, 'important');
    if (document.body) document.body.style.setProperty('height', px, 'important');
    [
        '#language_container',
        '.ec-page-outside-container',
        '.ec-page-main-container',
        '.ec-page-nav-container',
        '.ec-page-nav-bg',
        '.ec-page-body',
        '.ec-page-content-container'
    ].forEach(function(selector) {
        document.querySelectorAll(selector).forEach(function(element) {
            element.style.setProperty('min-height', px, 'important');
            element.style.setProperty('height', px, 'important');
        });
    });
    window.dispatchEvent(new Event('resize'));
})();
"""

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
    val academicService = isAcademicService(url)
    // 教务系统只有 HTTP，蜂窝网络常常无法访问 80 端口。此时把整个 WebView
    // 页面放到已经认证的 OneVPN HTTPS 代理上，避免看到空白页或无限加载。
    val useAcademicProxy = campus == Campus.BEIJING && academicService && ServiceLocator.isCellularNetwork()
    val syncOneVpn = useOneVpnSso || portalService || useAcademicProxy
    val target by produceState<String?>(
        if (useSso || useOneVpnSso || portalService || useAcademicProxy) null else url,
        url,
        useSso,
        useOneVpnSso,
        portalService,
        useAcademicProxy,
        campus,
    ) {
        // 冷启动后只剩离线快照时这里还没有 CAS 会话，先补一次静默登录，
        // 否则下面每条分支都会把用户送回统一认证登录页。
        withContext(Dispatchers.IO) { ServiceLocator.ensureSession() }
        value = if (useOneVpnSso) {
            withContext(Dispatchers.IO) {
                runCatching { OneVpnSso.establish(http, auth, campus, url) }
            }
            url
        } else if (useAcademicProxy) {
            // 教务只有 HTTP 80，蜂窝网络下直连必失败。建会话时就要用**代理地址**：
            // 交原始地址的话 OneVpnSso 会先直连 zyfw:80，超时后落回 CAS 登录页 → 白屏。
            // 数据接口（ZyfwApi.establishProxySession）传的一直是代理地址。
            val proxied = academicProxyUrl(url)
            // 建会话要走十来跳，蜂窝下每一跳都可能等满超时。给它一个上限：
            // 到点先把代理地址加载出来，会话在后台继续建，页面上还有刷新按钮兜底 ——
            // 总比一直停在进度条上强。
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(ACADEMIC_PROXY_TIMEOUT_MS) {
                    runCatching { OneVpnSso.establish(http, auth, campus, proxied) }
                }
            }
            // 会话没建起来也不退回明文直连 —— 蜂窝下那条路本来就不通，
            // 仍然加载代理地址，最坏情况是 OneVPN 自己显示一次登录页，而不是白屏。
            proxied
        } else if (portalService) {
            // 校外时门户会把访问者送去 OneVPN。应用侧若已经（或现在能）经代理换到 token，
            // 就直接打开**代理路径下**的门户首页，token 由 syncCookiesToWebView 种在代理路径上；
            // WebView 不再自己跑一遍 OAuth —— 那会撞上 OneVPN 的 /login，再登录一次把已有会话踢掉。
            // 校园网下门户不走代理，仍由门户自己的 cas.html 在 WebView 里完成 OAuth。
            val viaProxy = withContext(Dispatchers.IO) {
                withTimeoutOrNull(ACADEMIC_PROXY_TIMEOUT_MS) {
                    if (PortalSso.accessToken(campus) == null) {
                        runCatching { PortalSso.establish(http, auth, campus, url) }
                    }
                    PortalSso.usesProxy(campus)
                } ?: PortalSso.usesProxy(campus)
            }
            if (viaProxy) url else PortalSso.authorizationUrl(campus, url)
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
                            if (portalService || isPortalPage(pageUrl)) {
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

                                /**
                                 * OneVPN 的 CAS service 是它自己的 `/login?cas_login=true`，不是我们要的资源。
                                 * 票据兑换完成后，wengine 会把浏览器留在它自己的门户 / 拒绝页上，原本要开的
                                 * 教务地址就丢了 —— 用户看到的就是「访问被拒绝」。OkHttp 那条链路
                                 * （`OneVpnSso.establishLocked`）在这一步之后会显式再取一次目标地址，
                                 * WebView 这边以前没有这一步，这里补上，只补一次以免来回打转。
                                 */
                                var academicTargetReloaded = false

                                /** wengine 接受 token 后只回目标页一次，避免来回打转。 */
                                var oneVpnTokenReturned = false

                                /** 已有会话却被送去 /login 时只同步一次票据；再撞上就真的重新登录。 */
                                var oneVpnResynced = false

                                /** 最近一次看到的门户地址，被门户踢去 OneVPN 时按它拼代理地址。 */
                                var lastPortalPage: String? = null

                                fun takeOneVpnSsoUrl(candidate: String?): String? {
                                    // 门户在校外也会被送去 OneVPN，同样用 CASTGC 直接为它签票，不让用户再输一次密码
                                    if (!(useOneVpnSso || useAcademicProxy || portalService) || oneVpnSsoRedirected) return null
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
                                    if (u.host == "one.bnu.edu.cn" || u.host == "one.bnuzh.edu.cn") lastPortalPage = u.toString()
                                    // 应用侧明明已有 OneVPN 会话，WebView 却被送去登录：多半是两边票据不一致。
                                    // 先把应用侧票据同步过来、回到代理页；**不能**再登录一次 —— OneVPN 单会话，
                                    // 新登录会把应用侧那份也踢掉（1.9.36 的「退出网页后成绩考试不同步」就是这么来的）。
                                    if (
                                        u.host == "onevpn.bnu.edu.cn" && u.path == "/login" &&
                                        OneVpnSso.sessionEstablished && !oneVpnResynced
                                    ) {
                                        oneVpnResynced = true
                                        syncCookiesToWebView(includeOneVpn = true)
                                        val back = lastPortalPage?.takeIf { portalService }?.let(OneVpnSso::proxyUrl) ?: pageUrl
                                        Log.i(TAG, "OneVPN 已有会话，同步票据后回到代理页")
                                        view?.loadUrl(back)
                                        return true
                                    }
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
                                    // wengine 接受了 token（200 空页）：VPN 会话已在 WebView 自己的票据上，回到目标页。
                                    // 这里**不要**再同步 OkHttp 的 Cookie，那会把 WebView 刚拿到的会话覆盖掉。
                                    if (isOneVpnTokenAccepted(url) && !oneVpnTokenReturned) {
                                        oneVpnTokenReturned = true
                                        Log.i(TAG, "OneVPN 已接受 token，回到目标页")
                                        // WebView 自己登了一次 OneVPN：把新票据交回 OkHttp，两边共用同一个会话
                                        syncOneVpnTicketToOkHttp()
                                        val back = lastPortalPage?.takeIf { portalService }?.let(OneVpnSso::proxyUrl) ?: pageUrl
                                        view?.loadUrl(back)
                                        return
                                    }
                                    // 其他情况下停在 OneVPN 自己的页面（如它的门户首页）：走回教务那条代理路径
                                    if (
                                        useAcademicProxy && !academicTargetReloaded &&
                                        leftAcademicProxyPath(url, pageUrl)
                                    ) {
                                        academicTargetReloaded = true
                                        Log.i(TAG, "回到教务代理地址")
                                        view?.loadUrl(pageUrl)
                                        return
                                    }
                                    if (isPortalHomePage(url)) {
                                        view?.evaluateJavascript(PORTAL_LAYOUT_FIX, null)
                                    }
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

internal fun isAcademicService(url: String): Boolean =
    url.toHttpUrlOrNull()?.host == "zyfw.bnu.edu.cn"

/**
 * 蜂窝网络下教务网页入口真正要用的地址。
 *
 * 建会话和加载页面必须是**同一条 OneVPN 代理路径**：把原始 `http://zyfw…` 交给
 * `OneVpnSso.establish`，它会先直连 80 端口，在流量网络下必然超时，最后落到 CAS 登录页 → 白屏。
 */
internal fun academicProxyUrl(url: String): String = OneVpnSso.proxyUrl(url)

/**
 * WebView 是不是已经从教务的代理路径上掉下来、并且可以走回去了。
 *
 * OneVPN 的 CAS service 写死成它自己的 `/login?cas_login=true`：票据换完之后 wengine 把浏览器
 * 留在自己的门户页上，我们真正要打开的 `/http/<加密主机>/…` 就丢了。
 * 判据是**代理前缀**（`/http/<加密主机>`）而不是整条地址 —— 教务自己在这个前缀下跳转是正常的。
 *
 * 但**停在登录页上时一定不能走回去**：那一页正等着用户输账号，把它顶掉只会在
 * 「目标地址 → 登录页 → 目标地址」之间来回打转，谁也登不进去。
 */
internal fun leftAcademicProxyPath(current: String?, target: String): Boolean {
    val now = current?.toHttpUrlOrNull() ?: return false
    val want = target.toHttpUrlOrNull() ?: return false
    if (now.host != want.host) return false
    if (isOneVpnLoginPage(now)) return false
    // /http/<加密主机>/… → 取前两段作为前缀
    val prefix = want.pathSegments.take(2)
    if (prefix.size < 2) return false
    return now.pathSegments.take(2) != prefix
}

/**
 * wengine 接受 token 之后给非浏览器客户端（Android WebView 因为自带 `X-Requested-With` 也算）的落点：
 * `/wengine-vpn-token-login?token=…` 返回一个 200 空页。此刻 VPN 会话已经挂在当前票据上，
 * 回到原本要打开的页面即可。**绝不能去 `/token-login`**——同一个 token 再登录一次，
 * OneVPN 单会话，第二次把第一次踢掉，用户就又回到登录页（1.9.36 的「登录后又回到登录页」正是它）。
 */
internal fun isOneVpnTokenAccepted(url: String?): Boolean {
    val u = url?.toHttpUrlOrNull() ?: return false
    return u.host == "onevpn.bnu.edu.cn" && u.encodedPath.endsWith("/wengine-vpn-token-login")
}

/** OneVPN 自己的登录入口，或它代理出来的统一认证登录页。 */
private fun isOneVpnLoginPage(url: HttpUrl): Boolean =
    url.encodedPath == "/login" || url.encodedPath.endsWith("/cas/login") ||
        url.encodedPath.endsWith("/wengine-vpn-token-login") || url.encodedPath.endsWith("/token-login")

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
        // 代理路径下同样区分电脑端：电脑端的 index.html 是首页，不是登录页
        "onevpn.bnu.edu.cn" -> if (desktopMode) {
            parsed.encodedPath.endsWith("/tp_nup/guide.html") || parsed.encodedPath.endsWith("/nup/guide.html")
        } else {
            parsed.encodedPath.endsWith("/tp_nup/index.html") ||
                parsed.encodedPath.endsWith("/tp_nup/guide.html") ||
                parsed.encodedPath.endsWith("/nup/index.html") ||
                parsed.encodedPath.endsWith("/nup/guide.html")
        }
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

/**
 * WebView 自己完成了 OneVPN 登录后，把新票据交给 OkHttp。
 * OneVPN 单会话：两边若各持一枚票据，任何一边再登录都会把另一边踢掉；教务会话按新票据重建。
 */
private fun syncOneVpnTicketToOkHttp() {
    val raw = CookieManager.getInstance().getCookie("https://onevpn.bnu.edu.cn/") ?: return
    val ticket = raw.split(';').map { it.trim() }.firstOrNull { it.startsWith("wengine_vpn_ticket") } ?: return
    val name = ticket.substringBefore('=')
    val value = ticket.substringAfter('=', "")
    if (value.isBlank()) return
    val url = "https://onevpn.bnu.edu.cn/".toHttpUrlOrNull() ?: return
    ServiceLocator.http.cookies.saveFromResponse(
        url,
        listOf(
            Cookie.Builder().name(name).value(value)
                .domain("onevpn.bnu.edu.cn").path("/").secure().httpOnly().build(),
        ),
    )
    OneVpnSso.markSessionEstablished()
    ServiceLocator.api.invalidate()
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
    // 门户 accessToken：只有应用侧真的换到了 token 才覆盖 WebView 里的那一份。
    // 以前这里无条件先删再写，应用侧换 token 失败（校外常见）时就把用户在网页里
    // 手动登录刚拿到的 token 删掉了 —— 表现为「登录成功又回到登录页」。
    PortalSso.webViewCookie(ServiceLocator.activeCampus)?.let { (url, cookie) ->
        PortalSso.webViewCookieTarget(ServiceLocator.activeCampus)?.let { (u, c) -> cm.setCookie(u, c) }
        cm.setCookie(url, cookie)
    }
    if (includeOneVpn) {
        PortalSso.webViewProxyCookie(ServiceLocator.activeCampus)?.let { (url, cookie) ->
            cm.setCookie(url, cookie)
        }
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
