package io.github.joyreverie.onebnu.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.InfoRow
import io.github.joyreverie.onebnu.ui.components.LoadingBox
import io.github.joyreverie.onebnu.ui.event.EventEditorSheet
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.courseAccent
import io.github.joyreverie.onebnu.ui.theme.courseColor
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PERIODS = ScheduleLayout.PERIODS
private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var selected by remember { mutableStateOf<Pair<Course, ClassSession>?>(null) }
    var termMenu by remember { mutableStateOf(false) }
    var weekMenu by remember { mutableStateOf(false) }
    // 双指缩放：改的是行高（字号跟一半），松手时记住
    val settings = ServiceLocator.settings
    var zoom by remember { mutableFloatStateOf(ScheduleLayout.clampZoom(settings.scheduleZoom)) }
    // 右上角灰色的「120%」：只在捏合时出现，松手一秒半后淡出，平时不占地方
    var zoomHint by remember { mutableStateOf(false) }
    var zoomTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(zoomTick) {
        if (zoomHint) {
            delay(1500)
            zoomHint = false
        }
    }
    // 日程编辑：adding 为新建，editing 为改已有的
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PersonalEvent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(onClick = { termMenu = true }) {
                            Text(
                                s.term?.name ?: "课表",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = termMenu, onDismissRequest = { termMenu = false }) {
                            s.terms.forEach { t ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            t.name,
                                            fontWeight = if (t.code == s.term?.code) FontWeight.Bold
                                            else FontWeight.Normal,
                                        )
                                    },
                                    onClick = { termMenu = false; vm.selectTerm(t) },
                                )
                            }
                        }
                    }
                },
                actions = {
                    AnimatedVisibility(visible = zoomHint, enter = fadeIn(), exit = fadeOut()) {
                        ZoomBadge(zoom)
                    }
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, "添加日程")
                    }
                    IconButton(onClick = { vm.setWeek(s.week - 1) }, enabled = s.week > 1) {
                        Icon(Icons.Filled.ChevronLeft, "上一周")
                    }
                    Box {
                        // 点周次即可挑周；本周单列一项，一步回到当前
                        Box(Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = { weekMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    "第 ${s.week} 周",
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (s.isCurrentWeek) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                        WeekMenu(
                            expanded = weekMenu,
                            state = s,
                            onDismiss = { weekMenu = false },
                            onPick = { weekMenu = false; vm.setWeek(it) },
                        )
                    }
                    IconButton(onClick = { vm.setWeek(s.week + 1) }, enabled = s.week < s.maxWeek) {
                        Icon(Icons.Filled.ChevronRight, "下一周")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
            when {
                s.loading -> LoadingBox("正在加载课表…")
                s.error != null -> ErrorBox(s.error!!) { vm.load(forceRefresh = true) }
                s.emptyReason != null -> EmptyBox(s.emptyReason!!, onRetry = { vm.load(forceRefresh = true) })
                else -> {
                    val schedule = s.schedule
                    if (schedule == null) {
                        EmptyBox("暂无课表数据", onRetry = { vm.load(forceRefresh = true) })
                    } else {
                        ScheduleWeekPager(
                            schedule = schedule,
                            state = s,
                            zoom = zoom,
                            onWeekChange = vm::setWeek,
                            onZoom = {
                                zoom = ScheduleLayout.clampZoom(zoom * it)
                                zoomHint = true
                                zoomTick++
                            },
                            onZoomEnd = {
                                settings.scheduleZoom = zoom
                                zoomTick++
                            },
                            onClick = { c, sess -> selected = c to sess },
                            onEventClick = { editing = it },
                        )
                    }
                }
            }
            }
        }
    }

    selected?.let { (course, session) ->
        val sheet = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = sheet) {
            CourseDetail(course, session, s.periodTimes)
        }
    }

    if (adding || editing != null) {
        val store = ServiceLocator.events
        val today = LocalDate.now()
        // 新建默认落在所看这一周：本周就是今天，别的周取那周的周一
        val defaultDate = s.weekDates.let { d -> if (d.contains(today) || d.isEmpty()) today else d.first() }
        EventEditorSheet(
            initial = editing,
            defaultDate = defaultDate,
            onDismiss = { adding = false; editing = null },
            onSave = { store.upsert(it); adding = false; editing = null },
            onDelete = { store.delete(it.id); adding = false; editing = null },
        )
    }
}

