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
    // 色带的「可连自习到几点」按用户自定义作息换算成钟点；设置里改完作息，这里跟着变。
    val periodTimes by ServiceLocator.settings.periodTimesFlow.collectAsState()
    // 收起状态放在这一层：收起的是筛选面板，滚动发生在它下面的结果列表，两者是兄弟节点。
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
                    // 查完之后筛选就完成了：结果列表向下滑时收起面板，把屏幕还给内容；点箭头再展开。
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
                            !s.queried -> EmptyBox(
                                "选择楼房与时间后点击查询",
                                hint = "结果由教室课表取补集算出，只反映排课占用，不含临时借用",
                            )
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

        // 节次成对摆放（1-2、3-4……）好点不散；整对未选时点一下两节都选上，
        // 之后每个数字就是自己的开关，点选中状态里的某个数字只取消它。
        // 与我的课表联动：有课的节次带课程色圆点，下面几行直接说哪几节有课——
        // 提示而不是禁用：帮同学代查空闲时仍可以选自己有课的时段。
        val myByPeriod = myLessonAt(s)
        val mySpans = myLessonSpans(s)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12).forEach { (a, b) ->
                PeriodPairChip(a, b, s.periods, myByPeriod[a] ?: myByPeriod[b], vm::togglePeriod)
            }
        }
        // 快速选：从所选最早一节出发——往后带两节 / 一路到晚间最后一段 / 全天。
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
                label = { Text("到21:30") },
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

/** 收起后的那颗箭头：点它把筛选面板再展开。保持 48dp 点击域，别缩成小图标。 */
@Composable
private fun CollapsedFilterBar(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.ExpandMore, contentDescription = "展开筛选")
        }
    }
}

/**
 * 一对节次一颗 chip：整对未选时点一下两节都选上；之后每个数字就是独立的开关，
 * 点选中状态里的某个数字只取消它——不用在 12 颗散开的小 chip 之间来回找。
 */
@Composable
private fun PeriodPairChip(
    a: Int,
    b: Int,
    periods: List<Int>,
    lesson: Course?,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedA = a in periods
    val selectedB = b in periods
    val anySelected = selectedA || selectedB
    val accent = lesson?.let { courseAccent(it.name) }
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selectedA && selectedB -> MaterialTheme.colorScheme.secondaryContainer
                    anySelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .border(
                1.dp,
                if (anySelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 半区不能 weight：外层处于 horizontalScroll，宽度约束无限，weight 会把子项压成 0 宽、数字被裁没
            PeriodHalf(a, selectedA, accent, onToggle)
            Text("-", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PeriodHalf(b, selectedB, accent, onToggle)
            Text(
                "节",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, end = 7.dp),
            )
        }
    }
}

/** 一对里的半个：整个半区都是点击域，数字本身按选中与否变色加粗，读屏单独报状态。 */
@Composable
private fun PeriodHalf(
    p: Int,
    selected: Boolean,
    accent: Color?,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = "第$p 节" + if (selected) "，已选" else "，未选" }
            .clickable { onToggle(p) }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$p",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accent != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
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
    // 整楼状态：空闲的排前面，占用的跟在后面。我的课整张列表共享一份（同一节所有教室都一样），
    // 在这里算好传下去，别每张卡各算各的。
    val ordered = s.buildingRooms
    val myBusy = myLessonAt(s).keys
    // 向下滑 = 往列表深处翻，说明筛选已经用完：通知外层收起面板。只收不自动展开，展开只认箭头。
    // 阈值 100px 滤掉手抖；快速下滑时 snapshotFlow 会跳发，跨过的距离更大，同样触发。
    // 只 key 到 listState 不 key 回调：回调每次重组都是新实例，key 进去会在展开的一瞬重启并误收起。
    LaunchedEffect(listState) {
        var last = 0
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

        if (free.isEmpty()) {
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

        // key 去掉下标：换查询条件时同一间教室仍是同一个身份，animateItem 才能接管它的移动
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

/**
 * 结果汇总卡。原来是一行小字，但每张教室卡都值得配一个醒目的总数：
 * 绿色占比条与卡片色带同用 success 色，「这栋楼紧不紧张」一眼可见。
 */
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
            Spacer(Modifier.height(9.dp))
            Text(
                "卡片下方色带＝当天 1-12 节：绿＝空闲，灰＝有课，描边＝所选时段；只反映排课占用，不含临时借用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
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

/** 所查节次换算成钟点，如「第 3、5-6 节 · 10:00-17:10」；作息取不到时只报节次。 */
private fun periodRangeLabel(s: ClassroomUiState, periodTimes: List<String>): String {
    val range = periodsLabel(s.periods)
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

/** 所选周当天的「我的课」：节次 → 课程。课表取不到时为空表，界面各处静默退回无联动状态。 */
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

/** 当天的课按节次并成连续段，同一门课连堂只报一次。 */
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

/** 与课表页同一映射的课程色，取加深的 accent 画小圆点，浅色深色主题都看得清。 */
@Composable
private fun courseAccent(courseName: String): Color =
    LocalAccents.current.courseAccents[courseColorIndex(courseName, LocalAccents.current.courseAccents.size)]

/**
 * 当天 1-12 节的占用色带。「是否空闲」只回答所选那一段，
 * 这条带把前后也亮出来：选完时段往往还想看看前后能不能多学一会儿。
 */
@Composable
private fun DayStrip(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: List<Int>,
    modifier: Modifier = Modifier,
) {
    // 每节课记录盖住它的一次安排；一次课可跨多节，同一实例连续出现，读屏文案按实例连续段生成
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
                        if (session == null) LocalAccents.current.success.copy(alpha = 0.30f)
                        else MaterialTheme.colorScheme.surfaceVariant,
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
    val range = periodsLabel(selected)
    return parts.joinToString("；") + "；主色描边为所选的$range"
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
                            // 只数所查那一周真正发生的，不是整学期的排课条数
                            "本周共 ${room.busy.count { it.occursOn(week) }} 次排课",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isFree) FreeChip() else OccupiedChip()
            }
            Spacer(Modifier.height(11.dp))
            DayStrip(room, week, dayOfWeek, selected)
            if (isFree) {
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

/**
 * 「空闲」从 AssistChip 换成 success 色块：绿色在这里只表达「可用」，
 * 不与选中态的浅蓝混淆，也不用 AssistChip 的点击涟漪误导「可以点它」。
 */
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

/** 整楼列表里排在后面的占用教室：灰 chip 与绿色的「空闲」形成对照，色带仍是主角。 */
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

/**
 * 列表里的教室在所选时段都空闲，但能不能往后连下去因教室而异——
 * 「能自习到几点」比一句「空闲」更接近找座位的真实问题。
 * 连续性同时被两种东西截断：教室被占（「下课」），以及你自己有课（「你有课」）——
 * 课表联动在这里生效：教室再空，赶不上你的下一节课也用不上。
 */
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

/** 与结果卡同构的骨架：占位高度接近真实卡片，查询完成后页面不跳。 */
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
