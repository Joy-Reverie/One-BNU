package io.github.joyreverie.onebnu.ui.update

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.update.ReleaseInfo
import io.github.joyreverie.onebnu.core.update.UpdateInstaller

/** 更新对话框里的来源提示。 */
const val DOWNLOAD_SOURCE_NOTICE = "安装包由 GitHub 提供，下载前请注意网络环境。"

/**
 * 「发现新版本」对话框：日期与大小、来源提示、发布说明；
 * 可直接下载安装、到浏览器下载，或以后再说。设置页的手动检查与启动时的自动检查共用。
 */
@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    onDownload: () -> Unit,
    onBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val meta = listOfNotNull(
        release.publishedAt.takeIf { it.isNotBlank() },
        release.apkSize.takeIf { it > 0 }?.let(::formatSize),
    ).joinToString(" · ")
    val notes = release.plainNotes()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${release.tag}") },
        text = {
            Column {
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    DOWNLOAD_SOURCE_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    notes.ifBlank { "这次发布没有附带更新说明。" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
                if (release.apkUrl == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这个版本没有附带安装包，请到发布页下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (release.apkUrl != null) TextButton(onClick = onDownload) { Text("下载安装") }
            else TextButton(onClick = onBrowser) { Text("打开发布页") }
        },
        dismissButton = {
            Row {
                if (release.apkUrl != null) TextButton(onClick = onBrowser) { Text("浏览器下载") }
                TextButton(onClick = onDismiss) { Text("以后再说") }
            }
        },
    )
}

/** 开始应用内下载并提示；系统下载服务不可用时退回浏览器。返回是否走了应用内下载。 */
fun startUpdateDownload(context: Context, release: ReleaseInfo): Boolean =
    if (UpdateInstaller.download(context, release)) {
        Toast.makeText(context, "开始从 GitHub 下载，请注意网络环境；完成后会提示安装", Toast.LENGTH_LONG).show()
        true
    } else {
        UpdateInstaller.openInBrowser(context, release.apkUrl ?: release.pageUrl)
        false
    }

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