@Composable
private fun WeekMenu(
    expanded: Boolean,
    state: ScheduleUiState,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        state.currentWeek?.let { cur ->
            DropdownMenuItem(
                text = { Text("回到本周（第 $cur 周）", fontWeight = FontWeight.SemiBold) },
                onClick = { onPick(cur) },
            )
            Divider()
        }
        for (w in 1..state.maxWeek) {
            val range = state.termStart?.plusWeeks((w - 1).toLong())?.let { mon ->
                "${mon.monthValue}/${mon.dayOfMonth} - " +
                    mon.plusDays(6).let { "${it.monthValue}/${it.dayOfMonth}" }
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第 $w 周",
                            fontWeight = if (w == state.week) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (range != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                range,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (w == state.currentWeek) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "本周",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                onClick = { onPick(w) },
            )
        }
    }
}

/**
 * 双指缩放手势：只在两根手指同时按下时接管事件并回调缩放比例，单指滑动照常交给列表滚动。
 * 不用 detectTransformGestures —— 它会把单指拖动也当作平移吃掉，列表就滚不动了。
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zooming = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        onZoom(zoom)
                        zooming = true
                    }
                    event.changes.forEach { if (it.pressed) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (zooming) onEnd()
        }
    }

/** 网格里的一个格子是课还是日程。 */
private sealed interface GridPayload {
    data class CourseSlot(val course: Course, val session: ClassSession) : GridPayload
    data class Event(val event: PersonalEvent) : GridPayload
}

