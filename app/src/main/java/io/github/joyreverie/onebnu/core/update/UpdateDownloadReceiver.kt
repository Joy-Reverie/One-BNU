package io.github.joyreverie.onebnu.core.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接 DownloadManager 的「下载完成」广播。系统只把这条广播发给发起下载的应用，
 * 但 receiver 仍需 exported 才收得到；安全性由 [UpdateInstaller.onDownloadComplete] 里
 * 「id 必须与自己登记的一致、文件路径取自本地记录而非广播内容」保证。
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id >= 0) UpdateInstaller.onDownloadComplete(context, id)
    }
}
