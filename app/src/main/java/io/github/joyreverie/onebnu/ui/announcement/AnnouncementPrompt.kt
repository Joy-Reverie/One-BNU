package io.github.joyreverie.onebnu.ui.announcement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.store.AnnouncementStore
import kotlinx.coroutines.delay

internal data class AnnouncementItem(val number: String, val text: String, val important: Boolean = false)
internal data class AnnouncementRecord(val date: String, val title: String, val items: List<AnnouncementItem>)

internal val CURRENT_ANNOUNCEMENT_ITEMS = listOf(
    AnnouncementItem(
        "1",
        "登录或数据同步异常时，请留意 **GitHub 发布页**，或前往“设置 - 版本”检查更新，并及时升级至最新版本。首次同步数据建议优先使用**校园网**；同步完成后，数据会缓存到**本地**，后续可使用流量或在**无网络**环境下查看已同步内容。",
    ),
    AnnouncementItem(
        "2",
        "如需使用桌面课表，请按应用内引导操作。若无法添加，可在桌面长按 One BNU，进入“应用设置 - 权限管理”，找到并允许**“桌面快捷方式”权限**。",
    ),
    AnnouncementItem(
        "3",
        "如需开启上课提醒或日程提醒，请按应用内引导授予**通知权限**；也可在桌面长按 One BNU，进入“应用设置 - 通知管理”并开启通知。在下方**“通知类别”**中，可按需求设置铃声、震动、悬浮通知及锁屏显示等选项。",
    ),
    AnnouncementItem(
        "4",
        "为降低维护成本并减少安全风险，One BNU **不设自有服务器**。请主动关注 GitHub 或“设置 - 版本”中的更新，以获得更稳定的功能与最新数据，例如新学期校历等。",
    ),
    AnnouncementItem(
        "!",
        "请认准**可信来源**，最可靠的是 **GitHub 仓库**：https://github.com/Joy-Reverie/One-BNU/releases。其他来源安装包存在可能使**个人信息遭到泄露**！",
        important = true,
    ),
)

internal val ANNOUNCEMENT_HISTORY = listOf(
    AnnouncementRecord("2026年9月13日", "重要公告", CURRENT_ANNOUNCEMENT_ITEMS),
)

/** 启动公告：只在当前版本尚未确认时显示，放在根层确保登录页和主界面都能看到。 */
@Composable
fun AnnouncementPrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var visible by rememberSaveable { mutableStateOf(AnnouncementStore.shouldShow(context)) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var welcome by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(welcome) {
        if (welcome) {
            delay(1800)
            welcome = false
        }
    }

    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            icon = {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Outlined.Campaign,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
            title = {
                Text(
                    if (confirming) "请确认" else "重要公告",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                if (confirming) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "真的看完了嘛？∑(❍ฺд❍ฺlll)",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "公告会在新版本再次提醒你。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .height(390.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "请花一点时间读完，后续使用会更顺利。历史公告可在“设置 → 公告”中查看。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CURRENT_ANNOUNCEMENT_ITEMS.forEach { AnnouncementRow(it) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirming) {
                        AnnouncementStore.markRead(context)
                        visible = false
                        confirming = false
                        welcome = true
                    } else {
                        confirming = true
                    }
                }) {
                    Text(if (confirming) "我真的看完了" else "我已阅读")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (confirming) confirming = false else visible = false
                }) {
                    Text(if (confirming) "再看一遍" else "下次再看")
                }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedVisibility(
            visible = welcome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Surface(
                modifier = Modifier.padding(top = 18.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                tonalElevation = 5.dp,
            ) {
                Text(
                    "欢迎使用^⎚‸⎚^",
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
internal fun AnnouncementRow(item: AnnouncementItem) {
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                color = if (item.important) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        item.number,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.important) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(
                buildAnnotatedString {
                    var cursor = 0
                    val matches = Regex("\\*\\*(.+?)\\*\\*").findAll(item.text)
                    matches.forEach { match ->
                        append(item.text.substring(cursor, match.range.first))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                        cursor = match.range.last + 1
                    }
                    append(item.text.substring(cursor))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.important) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (item.important) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
        ) {
            Box(Modifier.padding(10.dp)) { content() }
        }
    } else {
        content()
    }
}
