package io.github.joyreverie.onebnu.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.net.NetworkDiagnostics
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.listPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络诊断。
 *
 * 「电脑上能登、手机上不能」这类问题，只靠一句「登录失败」没法定位。
 * 这里逐个检查依赖的主机，把失败卡在哪一层、原始异常是什么直接摆出来，
 * 并支持一键复制，方便反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<NetworkDiagnostics.Report?>(null) }
    var running by remember { mutableStateOf(true) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round) {
        running = true
        report = withContext(Dispatchers.IO) { ServiceLocator.diagnostics.run() }
        running = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网络诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { round++ }, enabled = !running) {
                        Icon(Icons.Filled.Refresh, "重新检查")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val r = report
            if (running && r == null) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("正在检查各项服务…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (r != null) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = LocalScreenInfo.current.listPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SummaryCard(r) }
                    items(r.checks) { CheckRow(it) }
                    item { DeviceCard() }
                    item {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { copyToClipboard(context, r.asText()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制诊断报告")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "把报告复制出来即可定位是域名解析、连接还是协议层的问题。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设备标识。
 *
 * 认证服务靠一份 `devInfo` Cookie 认设备，不认识就要求短信二次认证。
 * 「每次登录都要短信」通常就是这份记号没留住，所以在诊断里能看到它的状态；
 * 反过来，想让服务端重新把本机当陌生设备（比如换人使用）也从这里重置。
 */
@Composable
private fun DeviceCard() {
    val device = ServiceLocator.currentDevice()
    if (device == null) {
        BnuCard(Modifier.fillMaxWidth()) {
            Text(
                "当前为珠海校区。珠海认证使用独立会话，不共享北京校区的设备标识。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var known by remember { mutableStateOf(device.serverMark != null) }
    var confirming by remember { mutableStateOf(false) }

    BnuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("本机设备标识", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (known) {
                    "认证服务已认得这台设备，登录时通常不再要求短信验证。"
                } else {
                    "认证服务还不认识这台设备，下次登录会要求一次短信验证，验证通过后即可记住。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (known) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { confirming = true }) { Text("重置设备标识") }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("重置设备标识？") },
            text = { Text("重置后下次登录需要重新做一次短信验证。换人使用本机时才需要这么做。") },
            confirmButton = {
                TextButton(onClick = {
                    ServiceLocator.auth.resetDeviceIdentity()
                    known = false
                    confirming = false
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SummaryCard(r: NetworkDiagnostics.Report) {
    val ok = r.allCriticalPassed
    Surface(
        color = if (ok) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(Shape.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                null,
                Modifier.size(24.dp),
                tint = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                r.summary,
                style = MaterialTheme.typography.titleSmall,
                color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun CheckRow(c: NetworkDiagnostics.Check) {
    BnuCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                null,
                Modifier.size(20.dp),
                tint = if (c.ok) MaterialTheme.colorScheme.primary
                else if (c.critical) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    if (!c.critical) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "非必需",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    c.target,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${c.detail}（${c.millis}ms）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("One BNU 网络诊断", text))
}
