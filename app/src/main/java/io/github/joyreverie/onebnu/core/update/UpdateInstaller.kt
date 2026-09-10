package io.github.joyreverie.onebnu.core.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.github.joyreverie.onebnu.AppVisibility
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.notify.ClassReminder
import java.io.File

/**
 * 下载 GitHub Release 附带的 APK 并拉起系统安装器。
 *
 * 传输交给系统的 DownloadManager（自带进度通知与断点续传），文件落在应用私有的外部目录
 * `Android/data/<包名>/files/Download/`，不需要存储权限；安装时经 FileProvider 把文件授权给
 * 安装器读取。系统安装时会校验新包签名与已装版本一致，签名不符的包装不上去。
 *
 * 同一时刻只跟踪一个下载任务，任务 id、文件名与版本号记在 SharedPreferences 里，
 * 供 [UpdateDownloadReceiver] 与设置页在进程重启后继续识别。
 */
object UpdateInstaller {

    const val CHANNEL_ID = "app_update"
    private const val PREFS = "onebnu_update"
    private const val KEY_ID = "download_id"
    private const val KEY_FILE = "file_name"
    private const val KEY_VERSION = "version"
    private const val NOTIFICATION_ID = 4001
    private const val REQUEST_INSTALL = 4002
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed class DownloadState {
        object None : DownloadState()
        /** 进行中；[progress] 为 0～1，总大小未知时为 null。 */
        data class Running(val version: String, val progress: Float?) : DownloadState()
        /** 已下载完成，等待安装。 */
        data class Ready(val version: String, val file: File) : DownloadState()
    }

    /** 开始下载。系统下载服务被停用或外部存储不可写时返回 false，调用方应退回浏览器。 */
    fun download(context: Context, release: ReleaseInfo): Boolean {
        val url = release.apkUrl ?: return false
        val dm = context.getSystemService(DownloadManager::class.java) ?: return false
        val fileName = release.apkName?.takeIf { it.isNotBlank() } ?: "One-BNU-${release.version}.apk"

        // 上一个任务连同文件一起清掉，否则 DownloadManager 会给同名文件加 -1 后缀，之后找不到
        cancel(context)
        targetFile(context, fileName)?.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("One BNU ${release.tag}")
            .setDescription("正在下载更新")
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            // 自己能发通知时只让系统显示进度，完成由本应用提示；发不了通知时让系统的「下载完成」兜底
            .setNotificationVisibility(
                if (ClassReminder.notificationsAllowed(context)) DownloadManager.Request.VISIBILITY_VISIBLE
                else DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
        val id = runCatching {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            dm.enqueue(request)
        }.getOrNull() ?: return false

        prefs(context).edit()
            .putLong(KEY_ID, id)
            .putString(KEY_FILE, fileName)
            .putString(KEY_VERSION, release.version)
            .apply()
        return true
    }

    /** 当前跟踪的下载任务处于什么状态；失败或记录失效时顺手清理。 */
    fun state(context: Context): DownloadState {
        val p = prefs(context)
        val id = p.getLong(KEY_ID, -1L)
        if (id < 0) return DownloadState.None
        val version = p.getString(KEY_VERSION, null).orEmpty()
        val file = p.getString(KEY_FILE, null)?.let { targetFile(context, it) }

        // 记录里的版本不高于当前运行的版本，说明已经装上了（或装了更新的）：
        // 遗留的下载记录、通知与安装包都没有意义，顺手清掉，不再提示「点击安装」
        if (version.isBlank() || UpdateChecker.compare(version, BuildConfig.VERSION_NAME) <= 0) {
            cancel(context)
            file?.delete()
            return DownloadState.None
        }

        val dm = context.getSystemService(DownloadManager::class.java) ?: return DownloadState.None

        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
        cursor?.use { c ->
            if (!c.moveToFirst()) {
                clear(context)
                return DownloadState.None
            }
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (file != null && file.exists()) DownloadState.Ready(version, file)
                    else { clear(context); DownloadState.None }
                DownloadManager.STATUS_FAILED -> { clear(context); DownloadState.None }
                else -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    DownloadState.Running(version, if (total > 0) (soFar.toFloat() / total).coerceIn(0f, 1f) else null)
                }
            }
        }
        clear(context)
        return DownloadState.None
    }

    /** 取消进行中的任务或丢弃已下载的包。 */
    fun cancel(context: Context) {
        val id = prefs(context).getLong(KEY_ID, -1L)
        if (id >= 0) {
            context.getSystemService(DownloadManager::class.java)?.let { dm -> runCatching { dm.remove(id) } }
        }
        clear(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun install(context: Context, file: File) {
        runCatching { context.startActivity(installIntent(context, file)) }
            .onFailure { Toast.makeText(context, "无法打开安装器", Toast.LENGTH_SHORT).show() }
    }

    fun openInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
    }

    /** DownloadManager 报告任务结束。只处理自己登记过的那个 id。 */
    internal fun onDownloadComplete(context: Context, id: Long) {
        if (id != prefs(context).getLong(KEY_ID, -1L)) return
        when (val s = state(context)) {
            is DownloadState.Ready ->
                // 应用在前台就直接弹安装器；在后台时 Android 10 起不允许拉起界面，改发通知
                if (AppVisibility.foreground) install(context, s.file) else notifyReady(context, s.version, s.file)
            DownloadState.None ->
                Toast.makeText(context, "更新包下载失败，可到发布页手动下载", Toast.LENGTH_LONG).show()
            is DownloadState.Running -> Unit
        }
    }

    private fun notifyReady(context: Context, version: String, file: File) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_INSTALL, installIntent(context, file),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("One BNU v$version 已下载")
            .setContentText("点击安装")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "新版本下载完成后提示安装"
                setShowBadge(false)
            },
        )
    }

    private fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun targetFile(context: Context, name: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
