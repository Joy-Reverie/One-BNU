package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.data.remote.ZhuhaiCasClient
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import java.io.File
import java.util.Base64

/**
 * 开发用：借应用自己保存的会话**直连**教务，按 `files/probe_urls.txt` 逐行抓取页面，
 * 结果落在 `files/probe/`，供 `adb shell run-as … cat` 取回分析。
 *
 * 只读：只做 GET / 查询类 POST，用来核对报表结构与接口参数；不走 OneVPN 代理，
 * 因而不会把用户手机上的 OneVPN 会话踢下线。不进 release 包。
 *
 * 请求行格式（`#` 开头为注释）：
 * ```
 * GET /frame/homes.html
 * GET /frame/menus/JW1304.jsp?menucode=JW1304 | referer=/frame/homes.html
 * POST /taglib/DataTable.jsp?tableId=5327008 | xh={xh}&xn={xn}&xn1={xn1}&xq_m={xq}&_xq={xq}&ck_px=akcmk | referer=/student/wsxk.pyfadb.html
 * ```
 * 占位符：`{token}` 报表令牌，`{xh}` 学号，`{xn}` `{xn1}` `{xq}` 当前学年学期，`{params}` = base64("xn={xn}&xq={xq}")。
 *
 * 启动：`adb shell am start -n io.github.joyreverie.onebnu/.widget.ZyfwProbeActivity --es campus beijing`
 */
class ZyfwProbeActivity : ComponentActivity() {
    private var log by mutableStateOf("准备中…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val campus = if (intent.getStringExtra("campus") == "zhuhai") Campus.ZHUHAI else Campus.BEIJING
        val listName = intent.getStringExtra("list") ?: "probe_urls.txt"
        setContent {
            OneBnuTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(log, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Thread { run(campus, listName) }.start()
    }

    private fun append(line: String) {
        Log.i(TAG, line)
        runOnUiThread { log = log + "\n" + line }
    }

    private fun run(campus: Campus, listName: String) {
        val outDir = File(filesDir, "probe").apply { mkdirs() }
        val index = StringBuilder()
        try {
            ServiceLocator.selectCampus(campus)
            val http = ServiceLocator.http
            val auth = ServiceLocator.auth
            append("campus=$campus session=${auth.hasSession()} credentials=${ServiceLocator.secure.hasCredentials}")
            if (!ServiceLocator.ensureSession()) {
                append("没有可用的 CAS 会话，且静默重登失败"); return
            }
            val base = if (campus == Campus.BEIJING) ZyfwApi.BASE else ZhuhaiCasClient.JWXT_BASE
            val api = ZyfwApi(http, auth, base)
            api.ensureSession()
            val ctx = api.userContext
            append("教务会话已建立（直连）xn=${ctx?.currentXn} xq=${ctx?.currentXq} term=${ctx?.termDesc}")
            val home = "$base/frame/homes.html"
            val token = http.get("$base/frame/menus/js/SetTokenkey.jsp", home).body.replace(Regex("\\s"), "")
            val xh = ctx?.userCode?.ifBlank { null } ?: ctx?.loginId.orEmpty()
            val xn = ctx?.currentXn.orEmpty()
            val xq = ctx?.currentXq.orEmpty()
            val xn1 = xn.toIntOrNull()?.plus(1)?.toString().orEmpty()
            val params = Base64.getEncoder().encodeToString("xn=$xn&xq=$xq".toByteArray())
            fun fill(s: String) = s
                .replace("{token}", token)
                .replace("{xh}", xh)
                .replace("{xn}", xn)
                .replace("{xn1}", xn1)
                .replace("{xq}", xq)
                .replace("{params}", params)
            fun abs(path: String) = if (path.startsWith("http")) path else base + path

            val lines = File(filesDir, listName).takeIf { it.exists() }?.readLines()
                ?: run { append("没有 $listName"); return }
            var n = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#")) continue
                n++
                val parts = line.split("|").map { it.trim() }
                val (method, path) = parts[0].split(Regex("\\s+"), limit = 2).let { it[0].uppercase() to fill(it.getOrElse(1) { "" }) }
                var referer = home
                var body: String? = null
                parts.drop(1).forEach { p ->
                    if (p.startsWith("referer=")) referer = abs(fill(p.removePrefix("referer="))) else body = fill(p)
                }
                val name = "%02d".format(n)
                try {
                    val res = if (method == "POST") {
                        val form = body.orEmpty().split("&").filter { it.isNotBlank() }.associate { kv ->
                            val i = kv.indexOf('=')
                            if (i < 0) kv to "" else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                        }
                        http.postForm(abs(path), form, referer = referer)
                    } else {
                        http.get(abs(path), referer)
                    }
                    File(outDir, "$name.html").writeText(res.body)
                    val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(res.body)?.groupValues?.get(1)?.trim().orEmpty()
                    val summary = "$name [${res.code}] $method $path (${res.body.length} chars) ${res.url} title=$title"
                    index.appendLine(summary)
                    append(summary)
                } catch (e: Exception) {
                    val summary = "$name [ERR] $method $path ${e.javaClass.simpleName}: ${e.message}"
                    index.appendLine(summary)
                    append(summary)
                }
            }
            append("DONE $n requests")
        } catch (e: Exception) {
            append("FAILED ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            File(outDir, "index.txt").writeText(index.toString())
        }
    }

    private companion object {
        const val TAG = "OneBNU/Probe"
    }
}
