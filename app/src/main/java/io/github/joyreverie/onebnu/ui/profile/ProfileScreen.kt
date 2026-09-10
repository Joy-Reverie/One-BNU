package io.github.joyreverie.onebnu.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import io.github.joyreverie.onebnu.ui.Routes
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.listPadding
import io.github.joyreverie.onebnu.ui.theme.glow

@Composable
fun ProfileScreen(
    nav: NavHostController,
    onSignedOut: () -> Unit,
    vm: ProfileViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var showSignOut by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = LocalScreenInfo.current.listPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { IdentityCard(state, onRetry = vm::retry) }

        item { ReminderCard() }
        item { WidgetPinCard() }

        item {
            MenuGroup {
                MenuItem(Icons.Outlined.Badge, "学籍信息") { nav.navigate(Routes.STUDENT_INFO) }
                MenuItem(Icons.Outlined.School, "学分核算") { nav.navigate(Routes.CREDITS) }
                MenuItem(Icons.Outlined.Settings, "设置") { nav.navigate(Routes.SETTINGS) }
                MenuItem(Icons.Outlined.Info, "关于与支持") { nav.navigate(Routes.INFO) }
            }
        }

        item {
            MenuGroup {
                MenuItem(Icons.AutoMirrored.Outlined.Logout, "退出登录", danger = true) {
                    showSignOut = true
                }
            }
        }

        item {
            Text(
                "One BNU",
                Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showSignOut) {
        var forget by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            title = { Text("退出登录") },
            text = {
                Column {
                    Text("将清除本次会话。")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = forget, onCheckedChange = { forget = it })
                        Text("同时删除已保存的账号密码", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOut = false
                    ServiceLocator.signOut(forgetCredentials = forget)
                    onSignedOut()
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showSignOut = false }) { Text("取消") } },
        )
    }
}

/** 身份卡按会话状态分三态渲染，不再出现「还在加载却显示未登录」。 */
@Composable
private fun IdentityCard(state: SessionRepository.State, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.cardLarge))
            .background(LocalAccents.current.heroGradient)
            .glow(Color.White, alpha = 0.20f, cx = 0.9f, cy = 0.0f, radius = 0.8f),
    ) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    is SessionRepository.State.Ready ->
                        Text(
                            state.profile.initial,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                    SessionRepository.State.Loading, SessionRepository.State.Idle ->
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    is SessionRepository.State.Failed ->
                        Text("!", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                when (state) {
                    is SessionRepository.State.Ready -> {
                        val p = state.profile
                        Text(p.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.studentId,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        if (p.summary.isNotBlank()) {
                            Text(
                                p.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                        val tags = listOfNotNull(
                            p.grade.takeIf { it.isNotBlank() }?.let { "$it 级" },
                            p.level.takeIf { it.isNotBlank() },
                            p.gender.takeIf { it.isNotBlank() },
                        )
                        if (tags.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tags.forEach { t ->
                                    Surface(
                                        color = Color.White.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            t,
                                            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    SessionRepository.State.Idle, SessionRepository.State.Loading ->
                        Text(
                            "正在读取身份信息…",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    is SessionRepository.State.Failed -> {
                        Text(
                            if (state.needLogin) "登录状态已失效" else "读取失败",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (state is SessionRepository.State.Failed) {
                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = CircleShape,
                    modifier = Modifier.clickable(onClick = onRetry),
                ) {
                    Icon(
                        Icons.Outlined.Refresh, "重试",
                        Modifier.padding(8.dp).size(20.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (!danger) {
            Icon(
                Icons.Filled.ChevronRight, null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
