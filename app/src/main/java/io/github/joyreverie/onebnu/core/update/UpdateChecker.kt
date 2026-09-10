package io.github.joyreverie.onebnu.core.update

import io.github.joyreverie.onebnu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** GitHub Releases 上的一个发布版本。 */
data class ReleaseInfo(
    /** 去掉前缀 v 的版本号，如 `1.8.0`。 */
    val version: String,
    val tag: String,
    val title: String,
    /** 发布说明原文（Markdown）。 */
    val notes: String,
    /** 发布页地址。 */
    val pageUrl: String,
    /** 发布日期 `yyyy-MM-dd`，接口没给则为空串。 */
    val publishedAt: String,
    /** 第一个 `.apk` 附件的直链；没有附件时为 null。 */
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
) {
    /** 把 Markdown 说明压成适合对话框的纯文本：去标题井号、粗体星号、行内代码，列表项改圆点。 */
    fun plainNotes(): String = notes
        .replace("\r\n", "\n")
        .lines()
        .map { line ->
            line.trimEnd()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("^\\s*[-*]\\s+"), "• ")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

sealed class UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult()
    data class UpToDate(val release: ReleaseInfo) : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

/**
 * 向 GitHub 查最新发布版本。
 *
 * 只请求公开接口 `GET /repos/{owner}/{repo}/releases/latest`，不带任何身份信息；
 * 该接口本身就不返回预发布与草稿。未登录调用的配额是每小时 60 次（按来源 IP 计），
 * 对手动点一下「检查更新」足够。
 */
class UpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val client: OkHttpClient = defaultClient(),
) {

    val releasesPage: String get() = "https://github.com/$repo/releases"

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatest()
            if (isNewer(latest.version, currentVersion)) UpdateResult.Available(latest)
            else UpdateResult.UpToDate(latest)
        } catch (e: UnknownHostException) {
            UpdateResult.Failed("网络不可用")
        } catch (e: SocketTimeoutException) {
            UpdateResult.Failed("连接超时")
        } catch (e: IOException) {
            UpdateResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "网络错误")
        } catch (e: JSONException) {
            UpdateResult.Failed("返回数据无法解析")
        }
    }

    @Throws(IOException::class, JSONException::class)
    fun fetchLatest(): ReleaseInfo {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "One-BNU/$currentVersion (Android)")
            .build()
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            return when (res.code) {
                200 -> parse(body)
                404 -> throw IOException("尚未发布任何版本")
                403, 429 -> throw IOException("请求过于频繁，请稍后再试")
                else -> throw IOException("GitHub 返回了 ${res.code}")
            }
        }
    }

    companion object {

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** 解析 `releases/latest` 的响应体。 */
        @Throws(JSONException::class)
        fun parse(json: String): ReleaseInfo {
            val o = JSONObject(json)
            val tag = o.getString("tag_name")
            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                        apkName = name
                        apkSize = a.optLong("size")
                        break
                    }
                }
            }
            return ReleaseInfo(
                version = normalize(tag),
                tag = tag,
                title = o.optString("name").ifBlank { tag },
                notes = o.optString("body"),
                pageUrl = o.optString("html_url"),
                publishedAt = o.optString("published_at").take(10),
                apkUrl = apkUrl,
                apkName = apkName,
                apkSize = apkSize,
            )
        }

        /** `v1.8.0` → `1.8.0`。 */
        fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

        fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

        /**
         * 按点分数字段逐段比较，缺省段按 0（`1.8` 等于 `1.8.0`）；
         * `-beta` / `+build` 之类的后缀不参与比较。
         */
        fun compare(a: String, b: String): Int {
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        private fun parts(version: String): List<Int> = normalize(version)
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
