package io.github.joyreverie.onebnu.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/*
 * 动效小件：滚动数字、完成反馈按钮、惯性滚轮。
 * 都只做「状态变了怎么过渡」，不改变任何数据流；系统关掉动画时 Compose 的时长缩放会把它们一起压成瞬变。
 */

/**
 * 滚动数字：只有变了的那一位上下滚动，涨了从下往上、减了从上往下。
 * 位数按从右往左对齐，9 → 10 时个位滚、十位淡入，不会整串跳一下。
 */
@Composable
fun RollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    var previous by remember { mutableStateOf(value) }
    val up = value >= previous
    LaunchedEffect(value) { previous = value }
    val digits = value.toString()
    Row(modifier.clipToBounds()) {
        digits.forEachIndexed { i, ch ->
            key(digits.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(ROLL_MS, easing = FastOutSlowInEasing)) { it * dir } + fadeIn(tween(ROLL_MS))) togetherWith
                            (slideOutVertically(tween(ROLL_MS, easing = FastOutSlowInEasing)) { -it * dir } + fadeOut(tween(ROLL_MS / 2))) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { Text(it.toString(), style = style, color = color) }
            }
        }
    }
}

private const val ROLL_MS = 260

/** 轻触感：系统不支持更细的类型时退回键盘点按，关掉触感反馈的系统设置照样生效。 */
fun View.tick(confirm: Boolean = false) {
    val type = when {
        confirm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
        !confirm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    }
    performHapticFeedback(type)
}

/**
 * 完成反馈按钮：按下轻轻一压；[loading] 时收成圆形转圈，[done] 时圆里打勾、周围迸出一圈小粒子。
 * 外层不裁剪，粒子可以飞出按钮的边界。[background] 是展开态的底色，收成圆形后沿用。
 */
