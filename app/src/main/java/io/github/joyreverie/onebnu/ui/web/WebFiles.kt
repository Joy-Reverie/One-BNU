package io.github.joyreverie.onebnu.ui.web

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.widget.Toast
import io.github.joyreverie.onebnu.core.net.BnuHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/*
 * 内嵌页里的文件进出：页面要下载的交给系统下载，`<input type=file>` 要的文件由系统选择器挑。
 * 目前只有「师大云盘」开了这两样，见 WebScreen 的 panMode。
 */

private const val TAG = "OneBNU/WebFiles"

/** 文件名最长保留多少个字符：再长的名字在通知栏和文件管理里也看不全，还可能超出文件系统的限制。 */
private const val MAX_FILE_NAME = 120

private val EXTENDED_FILENAME = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;\s]+)""", RegexOption.IGNORE_CASE)
private val QUOTED_FILENAME = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
private val PLAIN_FILENAME = Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
private val QUOTED_PAIR = Regex("""\\(.)""")
private val UNSAFE_NAME_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/**
 * 把一次页面下载交给系统的 DownloadManager：自带进度通知、断点续传，下完点通知就能打开。
 *
 * 只接学校主机上的 HTTPS 下载，WebView 里这个站点的 Cookie 随请求带上（云盘的下载地址本身
 * 也带着会话）；别处的地址交给系统浏览器，Cookie 不带出学校域名。Android 10 起落在公共的
 * 「下载」目录，更早的系统没有存储权限，落在应用自己的外部目录，从通知里打开。
 */
internal fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" || !BnuHosts.isBnu(uri.host.orEmpty())) {
        if (scheme == "http" || scheme == "https") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        } else {
            Toast.makeText(context, "这个文件没法在应用里下载", Toast.LENGTH_SHORT).show()
        }
        return
    }
    val name = downloadFileName(url, contentDisposition)
        ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
    val extension = name.substringAfterLast('.', "").lowercase()
    val type = mimeType?.takeUnless { it.isBlank() || it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: mimeType?.takeIf { it.isNotBlank() }
    val started = runCatching {
        val request = DownloadManager.Request(uri)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        type?.let(request::setMimeType)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        }
        requireNotNull(context.getSystemService(DownloadManager::class.java)).enqueue(request)
    }.onFailure {
        // 地址里带着会话，不进日志
        Log.w(TAG, "下载没能开始 ${it::class.java.simpleName}")
    }.isSuccess
    Toast.makeText(
        context,
        if (started) "已开始下载：$name" else "没能开始下载，请稍后再试",
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * 下载的文件名。先看 `Content-Disposition`：RFC 5987 的 `filename*` 优先，其次普通的 `filename`
 * （不少服务端把中文名百分号编码后塞在这里，能解就解）；都没有时取地址最后一段 —— 云盘的下载地址
 * 是 `/v2/dl_router/databox/<编码后的完整路径>`，解码后最后一截就是文件名，但只认带扩展名的。
 * 结果去掉文件系统不接受的字符（清理完什么都不剩就当没给）；都取不到时为 null，调用方退回
 * `URLUtil.guessFileName`。
 */
internal fun downloadFileName(url: String, contentDisposition: String?): String? =
    contentDisposition?.let(::dispositionFileName)?.let(::sanitizeFileName)
        ?: urlFileName(url)?.let(::sanitizeFileName)

private fun dispositionFileName(header: String): String? {
    EXTENDED_FILENAME.find(header)?.let { m ->
        val charset = runCatching { Charset.forName(m.groupValues[1].trim().ifEmpty { "UTF-8" }) }
            .getOrDefault(Charsets.UTF_8)
        percentDecode(m.groupValues[2], charset)?.let { return it }
    }
    val value = QUOTED_FILENAME.find(header)?.groupValues?.get(1)?.replace(QUOTED_PAIR, "$1")
        ?: PLAIN_FILENAME.find(header)?.groupValues?.get(1)?.trim()
        ?: return null
    return percentDecode(value, Charsets.UTF_8) ?: value
}

private fun urlFileName(url: String): String? {
    val segment = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: return null
    val name = segment.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "")
    return name.takeIf { extension.isNotEmpty() && name.length > extension.length + 1 }
}

/**
 * 严格的百分号解码：`+` 不当空格（那是表单编码的规矩，文件名里的 `+` 就是 `+`），
 * `%` 后面不是两位十六进制、或者解出来不是合法的 [charset] 编码，就当它本来没编码过，返回 null。
 */
private fun percentDecode(value: String, charset: Charset): String? {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        if (value[i] == '%') {
            val hi = value.getOrNull(i + 1)?.let { Character.digit(it, 16) } ?: -1
            val lo = value.getOrNull(i + 2)?.let { Character.digit(it, 16) } ?: -1
            if (hi < 0 || lo < 0) return null
            bytes.write(hi * 16 + lo)
            i += 3
        } else {
            val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
            bytes.write(value.substring(i, end).toByteArray(charset))
            i = end
        }
    }
    return runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }.getOrNull()
}

/** 换掉文件系统不接受的字符与控制字符，去掉开头的点（不然成了隐藏文件），过长时截短但保住扩展名。 */
private fun sanitizeFileName(name: String): String? {
    val cleaned = name.replace(UNSAFE_NAME_CHARS, "_").trim().trimStart('.').trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.length <= MAX_FILE_NAME) return cleaned
    val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..16 }
        ?: return cleaned.take(MAX_FILE_NAME)
    return cleaned.take(MAX_FILE_NAME - extension.length - 1).trimEnd() + "." + extension
}

/**
 * 网页里 `<input type=file>` 要选文件时拉起的系统选择器：按 accept 限定类型，`multiple` 时允许多选。
 * `capture`（要求直接拍照）不单独处理，选择器里照样能从相册、文件里挑 —— 这样用不着申请相机权限。
 */
internal fun fileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
    val types = acceptedMimeTypes(params?.acceptTypes.orEmpty().toList()) { extension ->
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
    return Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = types.singleOrNull() ?: "*/*"
        if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
    }
}

/**
 * accept 里的每一项换成 MIME：MIME 类型（包括 `image/` 开头的通配）原样保留，`.pdf` 这类扩展名查表。
 * 没写 accept、或者有一项是 `*` / 认不出来的扩展名，就等于什么文件都能选，返回空表。
 */
internal fun acceptedMimeTypes(accept: List<String>, mimeForExtension: (String) -> String?): List<String> {
    val items = accept.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val types = items.map { item ->
        when {
            item.startsWith('.') -> mimeForExtension(item.drop(1)) ?: return emptyList()
            item == "*/*" || '/' !in item -> return emptyList()
            else -> item
        }
    }
    return types.distinct()
}

/** 选择器的结果：多选时在 ClipData 里，单选时在 data 里；取消或什么都没选是 null（页面据此知道这次取消了）。 */
internal fun chosenFiles(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val clip = data.clipData
    val uris = if (clip != null && clip.itemCount > 0) {
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    } else {
        listOfNotNull(data.data)
    }
    return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
}
