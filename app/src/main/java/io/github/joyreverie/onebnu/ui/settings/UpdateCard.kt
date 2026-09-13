package io.github.joyreverie.onebnu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.update.ReleaseInfo
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.core.update.UpdateInstaller
import io.github.joyreverie.onebnu.core.update.UpdateInstaller.DownloadState
import io.github.joyreverie.onebnu.core.update.UpdateResult
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.ui.update.UpdateDialog
import io.github.joyreverie.onebnu.ui.update.startUpdateDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「版本」卡：一行显示当前版本，点一下向 GitHub Releases 查最新版本；
 * 有新版本时弹出发布说明，可直接下载安装或到浏览器打开发布页；
 * 下载进度与「已下载、待安装」的状态也落在这一行上，进程重启后照常恢复。
 * 下面一行开关控制启动时的自动检查。
 */
@Composable
fun UpdateCard(currentVersion: String, checker: UpdateChecker) {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ReleaseInfo?>(null) }
    var download by remember { mutableStateOf(UpdateInstaller.state(context)) }
    var autoCheck by remember { mutableStateOf(settings.autoCheckUpdates) }

    // 从系统安装器或后台回到这一页时重读下载状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) download = UpdateInstaller.state(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 下载进行中每秒刷一次进度
    LaunchedEffect(download is DownloadState.Running) {
        while (download is DownloadState.Running) {
            delay(1000)
            download = UpdateInstaller.state(context)
        }
    }

    fun check() {
        if (checking) return
        checking = true
        status = null
        scope.launch {
            when (val r = checker.check()) {
                is UpdateResult.Available -> pending = r.release
                is UpdateResult.UpToDate -> status = "已是最新版本"
                is UpdateResult.Failed -> status = "检查失败：${r.message}"
            }
            checking = false
        }
    }

    val (title, subtitle) = when (val d = download) {
        is DownloadState.Running -> "正在下载 v${d.version}" to (d.progress?.let { "${(it * 100).toInt()}%" } ?: "等待中")
        is DownloadState.Ready -> "安装 v${d.version}" to "已下载完成，点击安装"
        DownloadState.None -> "检查更新" to (status ?: "当前版本 $currentVersion")
    }

    SectionCard("版本") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    when (val d = download) {
                        is DownloadState.Ready -> UpdateInstaller.install(context, d.file)
                        is DownloadState.Running -> Unit
                        DownloadState.None -> check()
                    }
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            when {
                checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                download !is DownloadState.None -> TextButton(onClick = {
                    UpdateInstaller.cancel(context)
                    download = UpdateInstaller.state(context)
                }) { Text(if (download is DownloadState.Running) "取消" else "放弃") }
                else -> Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.outline)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动检查更新", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    settings.autoCheckUpdates = it
                },
            )
        }
    }

    pending?.let { release ->
        UpdateDialog(
            release = release,
            onDownload = {
                pending = null
                if (startUpdateDownload(context, release)) download = UpdateInstaller.state(context)
            },
            onBrowser = {
                pending = null
                UpdateInstaller.openInBrowser(context, release.pageUrl.ifBlank { checker.releasesPage })
            },
            onDismiss = { pending = null },
        )
    }
}
