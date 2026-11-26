package io.github.joyreverie.onebnu.ui.classroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.DataStatusRow
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.RollingNumber
import io.github.joyreverie.onebnu.ui.components.Shimmer
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.courseColorIndex
import io.github.joyreverie.onebnu.ui.theme.listPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAYS = listOf("一", "二", "三", "四", "五", "六", "日")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private const val DAY_MILLIS = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomScreen(onBack: () -> Unit, vm: ClassroomViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val periodTimes by ServiceLocator.settings.periodTimesFlow.collectAsState()
    var filtersExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        // 注意：不要在 composable 作用域里提前 return —— 那会破坏 Compose 的槽表并崩溃。
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loadingOptions -> ClassroomLoading("正在加载楼房信息…")
                // 楼房都没拿到，筛选面板无从展示，整页给出错误与重试
                s.buildings.isEmpty() && s.error != null -> ErrorBox(s.error!!) { vm.retry() }
                else -> {
                    // 结果列表下滑时收起筛选面板，点箭头展开
                    Column(Modifier.animateContentSize()) {
                        AnimatedVisibility(
                            filtersExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            FilterPanel(s, vm)
                        }
                        if (!filtersExpanded) CollapsedFilterBar(onExpand = { filtersExpanded = true })
                    }
                    Divider()

                    Box(Modifier.fillMaxSize()) {
                        when {
                            s.loading -> ClassroomLoading("正在拉取整栋楼的课表…")
                            s.error != null -> ErrorBox(s.error!!) { vm.retry() }
                            !s.queried -> EmptyBox("选择楼房后查看空闲教室")
                            s.emptyReason != null -> EmptyBox(s.emptyReason!!, onRetry = { vm.query() })
                            else -> ResultList(s, periodTimes, listState) { filtersExpanded = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(s: ClassroomUiState, vm: ClassroomViewModel) {
    var pickingDate by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OptionPicker(
            label = "选择楼房",
            current = s.building,
            options = s.buildings,
            onPick = vm::selectBuilding,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        // 周次与日期互为映射：拨周次，右侧日期跟着变；选日期，周次和星期跟着变
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setWeek(s.week - 1) }, enabled = s.week > 1) {
                Icon(Icons.Filled.Remove, "上一周")
            }
            Text(
                "第 ${s.week} 周",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            IconButton(onClick = { vm.setWeek(s.week + 1) }, enabled = s.week < s.maxWeek) {
                Icon(Icons.Filled.Add, "下一周")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { pickingDate = true }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${s.date.format(DATE_FMT)} 周${DAYS[s.dayOfWeek - 1]}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            s.calendarNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(10.dp))

        // 星期
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DAYS.forEachIndexed { i, d ->
                FilterChip(
                    selected = s.dayOfWeek == i + 1,
                    onClick = { vm.setDay(i + 1) },
                    label = { Text("周$d") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 节次成对显示；自己有课的节次带课程色圆点，只提示不禁用
        val myByPeriod = myLessonAt(s)
        val mySpans = myLessonSpans(s)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12).forEach { (a, b) ->
                PeriodPairChip(a, b, s.periods, myByPeriod[a] ?: myByPeriod[b], vm::tapSegment)
            }
        }
        val periodTimes by ServiceLocator.settings.periodTimesFlow.collectAsState()
        val dayEnd = periodTimes.getOrNull(11)?.let { PeriodMapper.parsePeriod(it)?.second }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val anchor = s.periods.firstOrNull() ?: 1
            val pairEnd = (anchor + 1).coerceAtMost(12)
            FilterChip(
                selected = s.periods == listOf(anchor, pairEnd),
                onClick = { vm.applyPeriods(listOf(anchor, pairEnd)) },
                label = { Text("后两节") },
            )
            FilterChip(
                selected = s.periods == (anchor..12).toList(),
                onClick = { vm.applyPeriods((anchor..12).toList()) },
                label = { Text(dayEnd?.let { "到${PeriodMapper.format(it)}" } ?: "到最后") },
            )
            FilterChip(
                selected = s.periods == (1..12).toList(),
                onClick = { vm.applyPeriods((1..12).toList()) },
                label = { Text("全天") },
            )
        }
        if (mySpans.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(
                "当天你有课：" + mySpans.joinToString(" · ") { (range, course) ->
                    "${periodLabel(range)} ${course.name}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { vm.query() },
            enabled = s.building != null && !s.loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("查询空闲教室")
        }
    }

    if (pickingDate) {
        TermDatePickerDialog(
            initial = s.date,
            first = s.termStart,
            last = s.lastDate,
            onDismiss = { pickingDate = false },
            onPick = {
                vm.setDate(it)
                pickingDate = false
            },
        )
    }
}

/** 只允许选本学期范围内的日期，选定后由 ViewModel 换算成周次与星期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermDatePickerDialog(
    initial: LocalDate,
    first: LocalDate,
    last: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * DAY_MILLIS,
        yearRange = first.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = LocalDate.ofEpochDay(utcTimeMillis.floorDiv(DAY_MILLIS))
                return !d.isBefore(first) && !d.isAfter(last)
            }

            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it.floorDiv(DAY_MILLIS))) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun OptionPicker(
    label: String,
    current: Option?,
    options: List<Option>,
    onPick: (Option) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                current?.name ?: label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.name) },
                    onClick = {
                        open = false
                        onPick(o)
                    },
                )
            }
        }
    }
}

/** 筛选面板收起后的展开按钮。 */
@Composable
private fun CollapsedFilterBar(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.ExpandMore, contentDescription = "展开筛选")
        }
    }
}

/** 一对节次一个胶囊，两个数字各自开关；整对未选时点任一个两节一起选上。 */
@Composable
private fun PeriodPairChip(
    a: Int,
    b: Int,
    periods: List<Int>,
    lesson: Course?,
    onTapSegment: (Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedA = a in periods
    val selectedB = b in periods
    val accent = lesson?.let { courseAccent(it.name) }
    val stateDesc = when {
        selectedA && selectedB -> "两节都已选"
        selectedA -> "已选第 $a 节"
        selectedB -> "已选第 $b 节"
        else -> "未选"
    }
    Row(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = "第 $a-$b 节，$stateDesc" }
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeriodSegment(a, b, a, selectedA, accent, onTapSegment)
        PeriodSegment(a, b, b, selectedB, accent, onTapSegment)
        Text(
            "节",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 1.dp, end = 5.dp),
        )
    }
}

@Composable
private fun PeriodSegment(
    pairA: Int,
    pairB: Int,
    p: Int,
    selected: Boolean,
    accent: Color?,
    onTapSegment: (Int, Int, Int) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onTapSegment(pairA, pairB, p) }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$p",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accent != null) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else accent),
            )
        }
    }
}

