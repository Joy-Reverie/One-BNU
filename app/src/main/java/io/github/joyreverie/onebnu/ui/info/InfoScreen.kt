package io.github.joyreverie.onebnu.ui.info

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

const val CONTACT_EMAIL = "joyreverie27@gmail.com"
const val REPO_URL = "https://github.com/${BuildConfig.GITHUB_REPO}"

/**
 * 打开一个外部 Intent；设备上没有邮件或浏览器应用时不能让应用直接崩掉。
 * 打不开就把地址复制到剪贴板并提示一句，用户还是拿得到信息。
 */
private fun openExternally(context: android.content.Context, intent: Intent, fallbackText: String, label: String) {
    if (runCatching { context.startActivity(intent) }.isSuccess) return
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, fallbackText))
    android.widget.Toast
        .makeText(context, "没有可用的应用，已复制 $fallbackText", android.widget.Toast.LENGTH_LONG)
        .show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Int?>(null) }   // 打赏图资源 id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于与支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = LocalScreenInfo.current.listPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AboutCard() }

            item { OpenSourceCard() }

            item {
                SectionCard("联系开发者") {
                    Column(Modifier.padding(top = 2.dp)) {
                        Text(
                            "遇到软件使用问题或者有更好的建议，欢迎来信",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    openExternally(
                                        context,
                                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
                                            .putExtra(Intent.EXTRA_SUBJECT, "One BNU 反馈"),
                                        CONTACT_EMAIL,
                                        "邮箱",
                                    )
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.MailOutline, null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                CONTACT_EMAIL,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward, null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard("打赏（自愿）") {
                    Text(
                        "如果你觉得 One BNU 对你有帮助，可以扫码赞助开发者。完全自愿。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    SponsorGrid(onTap = { preview = it })
                }
            }
        }
    }

    preview?.let { res ->
        QrPreviewDialog(resource = res, onDismiss = { preview = null })
    }
}

@Composable
private fun AboutCard() {
    SectionCard("关于 One BNU") {
        Column(Modifier.padding(top = 2.dp)) {
            // 版本号从 BuildConfig 取，避免和 build.gradle.kts 里的 versionName 漂移
            Text(
                "北京师范大学校园助手 · ${ServiceLocator.activeCampus.label}，版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "使用数字京师统一身份认证登录，直接对接学校官方系统，不设中间服务器，账号与数据不经过任何第三方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 源代码入口：GitHub 仓库（问题反馈、贡献代码也在那里）。 */
@Composable
private fun OpenSourceCard() {
    val context = LocalContext.current
    SectionCard("开源") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    openExternally(context, Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)), REPO_URL, "仓库地址")
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "源代码与问题反馈",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "github.com/${BuildConfig.GITHUB_REPO} · GPL-3.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward, null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 微信 / 支付宝两枚收款码并排；用各自品牌色做描边以便一眼区分。 */
@Composable
private fun SponsorGrid(onTap: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SponsorTile(
            label = "微信支付",
            accent = Color(0xFF07C160),
            resource = R.drawable.sponsor_wechat,
            modifier = Modifier.weight(1f),
            onTap = { onTap(R.drawable.sponsor_wechat) },
        )
        SponsorTile(
            label = "支付宝",
            accent = Color(0xFF1677FF),
            resource = R.drawable.sponsor_alipay,
            modifier = Modifier.weight(1f),
            onTap = { onTap(R.drawable.sponsor_alipay) },
        )
    }
}

@Composable
private fun SponsorTile(
    label: String,
    accent: Color,
    resource: Int,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTap),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(resource),
                    contentDescription = "$label 收款码，点击放大",
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
    }
}

/** 全屏展示单张收款码，便于近距离扫描识别。 */
@Composable
private fun QrPreviewDialog(resource: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(resource),
                    contentDescription = "收款码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "用对应 App「扫一扫」识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                )
            }
        }
    }
}