@Composable
internal fun ScheduleGrid(
    schedule: Schedule,
    state: ScheduleUiState,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit,
    onClick: (Course, ClassSession) -> Unit,
    onEventClick: (PersonalEvent) -> Unit = {},
) {
    // 重叠格子当前显示第几个，键为「周:星期:起始节」
    val shown = remember { mutableStateMapOf<String, Int>() }
    val s = state
    val screen = LocalScreenInfo.current
    val today = ScheduleViewModel.todayDayOfWeek()
    val isCurrentWeek = s.isCurrentWeek
    val dates = s.weekDates
    val density = LocalDensity.current

    // 随屏幕尺寸调整：大屏放大；横屏宽度富余、高度紧张，行高按一天 12 节尽量落进一屏来算；
    // 再乘上用户双指缩放的倍数
    val baseRowDp = ScheduleLayout.baseRowDp(screen.isShort, screen.isExpanded, screen.isMedium)
    val fontScale = ScheduleLayout.fontScale(zoom)
    // 刻度里的字是 sp，系统字体大小也会放大它：宽度与「显示几行」都得按实际倍数算
    val textScale = fontScale * density.fontScale
    val gutter = ScheduleLayout.gutterDp(screen.isExpanded, screen.isMedium, textScale).dp
    val titleSize = (if (screen.isCompact) 10.sp else 12.sp) * fontScale
    val subSize = (if (screen.isCompact) 9.sp else 11.sp) * fontScale

    BoxWithConstraints(Modifier.fillMaxSize()) {
        var headerPx by remember { mutableIntStateOf(0) }
        val availableDp = with(density) { (constraints.maxHeight - headerPx).toDp().value }
        val rowHeight = ScheduleLayout.rowDp(screen.isLandscape, availableDp, baseRowDp, zoom).dp

    Column(Modifier.fillMaxSize()) {
        // ---- 星期表头：固定不滚动，否则往下翻就不知道是星期几了 ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).onSizeChanged { headerPx = it.height }) {
            Spacer(Modifier.width(gutter))
            DAY_LABELS.forEachIndexed { i, label ->
                val day = i + 1
                val highlight = isCurrentWeek && day == today
                val date = dates.getOrNull(i)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(1.5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (highlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                        color = if (highlight) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (date != null) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            fontSize = 9.sp,
                            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (highlight) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // ---- 课程网格（仅这部分滚动，表头保持可见）----
        // 按「天」成列渲染：跨节的课块直接给出 span 倍高度，
        // 比逐格渲染再撑高可靠 —— 后者会被固定高度的行裁掉。
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .pinchToZoom(onZoom, onZoomEnd)
                .padding(horizontal = 2.dp),
        ) {
            // 左侧节次与上下课时刻。节次号比时间小一号，三行才不至于互相挤压；
            // 行高不够（横屏压缩、缩到最小、系统字体调大）时按 gutterDetail 逐级少显示一行
            val detail = ScheduleLayout.gutterDetail(rowHeight.value, textScale)
            Column(Modifier.width(gutter)) {
                for (period in 1..PERIODS) {
                    val spec = s.periodTimes.getOrNull(period - 1)
                    Column(
                        Modifier.height(rowHeight).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "$period",
                            fontSize = 10.sp * fontScale,
                            lineHeight = 12.sp * fontScale,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (spec != null && detail != ScheduleLayout.GutterDetail.NUMBER_ONLY) {
                            Text(
                                spec.substringBefore('-'),
                                fontSize = 8.5.sp * fontScale,
                                lineHeight = 10.sp * fontScale,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                            if (detail == ScheduleLayout.GutterDetail.START_AND_END) {
                                Text(
                                    spec.substringAfter('-'),
                                    fontSize = 8.5.sp * fontScale,
                                    lineHeight = 10.sp * fontScale,
                                    // 下课时刻弱一档，一眼能看出哪个是上课时间
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            for (day in 1..7) {
                val date = dates.getOrNull(day - 1)
                // 课与日程放进同一列：课程占整节，日程按起止时刻在行内定位；
                // 时间上真正重叠的才归为一簇（一次显示一个），首尾相接的各自显示
                val items = schedule.slotsOn(s.week, day).map { (course, sess) ->
                    ScheduleLayout.GridItem.periods(sess.startPeriod, sess.endPeriod, GridPayload.CourseSlot(course, sess) as GridPayload)
                } + (if (date != null) s.events.filter { it.occursOn(date) } else emptyList()).map { e ->
                    val (top, bottom) = PeriodMapper.span(e.start, e.end, s.periodTimes, ScheduleLayout.MIN_SPAN)
                    ScheduleLayout.GridItem(top, bottom, GridPayload.Event(e) as GridPayload)
                }
                val groups = ScheduleLayout.groupColumn(items)
                val isToday = isCurrentWeek && day == today

                Column(
                    Modifier
                        .weight(1f)
                        .then(
                            if (isToday) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                                )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    var period = 1
                    groups.forEach { group ->
                        while (period < group.start) {
                            EmptyCell(rowHeight)
                            period++
                        }
                        val blockHeight = rowHeight * group.span
                        Box(Modifier.height(blockHeight).fillMaxWidth()) {
                            Column { repeat(group.span) { EmptyCell(rowHeight) } }
                            group.clusters.forEachIndexed { clusterIndex, items ->
                            // 时间上重叠的一簇不并排挤成细条：一次只显示一个，底部的切换条点一下换下一个
                            val key = "${s.week}:$day:${group.start}:$clusterIndex"
                            val shownIndex = (shown[key] ?: 0).mod(items.size)
                            val item = items[shownIndex]
                            val conflicting = items.count { it.payload is GridPayload.CourseSlot } > 1
                            val inset = if (items.size > 1) OVERLAP_STRIP else 0.dp
                            // 按时刻定位、按时长取高；太短的日程保证一个最小高度，能读出标题
                            val cellHeight = maxOf(rowHeight * (item.bottom - item.top), MIN_CELL_HEIGHT)
                            val cellTop = (rowHeight * (item.top - (group.start - 1)))
                                .coerceIn(0.dp, (blockHeight - cellHeight).coerceAtLeast(0.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = cellTop)
                                    .height(cellHeight)
                                    .padding(1.5.dp),
                            ) {
                                when (val p = item.payload) {
                                    is GridPayload.CourseSlot -> CourseCell(
                                        course = p.course,
                                        session = p.session,
                                        conflicting = conflicting,
                                        titleSize = titleSize,
                                        subSize = subSize,
                                        bottomInset = inset,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onClick(p.course, p.session) },
                                    )
                                    is GridPayload.Event -> EventCell(
                                        event = p.event,
                                        titleSize = titleSize,
                                        subSize = subSize,
                                        bottomInset = inset,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { onEventClick(p.event) },
                                    )
                                }
                                if (items.size > 1) {
                                    OverlapSwitch(
                                        index = shownIndex + 1,
                                        total = items.size,
                                        subSize = subSize,
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    ) { shown[key] = shownIndex + 1 }
                                }
                            }
                            }
                        }
                        period = group.end + 1
                    }
                    while (period <= PERIODS) {
                        EmptyCell(rowHeight)
                        period++
                    }
                }
            }
        }
    }
    }
}

/** 顶栏右侧的小灰字缩放比例，如「120%」。 */
@Composable
internal fun ZoomBadge(zoom: Float) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            "${(zoom * 100).roundToInt()}%",
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyCell(rowHeight: Dp) {
    Box(Modifier.height(rowHeight).fillMaxWidth().padding(1.5.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
        )
    }
}

/** 重叠格子底部的切换条：写着「第几个 / 共几个」，点它换下一个显示。 */
@Composable
private fun OverlapSwitch(
    index: Int,
    total: Int,
    subSize: TextUnit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(OVERLAP_STRIP)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), shape = RoundedCornerShape(50)) {
            Row(
                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.SwapVert,
                    "切换重叠的课程或日程",
                    Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(Modifier.width(1.dp))
                Text(
                    "$index/$total",
                    fontSize = subSize,
                    lineHeight = subSize * LINE_SPACING,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val OVERLAP_STRIP = 20.dp

/** 日程格子的最小高度：一行标题加内边距。 */
private val MIN_CELL_HEIGHT = 26.dp

/** 个人日程的格子：木铎金底 + 细描边，与课程块一眼区分；显示标题、时间、地点。 */
@Composable
private fun EventCell(
    event: PersonalEvent,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 10.sp,
    subSize: TextUnit = 9.sp,
    bottomInset: Dp = 0.dp,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = 4.dp + bottomInset),
            ) {
                val density = LocalDensity.current
                val titleLineDp: Dp = with(density) { (titleSize * LINE_SPACING).toDp() }
                val subLineDp: Dp = with(density) { (subSize * LINE_SPACING).toDp() }
                val avail = maxHeight
                // 窄格放不下「08:00–10:00」，只给开始时间
                val timeText = if (maxWidth < 64.dp) event.start.format(PersonalEvent.HM) else event.timeLabel
                // 高度够就放时间，再够就放地点；很短的日程只留标题
                val showTime = avail >= titleLineDp + subLineDp + GAP
                val showLocation = showTime && event.location.isNotBlank() && avail >= titleLineDp + subLineDp * 2 + GAP * 2
                val subLines = if (showLocation) 2 else if (showTime) 1 else 0
                val titleLines = ((avail - subLineDp * subLines - (if (showTime) GAP else 0.dp)) / titleLineDp)
                    .toInt().coerceIn(1, 10)
                Column {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = titleSize,
                        lineHeight = titleSize * LINE_SPACING,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (showTime) {
                        Spacer(Modifier.height(GAP))
                        Text(
                            timeText,
                            fontSize = subSize,
                            lineHeight = subSize * LINE_SPACING,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    if (showLocation) {
                        Text(
                            event.location,
                            fontSize = subSize,
                            lineHeight = subSize * LINE_SPACING,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseCell(
    course: Course,
    session: ClassSession,
    modifier: Modifier = Modifier,
    conflicting: Boolean = false,
    titleSize: TextUnit = 10.sp,
    subSize: TextUnit = 9.sp,
    bottomInset: Dp = 0.dp,
) {
    val accent = courseAccent(course.name)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = courseColor(course.name),
        border = if (conflicting) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧强调条：同色系但更深，让每块课在浅底上有明确归属
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))

            // 行数按实际可用高度算出来。写死 2/3 行的话，跨 4 节的大格子
            // 明明还空着一大片，课名却已经被截成「…」。
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = 4.dp + bottomInset),
            ) {
                val density = LocalDensity.current
                val titleLineDp: Dp = with(density) { (titleSize * LINE_SPACING).toDp() }
                val subLineDp: Dp = with(density) { (subSize * LINE_SPACING).toDp() }
                val avail = maxHeight

                // 先给地点留一行，剩下全归课名；空间特别富裕时地点也放开到两行
                val subLines = if (avail >= titleLineDp * 4 + subLineDp * 2) 2 else 1
                val titleLines = ((avail - subLineDp * subLines - GAP) / titleLineDp)
                    .toInt().coerceIn(1, 10)

                Column {
                    Text(
                        course.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = titleSize,
                        lineHeight = titleSize * LINE_SPACING,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (session.location.isNotBlank()) {
                        Spacer(Modifier.height(GAP))
                        Text(
                            session.location,
                            fontSize = subSize,
                            lineHeight = subSize * LINE_SPACING,
                            maxLines = subLines,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val LINE_SPACING = 1.22f
private val GAP = 2.dp

@Composable
private fun CourseDetail(course: Course, session: ClassSession, periodTimes: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(courseAccent(course.name)),
            )
            Spacer(Modifier.width(10.dp))
            Text(course.name, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            course.code,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("${course.credits} 学分") })
            course.hours?.let { AssistChip(onClick = {}, label = { Text("$it 学时") }) }
            if (course.studyType.isNotBlank()) {
                AssistChip(onClick = {}, label = { Text(course.studyType) })
            }
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        InfoRow("任课教师", course.teacherLabel.ifBlank { "—" })
        InfoRow("上课班号", course.classNo.ifBlank { "—" })
        InfoRow("本次地点", session.location)
        InfoRow("本次周次", "第 ${session.weeksLabel} 周")
        InfoRow(
            "本次节次",
            buildString {
                append(session.periodLabel)
                val a = periodTimes.getOrNull(session.startPeriod - 1)?.substringBefore('-')
                val b = periodTimes.getOrNull(session.endPeriod - 1)?.substringAfter('-')
                if (a != null && b != null) append("  $a-$b")
            },
        )

        if (course.sessions.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text("全部安排", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            course.sessions.forEach { x ->
                Text(
                    "· 第 ${x.weeksLabel} 周  周${DAY_LABELS[x.dayOfWeek - 1]}  ${x.periodLabel}  ${x.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
