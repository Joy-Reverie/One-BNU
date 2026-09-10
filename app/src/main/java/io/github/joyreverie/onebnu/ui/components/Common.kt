package io.github.joyreverie.onebnu.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalDarkTheme
import io.github.joyreverie.onebnu.ui.theme.Shape

/**
 * 应用统一的卡片：白面 + 极淡描边 + 很轻的投影。
 * 不用 Material 默认的 tonal elevation —— 那会把卡片染成灰蓝色，
 * 多张叠在一起显脏；这里用「描边 + 微阴影」做层次更干净。
 */
@Composable
fun BnuCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Shape.card),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .shadowSoft(shape)
            .clip(shape),
        color = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalAccents.current.hairline),
        shape = shape,
    ) { content() }
}

/** 统一的轻投影，暗色下自动减弱（暗背景上强阴影会变成黑块）。 */
@Composable
fun Modifier.shadowSoft(shape: RoundedCornerShape, elevation: Dp = 2.dp): Modifier {
    val dark = LocalDarkTheme.current
    return this.then(
        shadow(
            elevation = if (dark) 0.dp else elevation,
            shape = shape,
            ambientColor = Color(0x1A1B3C6E),
            spotColor = Color(0x141B3C6E),
        ),
    )
}

/** 骨架屏方块：比转圈更能表达「内容马上就来」，也不会让页面高度跳动。 */
@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(10.dp)) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = a)),
    )
}

/** 列表骨架，给成绩 / 考试等卡片列表用。 */
@Composable
fun ListSkeleton(rows: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) {
            BnuCard {
                Column(Modifier.padding(16.dp)) {
                    Shimmer(Modifier.fillMaxWidth(0.55f).height(16.dp))
                    Spacer(Modifier.height(10.dp))
                    Shimmer(Modifier.fillMaxWidth(0.8f).height(12.dp))
                }
            }
        }
    }
}

@Composable
fun LoadingBox(text: String = "加载中…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth()) {
            ListSkeleton(rows = 4)
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 「查得到但没有数据」的状态 —— 与错误明确区分：
 * 新生没成绩、学期没发布考试都属正常，用中性的圆形插画而不是报错样式。
 */
@Composable
fun EmptyBox(text: String, hint: String? = null, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBox(
        modifier = modifier,
        icon = { size ->
            Icon(
                Icons.Outlined.Inbox, null,
                Modifier.size(size),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        tint = MaterialTheme.colorScheme.primaryContainer,
        title = text,
        hint = hint,
        action = onRetry?.let { { OutlinedButton(onClick = it) { Text("重新查询") } } },
    )
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBox(
        modifier = modifier,
        icon = { size ->
            Icon(
                Icons.Outlined.CloudOff, null,
                Modifier.size(size),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        tint = MaterialTheme.colorScheme.errorContainer,
        title = message,
        hint = null,
        action = onRetry?.let { { Button(onClick = it) { Text("重试") } } },
    )
}

@Composable
private fun StateBox(
    modifier: Modifier,
    icon: @Composable (Dp) -> Unit,
    tint: Color,
    title: String,
    hint: String?,
    action: (@Composable () -> Unit)?,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.35f))),
                    ),
                contentAlignment = Alignment.Center,
            ) { icon(34.dp) }
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(22.dp))
                action()
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BnuCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    trailing?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** 章节小标题，带一道短强调线，比裸文字有层次。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 键值对一行，用于学籍信息、课程详情等。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            label,
            Modifier.width(84.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
