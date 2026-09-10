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
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 内嵌浏览器。
 *
 * 用于图书馆、门户等没有稳定接口、也不适合抓取的系统：
 * 把应用已经持有的 CAS 会话同步给 WebView，用户不必再登录一次。
 *
 * 安全上做了收紧：不开 JS 接口桥、不允许文件域访问、只在北师大域名内跳转，
 * 站外链接一律交给系统浏览器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(title: String, url: String, useSso: Boolean, onBack: () -> Unit) {
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
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url ?: return false
                                val host = u.host.orEmpty()
                                // 校外链接交给系统浏览器，避免在内嵌页里输入账号
                                if (!host.endsWith("bnu.edu.cn")) {
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
    for (domain in listOf("https://cas.bnu.edu.cn/", "https://one.bnu.edu.cn/", "${ZyfwApi.BASE}/")) {
        val url = domain.toHttpUrlOrNull() ?: continue
        for (c in jar.loadForRequest(url)) {
            cm.setCookie(domain, "${c.name}=${c.value}; Path=/; Domain=${c.domain}")
        }
    }
    cm.flush()
}
