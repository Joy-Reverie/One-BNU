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

        // 节次
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12).forEach { (a, b) ->
                FilterChip(
                    selected = s.startPeriod == a && s.endPeriod == b,
                    onClick = { vm.setPeriods(a, b) },
                    label = { Text("$a-$b 节") },
                )
            }
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

@Composable
private fun ResultList(
    s: ClassroomUiState,
    periodTimes: List<String>,
    listState: LazyListState,
    onScrollDown: () -> Unit,
) {
    val free = s.freeRooms
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
        itemsIndexed(free, key = { _, room -> "${room.building}|${room.name}" }) { _, room ->
            RoomCard(
                room,
                s.week,
                s.dayOfWeek,
                s.startPeriod..s.endPeriod,
                periodTimes,
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
            if (s.freshness != null) {
                Spacer(Modifier.height(8.dp))
                DataStatusRow(s.freshness)
            }
        }
    }
}

/** 所查节次换算成钟点，如「第 3-4 节 · 10:00-11:40」；作息取不到时只报节次。 */
private fun periodRangeLabel(s: ClassroomUiState, periodTimes: List<String>): String {
    val range = if (s.startPeriod == s.endPeriod) "第 ${s.startPeriod} 节" else "第 ${s.startPeriod}-${s.endPeriod} 节"
    val start = periodTimes.getOrNull(s.startPeriod - 1)?.let { PeriodMapper.parsePeriod(it)?.first }
    val end = periodTimes.getOrNull(s.endPeriod - 1)?.let { PeriodMapper.parsePeriod(it)?.second }
    return if (start != null && end != null) {
        "$range · ${PeriodMapper.format(start)}-${PeriodMapper.format(end)}"
    } else {
        range
    }
}

/**
 * 当天 1-12 节的占用色带。「是否空闲」只回答所选那一段，
 * 这条带把前后也亮出来：选完时段往往还想看看前后能不能多学一会儿。
 */
@Composable
private fun DayStrip(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: IntRange,
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
private fun stripDescription(busyOf: Map<Int, ClassSession>, selected: IntRange): String {
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
    val range = if (selected.first == selected.last) "第 ${selected.first} 节" else "第 ${selected.first}-${selected.last} 节"
    return parts.joinToString("；") + "；主色描边为所选的$range"
}

@Composable
private fun RoomCard(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: IntRange,
    periodTimes: List<String>,
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
                FreeChip()
            }
            Spacer(Modifier.height(11.dp))
            DayStrip(room, week, dayOfWeek, selected)
            Spacer(Modifier.height(6.dp))
            Text(
                continuationLabel(room, week, dayOfWeek, selected, periodTimes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * 列表里的教室在所选时段都空闲，但能不能往后连下去因教室而异——
 * 「能自习到几点」比一句「空闲」更接近找座位的真实问题。
 */
private fun continuationLabel(
    room: Classroom,
    week: Int,
    dayOfWeek: Int,
    selected: IntRange,
    periodTimes: List<String>,
): String {
    val busyHere = room.busy.filter { it.dayOfWeek == dayOfWeek && it.occursOn(week) }
    var last = selected.last
    while (last < 12 && busyHere.none { it.startPeriod <= last + 1 && it.endPeriod >= last + 1 }) last++
    val endLabel = periodTimes.getOrNull(last - 1)
        ?.let { PeriodMapper.parsePeriod(it)?.second?.let(PeriodMapper::format) }
    val head = if (last == selected.last) "空闲仅到第 $last 节" else "可连自习到第 $last 节"
    return if (endLabel == null) head else "$head（$endLabel 下课）"
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
