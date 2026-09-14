package io.github.joyreverie.onebnu.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.ui.components.OnResumed
import io.github.joyreverie.onebnu.ui.theme.LocalDarkTheme
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * 课表上「现在」的时刻：每到整分刷新一次，回到前台时立刻校准（后台里 delay 可能被系统推迟）。
 *
 * 以 [State] 而不是值的形式往下传。读它的地方只有指针的绘制 lambda，因此分钟一跳只触发那一层重绘，
 * 七列格子既不重组也不重新布局。[fixed] 供 debug 预览把时刻钉住用。
 */
@Composable
internal fun rememberMinuteClock(fixed: LocalTime? = null): State<LocalTime> {
    val clock = remember { mutableStateOf(fixed ?: LocalTime.now()) }
    if (fixed != null) {
        LaunchedEffect(fixed) { clock.value = fixed }
        return clock
    }
    OnResumed { clock.value = LocalTime.now() }
    LaunchedEffect(Unit) {
        while (true) {
            val ms = System.currentTimeMillis()
            delay(MINUTE_MS - ms % MINUTE_MS + 50L)
            clock.value = LocalTime.now()
        }
    }
    return clock
}

private const val MINUTE_MS = 60_000L

/**
 * 今天这一列上的「现在」指针。
 *
 * 指针以上到网格顶端蒙一层页面底色，把已经上过的课洗淡；指针本体是一条主色细线，
 * 左端一枚带光晕的圆点。纵向位置与日程用同一套换算（[PeriodMapper.position]）：
 * 上课时按节内进度线性落位，课间并在前一节的行里，午休、晚上开课前这类长空档贴到下一节上沿，
 * 第一节之前停在顶端、最后一节之后停在底端。
 *
 * 只画图、不接管触摸，格子照常可点。
 */
@Composable
internal fun NowIndicator(
    clock: State<LocalTime>,
    periodTimes: List<String>,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    // 深色下页面底色更暗，同样的透明度洗淡效果更强，稍微收一点
    val wash = MaterialTheme.colorScheme.background.copy(alpha = if (LocalDarkTheme.current) 0.48f else 0.55f)
    Box(
        modifier.drawBehind {
            val stroke = LINE_WIDTH.toPx()
            val rows = PeriodMapper.position(clock.value, periodTimes)
            val y = (rows * rowHeight.toPx()).coerceIn(stroke / 2, (size.height - stroke / 2).coerceAtLeast(stroke / 2))
            if (y > stroke) drawRect(wash, size = Size(size.width, y))

            val inset = 1.5.dp.toPx()
            val r = DOT_RADIUS.toPx()
            val cx = inset + 1.dp.toPx() + r
            drawLine(line, Offset(cx, y), Offset(size.width - inset, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(line.copy(alpha = 0.18f), radius = r * 2f, center = Offset(cx, y))
            drawCircle(ring, radius = r + 1.5.dp.toPx(), center = Offset(cx, y))
            drawCircle(line, radius = r, center = Offset(cx, y))
        },
    )
}

private val LINE_WIDTH = 2.dp
private val DOT_RADIUS = 3.5.dp
