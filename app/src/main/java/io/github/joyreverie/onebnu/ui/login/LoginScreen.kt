package io.github.joyreverie.onebnu.ui.login

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.joyreverie.onebnu.ui.theme.GlowBlob

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onDiagnostics: () -> Unit = {},
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val screen = LocalScreenInfo.current

    var autoTried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoTried && vm.canAutoLogin()) {
            autoTried = true
            vm.login(onLoggedIn)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()

        val content: @Composable () -> Unit = {
            LoginCard(state, vm, onLoggedIn, Modifier.widthIn(max = 400.dp))
        }

        // 横屏（高度紧张）左右分栏：左边品牌，右边表单，
        // 竖排会让表单被键盘挤到看不见按钮。
        if (screen.isShort && screen.widthDp >= 600) {
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Brand(compact = false)
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        content()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "登不上？点此做网络诊断",
                            Modifier.clickable(onClick = onDiagnostics).padding(6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // safeDrawing 已含 IME，不能再叠 imePadding，否则键盘弹出时双倍留白
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (screen.isShort) 20.dp else 64.dp))
                Brand(compact = screen.isShort)
                Spacer(Modifier.height(if (screen.isShort) 20.dp else 36.dp))
                content()
                Spacer(Modifier.height(28.dp))
                Footer(onDiagnostics)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** 背景：两团高斯模糊的品牌色光斑，比纯色底更有空间感，也不抢前景。 */
@Composable
private fun AuroraBackground() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.background)) {
        GlowBlob(
            cs.primary,
            Modifier.size(420.dp).offset(x = (-140).dp, y = (-160).dp),
            alpha = 0.26f,
        )
        GlowBlob(
            cs.secondary,
            Modifier.align(Alignment.TopEnd).size(360.dp).offset(x = 120.dp, y = (-40).dp),
            alpha = 0.22f,
        )
        GlowBlob(
            cs.tertiary,
            Modifier.align(Alignment.BottomStart).size(400.dp).offset(x = (-120).dp, y = 150.dp),
            alpha = 0.16f,
        )
    }
}

@Composable
private fun Brand(compact: Boolean) {
    val accents = LocalAccents.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 启动器图标里的箭头标志，底色与图标背景相同（白到极淡的蓝），
        // 圆角方形按桌面图标的比例来，深色主题下也保持图标原貌。
        Box(
            Modifier
                .size(if (compact) 76.dp else 100.dp)
                .clip(RoundedCornerShape(if (compact) 20.dp else 26.dp))
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFE9F0FB)))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.login_mark),
                contentDescription = "One BNU",
                modifier = Modifier.size(if (compact) 50.dp else 66.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        // 字标与启动器图标保持一致：中间留空格，「One」浅蓝、「BNU」深蓝。
        // 不再用整体渐变 —— 图标里这两半是两个明确的色阶，渐变会把这层对比冲淡。
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = accents.wordmarkOne)) { append("One ") }
                withStyle(SpanStyle(color = accents.wordmarkBnu)) { append("BNU") }
            },
            style = (
                if (compact) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.displaySmall
                ).copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = if (compact) 1.5.sp else 2.5.sp,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "北京师范大学校园助手",
            style = MaterialTheme.typography.bodyMedium,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginCard(
    state: LoginUiState,
    vm: LoginViewModel,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    var showPassword by remember { mutableStateOf(false) }
    val submit = { focus.clearFocus(); vm.login(onLoggedIn) }

    state.secondAuth?.let {
        SecondAuthDialog(
            state = it,
            onCode = vm::onSmsCode,
            onSend = vm::sendSmsCode,
            onSubmit = { focus.clearFocus(); vm.submitSmsCode(onLoggedIn) },
            onDismiss = vm::cancelSecondAuth,
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Shape.cardLarge),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalAccents.current.hairline),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("登录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                "使用数字京师统一身份认证",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUsername,
                label = { Text("学工号") },
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                singleLine = true,
                shape = RoundedCornerShape(Shape.field),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPassword,
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (showPassword) "隐藏密码" else "显示密码",
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Shape.field),
                colors = fieldColors(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.captchaUrl != null) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(
                visible = state.captchaUrl != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.captcha,
                            onValueChange = vm::onCaptcha,
                            label = { Text("验证码") },
                            singleLine = true,
                            shape = RoundedCornerShape(Shape.field),
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        CaptchaImage(
                            url = state.captchaUrl.orEmpty(),
                            onRefresh = vm::refreshCaptcha,
                            modifier = Modifier.size(width = 108.dp, height = 56.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.remember,
                    onCheckedChange = vm::onRemember,
                    enabled = state.canSaveCredentials,
                )
                Text(
                    if (state.canSaveCredentials) "记住密码" else "本设备安全存储不可用",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.canSaveCredentials) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                )
            }

            AnimatedVisibility(visible = state.error != null) {
                Text(
                    state.error.orEmpty(),
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))

            GradientButton(
                text = "登 录",
                loading = state.loading,
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 短信二次认证。
 *
 * 服务端在不认识这台设备时会要求短信验证，网页端弹的就是同一个流程
 * （`secondAuth` 的 send / login 两步）。以前应用到这里就断了，只能让用户
 * 先去浏览器登一次；现在直接在应用内做完。
 */
@Composable
private fun SecondAuthDialog(
    state: SecondAuthState,
    onCode: (String) -> Unit,
    onSend: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.submitting && !state.sending) onDismiss() },
        title = { Text("短信验证") },
        text = {
            Column {
                Text(
                    "该账号在这台设备上首次登录，需要短信验证。" +
                        "验证码将发送到 ${state.maskedPhone}。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCode,
                        label = { Text("验证码") },
                        singleLine = true,
                        shape = RoundedCornerShape(Shape.field),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onSubmit() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onSend,
                        enabled = state.canSend,
                        shape = RoundedCornerShape(Shape.field),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (state.secondsLeft > 0) "${state.secondsLeft}s" else "获取验证码",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                val notice = state.error ?: state.notice
                AnimatedVisibility(visible = notice != null) {
                    Text(
                        notice.orEmpty(),
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = state.canSubmit) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("验证并登录")
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                enabled = !state.submitting && !state.sending,
            ) { Text("取消") }
        },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

/** 渐变主按钮：纯色按钮在这个页面上太平，渐变能把品牌色带下来。 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val accents = LocalAccents.current
    val shape = RoundedCornerShape(Shape.field)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) accents.heroGradient else Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant),
            ))
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Footer(onDiagnostics: () -> Unit) {
    Text(
        "登不上？点此做网络诊断",
        Modifier.clickable(onClick = onDiagnostics).padding(6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

/**
 * 验证码图片必须带 CAS 会话 Cookie 才能取到与本次登录匹配的那张，
 * 所以走应用自己的 OkHttp 客户端，而不是通用图片加载器。
 */
@Composable
private fun CaptchaImage(url: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url)
                    .header("Referer", "https://cas.bnu.edu.cn/cas/login")
                    .build()
                ServiceLocator.http.client.newCall(req).execute().use { res ->
                    val bytes = res.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(Shape.chip))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onRefresh() },
        contentAlignment = Alignment.Center,
    ) {
        val bm = bitmap
        if (bm != null) {
            Image(bm, "验证码，点击刷新", Modifier.fillMaxSize())
        } else {
            Text("点击刷新", style = MaterialTheme.typography.bodySmall)
        }
    }
}
