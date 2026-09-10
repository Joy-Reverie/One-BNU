package io.github.joyreverie.onebnu.ui.classroom

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.LoadingBox
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
                s.loadingOptions -> LoadingBox("正在加载楼房信息…")
                // 楼房都没拿到，筛选面板无从展示，整页给出错误与重试
                s.buildings.isEmpty() && s.error != null -> ErrorBox(s.error!!) { vm.retry() }
                else -> {
                    FilterPanel(s, vm)
                    Divider()

                    Box(Modifier.fillMaxSize()) {
                        when {
                            s.loading -> LoadingBox("正在拉取整栋楼的课表…")
                            s.error != null -> ErrorBox(s.error!!) { vm.retry() }
                            !s.queried -> EmptyBox(
                                "选择楼房与时间后点击查询",
                                hint = "结果由教室课表取补集算出，只反映排课占用，不含临时借用",
                            )
                            s.emptyReason != null -> EmptyBox(s.emptyReason!!, onRetry = { vm.query() })
                            else -> ResultList(s)
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

@Composable
private fun ResultList(s: ClassroomUiState) {
    val free = s.freeRooms
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = LocalScreenInfo.current.listPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info, null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "第 ${s.week} 周 周${DAYS[s.dayOfWeek - 1]}（${s.date.format(DATE_FMT)}）" +
                            "第 ${s.startPeriod}-${s.endPeriod} 节：" +
                            "${s.building?.name ?: ""} 共 ${s.rooms.size} 间教室，其中 ${free.size} 间空闲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

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

        items(free, key = { it.name }) { room -> RoomCard(room) }
    }
}

@Composable
private fun RoomCard(room: Classroom) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
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
                        "本周共 ${room.busy.size} 次排课",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = {}, label = { Text("空闲") })
        }
    }
}
