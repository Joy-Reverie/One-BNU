package io.github.joyreverie.onebnu.core.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit

/**
 * 启动时的自动检查。
 *
 * 条件：用户没关掉这项、当前有网络、这个进程还没查过、距上次成功检查不少于 [MIN_INTERVAL_MS]。
 * 查到新版本后，用户点过「以后再说」的版本不再弹，正在下载或已下载好的版本也不再弹；
 * 手动检查（设置页）不受这些限制。
 */
object AutoUpdate {

    private const val PREFS = "onebnu_auto_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DISMISSED = "dismissed_version"

    /** 两次自动联网检查之间的最小间隔。 */
    val MIN_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

    @Volatile
    private var checkedThisProcess = false

    /** 这次启动要不要联网查。纯函数，便于测试；时钟回拨时视为已到期。 */
    fun isDue(enabled: Boolean, online: Boolean, alreadyChecked: Boolean, now: Long, lastCheck: Long): Boolean =
        enabled && online && !alreadyChecked &&
            (lastCheck <= 0L || now < lastCheck || now - lastCheck >= MIN_INTERVAL_MS)

    /** 这个新版本要不要弹给用户看。 */
    fun shouldPrompt(release: ReleaseInfo, dismissedVersion: String?, download: UpdateInstaller.DownloadState): Boolean {
        if (release.version == dismissedVersion) return false
        return when (download) {
            is UpdateInstaller.DownloadState.Running -> download.version != release.version
            is UpdateInstaller.DownloadState.Ready -> download.version != release.version
            UpdateInstaller.DownloadState.None -> true
        }
    }

    /** 满足条件时向 GitHub 查一次；有需要提示的新版本就返回，否则返回 null。 */
    suspend fun findUpdate(context: Context, enabled: Boolean, checker: UpdateChecker): ReleaseInfo? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!isDue(enabled, isOnline(context), checkedThisProcess, now, prefs.getLong(KEY_LAST_CHECK, 0L))) return null
        checkedThisProcess = true

        val result = checker.check()
        // 没连上不记时间，下次启动再试
        if (result is UpdateResult.Failed) return null
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val release = (result as? UpdateResult.Available)?.release ?: return null
        val dismissed = prefs.getString(KEY_DISMISSED, null)
        return if (shouldPrompt(release, dismissed, UpdateInstaller.state(context))) release else null
    }

    /** 用户对这个版本点了「以后再说」。 */
    fun dismiss(context: Context, version: String) {
        prefs(context).edit().putString(KEY_DISMISSED, version).apply()
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // 只看有没有网络出口，不要求系统的「联网校验」通过 —— 国内网络常年通不过那项校验
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** debug 预览入口用：清掉节流与忽略记录，让下一次 [findUpdate] 一定去查。 */
    fun resetForTesting(context: Context) {
        checkedThisProcess = false
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
