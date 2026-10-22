package io.github.joyreverie.onebnu.ui.update

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.update.ReleaseInfo
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.core.update.UpdateInstaller
import io.github.joyreverie.onebnu.core.update.UpdateInstaller.DownloadState
import io.github.joyreverie.onebnu.core.update.UpdateResult
import kotlinx.coroutines.launch

/**
 * 登录页上的「检查更新」：没登录也进不了设置页，而认证接口一变旧版本就登不上，
 * 只靠启动时的自动检查不够（可能被关掉、被「以后再说」或六小时节流挡住）。
 * 与设置页一样不受这些限制，结果用 Toast 说一句，有新版本时弹 [UpdateDialog]。
 */
@Composable
fun ManualUpdateLink(modifier: Modifier = Modifier, checker: UpdateChecker = remember { UpdateChecker() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ReleaseInfo?>(null) }

    fun onClick() {
        when (val d = UpdateInstaller.state(context)) {
            is DownloadState.Ready -> UpdateInstaller.install(context, d.file)
            is DownloadState.Running ->
                Toast.makeText(context, "v${d.version} 正在下载", Toast.LENGTH_SHORT).show()
            DownloadState.None -> {
                if (checking) return
                checking = true
                scope.launch {
                    when (val r = checker.check()) {
                        is UpdateResult.Available -> pending = r.release
                        is UpdateResult.UpToDate ->
                            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                        is UpdateResult.Failed ->
                            Toast.makeText(context, "检查失败：${r.message}", Toast.LENGTH_LONG).show()
                    }
                    checking = false
                }
            }
        }
    }

    Text(
        if (checking) "正在检查…" else "检查更新",
        modifier.clickable(onClick = ::onClick).padding(6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )

    pending?.let { release ->
        UpdateDialog(
            release = release,
            onDownload = {
                pending = null
                startUpdateDownload(context, release)
            },
            onBrowser = {
                pending = null
                UpdateInstaller.openInBrowser(context, release.pageUrl.ifBlank { checker.releasesPage })
            },
            onDismiss = { pending = null },
        )
    }
}
