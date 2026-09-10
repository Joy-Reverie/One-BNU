package io.github.joyreverie.onebnu.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.update.AutoUpdate
import io.github.joyreverie.onebnu.core.update.ReleaseInfo
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.core.update.UpdateInstaller

/**
 * 挂在界面根部：启动时按 [AutoUpdate] 的条件联网查一次，有新版本就弹 [UpdateDialog]。
 * 自身不占布局，对话框浮在当前任何页面之上。
 */
@Composable
fun AutoUpdatePrompt(checker: UpdateChecker = remember { UpdateChecker() }) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(checker) {
        release = AutoUpdate.findUpdate(context, ServiceLocator.settings.autoCheckUpdates, checker)
    }

    release?.let { r ->
        UpdateDialog(
            release = r,
            onDownload = {
                release = null
                startUpdateDownload(context, r)
            },
            onBrowser = {
                release = null
                UpdateInstaller.openInBrowser(context, r.pageUrl.ifBlank { checker.releasesPage })
            },
            onDismiss = {
                release = null
                AutoUpdate.dismiss(context, r.version)
            },
        )
    }
}