@Composable
fun MorphButton(
    text: String,
    onClick: () -> Unit,
    background: Brush,
    contentColor: Color,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    done: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    corner: Dp = 14.dp,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val collapsed = loading || done
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && !collapsed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val view = LocalView.current
    LaunchedEffect(done) { if (done) view.tick(confirm = true) }

    BoxWithConstraints(modifier.height(height), contentAlignment = Alignment.Center) {
        val width by animateDpAsState(
            if (collapsed) height else maxWidth,
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
            label = "width",
        )
        val radius by animateDpAsState(if (collapsed) height / 2 else corner, tween(220), label = "corner")
        if (done) Burst(Modifier.size(height))
        Box(
            Modifier
                .width(width)
                .height(height)
                .graphicsLayer { scaleX = press; scaleY = press }
                .clip(RoundedCornerShape(radius))
                .background(background)
                .clickable(
                    interactionSource = source,
                    indication = ripple(),
                    enabled = enabled && !collapsed,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = when {
                    done -> 2
                    loading -> 1
                    else -> 0
                },
                transitionSpec = {
                    (fadeIn(tween(160, delayMillis = 60)) + scaleIn(tween(240, delayMillis = 60), initialScale = 0.6f)) togetherWith
                        fadeOut(tween(90))
                },
                label = "morph",
            ) { phase ->
                when (phase) {
                    2 -> Icon(Icons.Filled.Check, "已完成", Modifier.size(height * 0.46f), tint = contentColor)
                    1 -> CircularProgressIndicator(Modifier.size(height * 0.42f), strokeWidth = 2.dp, color = contentColor)
                    else -> Text(text, style = textStyle, color = contentColor, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** 从中心迸出的一圈小粒子，颜色取课程色板；画在 [modifier] 的范围外也不会被裁掉。 */
@Composable
private fun Burst(modifier: Modifier) {
    val colors = LocalAccents.current.courseAccents
    val t = remember { Animatable(0f) }
    // 等按钮差不多收成圆了再迸，否则粒子一出来就被还没收窄的按钮盖住
    LaunchedEffect(Unit) { t.animateTo(1f, tween(560, delayMillis = 160, easing = LinearOutSlowInEasing)) }
    Box(
        modifier.drawBehind {
            val p = t.value
            if (p <= 0f || p >= 1f) return@drawBehind
            val base = size.minDimension / 2
            repeat(BURST_COUNT) { i ->
                val a = 2 * PI * i / BURST_COUNT + if (i % 2 == 0) 0.0 else 0.18
                val reach = base * (1.1f + p * if (i % 2 == 0) 1.1f else 0.75f)
                val r = (if (i % 2 == 0) 4f else 3f) * density * (1f - p * 0.6f)
                drawCircle(
                    colors[i % colors.size].copy(alpha = (1f - p) * 0.95f),
                    radius = r,
                    center = center + Offset((cos(a) * reach).toFloat(), (sin(a) * reach).toFloat()),
                )
            }
        },
    )
}

private const val BURST_COUNT = 12

/**
 * 惯性滚轮：甩动带惯性、松手吸附到最近的刻度，滚到头由系统的回弹拉伸收住；每过一格轻震一下。
 * 中间一行是选中项，底下的高亮条由调用方统一画（两个滚轮共用一条），这里只管行。
 */
@Composable
fun WheelPicker(
    count: Int,
    initial: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = WHEEL_ITEM,
    label: (Int) -> String = { "%02d".format(it) },
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, count - 1))
    // 系统默认的甩动减速很急，一下就停、感觉不到惯性：把初速压低、摩擦调小，滑得距离差不多但减速过程更长，
    // 最后吸附到刻度的回位也放软一点
    val fling = remember(state) {
        val snap = snapFlingBehavior(
            SnapLayoutInfoProvider(state, SnapPosition.Center),
            exponentialDecay(frictionMultiplier = WHEEL_FRICTION),
            spring(dampingRatio = 0.9f, stiffness = 300f),
        )
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(snap) { performFling(initialVelocity * WHEEL_VELOCITY) }
        }
    }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val selected by remember {
        derivedStateOf {
            val bump = if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            (state.firstVisibleItemIndex + bump).coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { selected }.distinctUntilChanged().drop(1).collect {
            view.tick()
            onSelected(it)
        }
    }
    val pad = itemHeight * (WHEEL_ROWS / 2)
    LazyColumn(
        modifier.height(itemHeight * WHEEL_ROWS),
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = pad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count) { i ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .graphicsLayer {
                        // 离中线越远越小越淡，像真的滚筒
                        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
                        val d = if (info == null) WHEEL_ROWS.toFloat() else abs(info.offset / itemPx)
                        val f = (1f - d / (WHEEL_ROWS / 2 + 1f)).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * f * f
                        val s = 0.82f + 0.18f * f
                        scaleX = s
                        scaleY = s
                    }
                    .clickable(interactionSource = null, indication = null) {
                        scope.launch { state.animateScrollToItem(i) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(i),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (i == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

val WHEEL_ITEM = 44.dp
private const val WHEEL_FRICTION = 0.6f
private const val WHEEL_VELOCITY = 0.8f
const val WHEEL_ROWS = 5

/**
 * 底部导航的圆形页签：只有图标，没有文字（文字留给读屏）。按下时和完成反馈按钮一样轻轻一压；
 * 选中时品牌渐变的实心圆从中心弹开，外面荡开一圈淡淡的波纹，图标换成白色实心款。
 */
@Composable
fun CircleTabButton(
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val ring = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.88f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    // 阻尼小于 1，实心圆弹开时略过头再回来
    val fill by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "fill",
    )
    val pulse = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        // 第一次显示不荡波纹，只有真的切过来才荡
        if (seen && selected) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(520, easing = LinearOutSlowInEasing))
        } else if (pulse.value < 1f) {
            // 波纹还没荡完就被切走：上一段动画随 selected 变化被取消，值停在半路，
            // 不收尾的话那圈环会一直留在旧页签上。快速荡完，让它自然消失
            pulse.animateTo(1f, tween(160))
        }
        seen = true
    }
    val view = LocalView.current
    Box(
        modifier
            .size(TAB_SIZE)
            .graphicsLayer { scaleX = press; scaleY = press }
            .drawBehind {
                val r = size.minDimension / 2
                val p = pulse.value
                if (p < 1f) {
                    drawCircle(ring.copy(alpha = (1f - p) * 0.45f), radius = r * (1f + 0.4f * p), style = Stroke(2.dp.toPx()))
                }
                if (fill > 0f) drawCircle(accents.heroGradient, radius = r * fill.coerceAtMost(1.08f))
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = ripple(),
                role = Role.Tab,
                onClickLabel = label,
            ) {
                if (!selected) view.tick()
                onClick()
            }
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // 图标跟着实心圆走：圆长过一半换实心款、缩过一半换回线框，不会在白底上闪一下白色实心图标
            if (fill > 0.5f) selectedIcon else icon,
            null,
            Modifier.size(24.dp),
            tint = lerp(idle, Color.White, fill.coerceIn(0f, 1f)),
        )
    }
}

val TAB_SIZE = 50.dp