@Composable
private fun ResultList(
    s: ClassroomUiState,
    periodTimes: List<String>,
    listState: LazyListState,
    onScrollDown: () -> Unit,
) {
    val free = s.freeRooms
    val freeSet = free.toSet()
    val ordered = s.buildingRooms
    val myBusy = myLessonAt(s).keys
    // 下滑超过 100px 收起筛选面板；只 key listState，回调每次重组都是新实例
    LaunchedEffect(listState) {
        var last = listState.firstVisibleItemIndex * 10000 + listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex * 10000 + listState.firstVisibleItemScrollOffset }
            .collect { pos ->
                if (pos > last + 100) onScrollDown()
                last = pos
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = LocalScreenInfo.current.listPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SummaryCard(s, free.size, periodTimes) }

        if (s.periods.isNotEmpty() && free.isEmpty()) {
            item {
                Text(
                    "该时段这栋楼没有空闲教室",
                    Modifier.padding(top = 32.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        itemsIndexed(ordered, key = { _, room -> "${room.building}|${room.name}" }) { _, room ->
            RoomCard(
                room,
                s.week,
                s.dayOfWeek,
                s.periods,
                periodTimes,
                myBusy,
                isFree = room in freeSet,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** 结果汇总：空闲数、占比条与数据状态。 */
@Composable
private fun SummaryCard(s: ClassroomUiState, freeCount: Int, periodTimes: List<String>, modifier: Modifier = Modifier) {
    val ratio = if (s.rooms.isEmpty()) 0f else freeCount.toFloat() / s.rooms.size
    val animated by animateFloatAsState(ratio, tween(360), label = "freeRatio")
    BnuCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "第 ${s.week} 周 周${DAYS[s.dayOfWeek - 1]}（${s.date.format(DATE_FMT)}）" +
                    " · ${s.building?.name ?: ""} · ${periodRangeLabel(s, periodTimes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (s.periods.isEmpty()) {
                Text(
                    "整楼 ${s.rooms.size} 间教室",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingNumber(
                        value = freeCount,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "/ ${s.rooms.size} 间空闲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(9.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animated.coerceIn(0f, 1f))
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(LocalAccents.current.success),
                    )
                }
            }
            val busyHere = s.periods.mapNotNull { myLessonAt(s)[it]?.name }.distinct()
            if (busyHere.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "所选时段你有课：" + busyHere.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAccents.current.warning,
                )
            }
            if (s.freshness != null) {
                Spacer(Modifier.height(8.dp))
                DataStatusRow(s.freshness)
            }
        }
    }
}

/** 所查节次换算成钟点，如「第 3、5-6 节 · 10:00-17:10」；未选节次时就是整楼浏览。 */
private fun periodRangeLabel(s: ClassroomUiState, periodTimes: List<String>): String {
    if (s.periods.isEmpty()) return "整楼浏览"
    val range = periodsLabel(s.periods)
    // 不连续的节次不给一段起止钟点，免得看成整段都查了
    if (s.periods.last() - s.periods.first() + 1 != s.periods.size) return range
    val start = periodTimes.getOrNull(s.periods.first() - 1)?.let { PeriodMapper.parsePeriod(it)?.first }
    val end = periodTimes.getOrNull(s.periods.last() - 1)?.let { PeriodMapper.parsePeriod(it)?.second }
    return if (start != null && end != null) {
        "$range · ${PeriodMapper.format(start)}-${PeriodMapper.format(end)}"
    } else {
        range
    }
}

/** 「第 3 节」/「第 3-4 节」。 */
private fun periodLabel(range: IntRange): String =
    if (range.first == range.last) "第 ${range.first} 节" else "第 ${range.first}-${range.last} 节"

/** 任意节次集合的标签：连续段合并，如「第 3、5-6 节」。 */
private fun periodsLabel(periods: List<Int>): String {
    if (periods.isEmpty()) return "未选节次"
    val parts = ArrayList<String>()
    var i = 0
    while (i < periods.size) {
        var j = i
        while (j + 1 < periods.size && periods[j + 1] == periods[j] + 1) j++
        parts += if (i == j) "${periods[i]}" else "${periods[i]}-${periods[j]}"
        i = j + 1
    }
    return "第 " + parts.joinToString("、") + " 节"
}

/** 所选日期自己的课：节次 → 课程。 */
private fun myLessonAt(s: ClassroomUiState): Map<Int, Course> {
    val map = mutableMapOf<Int, Course>()
    for (course in s.myCourses) {
        for (session in course.sessions) {
            if (session.dayOfWeek != s.dayOfWeek || !session.occursOn(s.week)) continue
            for (p in session.startPeriod..session.endPeriod) if (p !in map) map[p] = course
        }
    }
    return map
}

/** 自己的课按节次并成连续段。 */
private fun myLessonSpans(s: ClassroomUiState): List<Pair<IntRange, Course>> {
    val byPeriod = myLessonAt(s)
    val spans = ArrayList<Pair<IntRange, Course>>()
    var p = 1
    while (p <= 12) {
        val course = byPeriod[p]
        if (course == null) {
            p++
            continue
        }
        var q = p
        while (q < 12 && byPeriod[q + 1] === course) q++
        spans += p..q to course
        p = q + 1
    }
    return spans
}

/** 与课表页一致的课程色。 */
@Composable
private fun courseAccent(courseName: String): Color =
    LocalAccents.current.courseAccents[courseColorIndex(courseName, LocalAccents.current.courseAccents.size)]

/** 当天 1-12 节占用色带：绿为空闲，灰为有课，主色描边为所选节次。 */
@Composable
private fun DayStrip(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: List<Int>,
    modifier: Modifier = Modifier,
) {
    val busyOf = remember(room, week, dayOfWeek) {
        val map = mutableMapOf<Int, ClassSession>()
        for (session in room.busy) {
            if (session.dayOfWeek != dayOfWeek || !session.occursOn(week)) continue
            for (p in session.startPeriod..session.endPeriod) if (p !in map) map[p] = session
        }
        map
    }
    val desc = remember(busyOf, selected) { stripDescription(busyOf, selected) }
    val cellShape = RoundedCornerShape(3.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (p in 1..12) {
            val session = busyOf[p]
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(cellShape)
                    .background(
                        if (session == null) LocalAccents.current.success.copy(alpha = 0.60f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    )
                    .then(
                        if (p in selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, cellShape)
                        else Modifier
                    ),
            )
        }
    }
}

/** 色带的读屏文案：把 12 节并成连续段逐段念出，有课的段带上课程与教师。 */
private fun stripDescription(busyOf: Map<Int, ClassSession>, selected: List<Int>): String {
    val parts = ArrayList<String>()
    var p = 1
    while (p <= 12) {
        val session = busyOf[p]
        var q = p
        while (q < 12 && busyOf[q + 1] === session) q++
        val label = if (p == q) "第 $p 节" else "第 $p-$q 节"
        parts += if (session == null) "$label 空闲" else "$label 有课（${session.location.ifBlank { "课程" }}）"
        p = q + 1
    }
    return if (selected.isEmpty()) {
        parts.joinToString("；") + "；未选节次，整楼浏览"
    } else {
        parts.joinToString("；") + "；主色描边为所选的${periodsLabel(selected)}"
    }
}

@Composable
private fun RoomCard(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: List<Int>,
    periodTimes: List<String>,
    myBusy: Set<Int>,
    isFree: Boolean,
    modifier: Modifier = Modifier,
) {
    BnuCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            room.type.takeIf { it.isNotBlank() },
                            room.capacity?.let { "$it 座" },
                            "本周共 ${room.busy.count { it.occursOn(week) }} 次排课",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected.isEmpty()) {
                    // 整楼浏览不标空闲 / 占用
                } else if (isFree) {
                    FreeChip()
                } else {
                    OccupiedChip()
                }
            }
            Spacer(Modifier.height(11.dp))
            DayStrip(room, week, dayOfWeek, selected)
            if (isFree && selected.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    continuationLabel(room, week, dayOfWeek, selected, periodTimes, myBusy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FreeChip() {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LocalAccents.current.success.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            "空闲",
            color = LocalAccents.current.success,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OccupiedChip() {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            "占用",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 从所选最后一节往后能连续用到第几节：遇到教室有课或自己有课就停。 */
private fun continuationLabel(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: List<Int>,
    periodTimes: List<String>,
    myBusy: Set<Int>,
): String {
    val from = selected.maxOrNull() ?: return "未选节次"
    val busyHere = room.busy.filter { it.dayOfWeek == dayOfWeek && it.occursOn(week) }
    var last = from
    var stoppedByMe = false
    while (last < 12) {
        val next = last + 1
        if (next in myBusy) {
            stoppedByMe = true
            break
        }
        if (busyHere.any { it.startPeriod <= next && it.endPeriod >= next }) break
        last = next
    }
    val endLabel = periodTimes.getOrNull(last - 1)
        ?.let { PeriodMapper.parsePeriod(it)?.second?.let(PeriodMapper::format) }
    val nextStart = periodTimes.getOrNull(last)
        ?.let { PeriodMapper.parsePeriod(it)?.first?.let(PeriodMapper::format) }
    return when {
        stoppedByMe && last == from ->
            if (nextStart == null) "第 ${last + 1} 节起你有课" else "第 ${last + 1} 节起你有课（$nextStart 上课）"
        stoppedByMe ->
            if (nextStart == null) "可用到第 $last 节（第 ${last + 1} 节你有课）" else "可用到第 $last 节（$nextStart 你有课）"
        last == from -> if (endLabel == null) "空闲仅到第 $last 节" else "空闲仅到第 $last 节（$endLabel 下课）"
        else -> if (endLabel == null) "可连自习到第 $last 节" else "可连自习到第 $last 节（$endLabel 下课）"
    }
}

/** 与结果卡同结构的加载骨架。 */
@Composable
private fun ClassroomLoading(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(LocalScreenInfo.current.listPadding())) {
        BnuCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Shimmer(Modifier.fillMaxWidth(0.7f).height(12.dp))
                Spacer(Modifier.height(12.dp))
                Shimmer(Modifier.fillMaxWidth(0.3f).height(30.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        repeat(4) {
            BnuCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Shimmer(Modifier.weight(1f).height(15.dp))
                        Spacer(Modifier.width(12.dp))
                        Shimmer(Modifier.size(width = 44.dp, height = 22.dp), RoundedCornerShape(8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Shimmer(Modifier.fillMaxWidth().height(13.dp))
                    Spacer(Modifier.height(8.dp))
                    Shimmer(Modifier.fillMaxWidth(0.45f).height(10.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
