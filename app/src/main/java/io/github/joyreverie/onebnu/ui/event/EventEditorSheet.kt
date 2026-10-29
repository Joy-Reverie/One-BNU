package io.github.joyreverie.onebnu.ui.event

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.ui.components.MorphButton
import io.github.joyreverie.onebnu.ui.components.TimePickerDialog
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay

private const val DAY_MILLIS = 86_400_000L

/** 保存后按钮打勾停留的时长，粒子迸完正好收起弹层。 */
private const val SAVE_HOLD_MS = 700L

/**
 * 添加 / 编辑个人日程的底部弹层：事件、日期、开始与结束时间、重复规则、地点、备注。
 * [initial] 为 null 时是新建，否则是编辑（多一个删除按钮；重复日程改的是整个系列）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    initial: PersonalEvent?,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (PersonalEvent) -> Unit,
    onDelete: ((PersonalEvent) -> Unit)? = null,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var date by remember { mutableStateOf(initial?.date ?: defaultDate) }
    var start by remember { mutableStateOf(initial?.start ?: LocalTime.of(8, 0)) }
    var end by remember { mutableStateOf(initial?.end ?: LocalTime.of(10, 0)) }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var repeatUntil by remember { mutableStateOf(initial?.repeatUntil) }
    var pickingDate by remember { mutableStateOf(false) }
    // 0 = 改开始时间，1 = 改结束时间
    var pickingTime by remember { mutableStateOf<Int?>(null) }
    var editingRepeat by remember { mutableStateOf(false) }
    // 点了保存：按钮先打勾迸粒子，停一下再交出去关弹层
    var saving by remember { mutableStateOf<PersonalEvent?>(null) }
    LaunchedEffect(saving) {
        saving?.let {
            delay(SAVE_HOLD_MS)
            onSave(it)
        }
    }

    val timeOk = end.isAfter(start)
    // 重复结束日期早于开始日期的日程永远不会发生，不让它存下来
    val repeatOk = repeatDays.isEmpty() || repeatUntil?.isBefore(date) != true
    val valid = title.isNotBlank() && timeOk && repeatOk
    val preview = PersonalEvent("", "", date, start, end, repeatDays = repeatDays, repeatUntil = repeatUntil)

    val accents = LocalAccents.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 底色比分组卡片深一档，白色的分组才浮得起来
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (initial == null) "添加日程" else "编辑日程",
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            // 事件名单独一组，字号放大，像一张卡片的标题
            Group {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(width = 4.dp, height = 24.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                    Spacer(Modifier.width(12.dp))
                    InlineField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "事件",
                        style = MaterialTheme.typography.titleLarge,
                        singleLine = true,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Group {
                GroupRow(Icons.Outlined.CalendarMonth, 2, if (repeatDays.isEmpty()) "日期" else "开始日期", onClick = { pickingDate = true }) {
                    RowValue("${date.monthValue}月${date.dayOfMonth}日 周${PersonalEvent.DAY_NAMES[date.dayOfWeek.value - 1]}")
                }
                GroupDivider()
                GroupRow(Icons.Outlined.Schedule, 1, "时间") {
                    TimePill(start.format(PersonalEvent.HM), error = false) { pickingTime = 0 }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.padding(horizontal = 6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    TimePill(end.format(PersonalEvent.HM), error = !timeOk) { pickingTime = 1 }
                }
                GroupDivider()
                GroupRow(Icons.Outlined.Repeat, 0, "重复", onClick = { editingRepeat = true }) {
                    RowValue(preview.repeatLabel)
                }
            }
            if (!timeOk || !repeatOk) {
                Text(
                    if (!timeOk) "结束时间需要晚于开始时间" else "重复结束日期不能早于开始日期",
                    Modifier.padding(start = 16.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))

            Group {
                GroupRow(Icons.Outlined.Place, 4, null) {
                    InlineField(location, { location = it }, "地点", singleLine = true)
                }
                GroupDivider()
                GroupRow(Icons.AutoMirrored.Outlined.Notes, 3, null, top = true) {
                    InlineField(note, { note = it }, "备注", singleLine = false)
                }
            }
            if (initial != null && initial.repeats) {
                Text(
                    "这是重复日程，修改或删除会应用到每一次。",
                    Modifier.padding(start = 16.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null && onDelete != null) {
                    Surface(
                        onClick = { onDelete(initial) },
                        enabled = saving == null,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                MorphButton(
                    text = "保存",
                    onClick = {
                        saving = PersonalEvent(
                            id = initial?.id ?: PersonalEvent.newId(),
                            title = title.trim(),
                            date = date,
                            start = start,
                            end = end,
                            location = location.trim(),
                            note = note.trim(),
                            repeatDays = repeatDays,
                            repeatUntil = if (repeatDays.isEmpty()) null else repeatUntil,
                        )
                    },
                    background = if (valid) accents.heroGradient
                    else SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    contentColor = if (valid) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    done = saving != null,
                    enabled = valid,
                    height = 48.dp,
                    corner = 24.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (pickingDate) {
        DatePickerDialogFor(
            initial = date,
            onDismiss = { pickingDate = false },
            onPick = {
                date = it
                pickingDate = false
            },
        )
    }

    pickingTime?.let { which ->
        TimePickerDialog(
            title = if (which == 0) "开始时间" else "结束时间",
            initial = if (which == 0) start else end,
            onDismiss = { pickingTime = null },
            onConfirm = { t ->
                if (which == 0) {
                    start = t
                    // 开始时间挪到结束之后时，把结束顺延一小时，省得再点一次
                    if (!end.isAfter(t)) end = if (t.hour >= 23) LocalTime.of(23, 59) else t.plusHours(1)
                } else {
                    end = t
                }
                pickingTime = null
            },
        )
    }

    if (editingRepeat) {
        RepeatDialog(
            date = date,
            days = repeatDays,
            until = repeatUntil,
            onDismiss = { editingRepeat = false },
            onConfirm = { d, u ->
                repeatDays = d
                repeatUntil = u
                editingRepeat = false
            },
        )
    }
}

/** 重复规则：预设（每天 / 每周 / 工作日 / 周末）或自己勾星期几，可选结束日期。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatDialog(
    date: LocalDate,
    days: Set<Int>,
    until: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>, LocalDate?) -> Unit,
) {
    var selected by remember { mutableStateOf(days) }
    var endDate by remember { mutableStateOf(until) }
    var pickingEnd by remember { mutableStateOf(false) }
    val weekly = setOf(date.dayOfWeek.value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重复") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "不重复" to emptySet(),
                        "每天" to PersonalEvent.EVERY_DAY,
                        "每周${PersonalEvent.DAY_NAMES[date.dayOfWeek.value - 1]}" to weekly,
                        "工作日" to PersonalEvent.WEEKDAYS,
                        "周末" to PersonalEvent.WEEKEND,
                    ).forEach { (label, set) ->
                        FilterChip(selected = selected == set, onClick = { selected = set }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("或者选星期几", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..7).forEach { d ->
                        val on = d in selected
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { selected = if (on) selected - d else selected + d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                PersonalEvent.DAY_NAMES[d - 1],
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { pickingEnd = true }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("结束日期", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            endDate?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "不限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (endDate != null) {
                            TextButton(onClick = { endDate = null }) { Text("清除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected, endDate) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickingEnd) {
        DatePickerDialogFor(
            initial = endDate ?: date.plusWeeks(16),
            onDismiss = { pickingEnd = false },
            onPick = {
                endDate = if (it.isBefore(date)) date else it
                pickingEnd = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogFor(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * DAY_MILLIS)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis?.let { LocalDate.ofEpochDay(it.floorDiv(DAY_MILLIS)) }
                    if (picked != null) onPick(picked) else onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state = state) }
}

/** 一组设置项的白色圆角底板，和应用里的卡片同一套描边。 */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Shape.card),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalAccents.current.hairline),
    ) { Column(content = content) }
}

/** 组内分隔线，从图标右侧起画，和 iOS 式分组列表一样不切断图标列。 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * 组里的一行：彩色小图标块 + 名称 + 右侧内容。[tint] 是色板下标，与首页服务图标同一套颜色。
 * [label] 为空时右侧内容占满整行（地点、备注直接在行里输入）；[top] 让图标贴顶，供多行输入用。
 */
@Composable
private fun GroupRow(
    icon: ImageVector,
    tint: Int,
    label: String?,
    onClick: (() -> Unit)? = null,
    top: Boolean = false,
    trailing: @Composable RowScope.() -> Unit,
) {
    val accents = LocalAccents.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accents.courseColors[tint % accents.courseColors.size]),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = accents.courseAccents[tint % accents.courseAccents.size])
        }
        Spacer(Modifier.width(14.dp))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        trailing()
    }
}

/** 行右侧的取值 + 小箭头，表示点开可改。 */
@Composable
private fun RowValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        null,
        Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
}

/** 起止时间各一枚胶囊，点开滚轮；结束早于开始时胶囊变红。 */
@Composable
private fun TimePill(text: String, error: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 无边框的行内输入框，空着时显示灰色占位字。 */
@Composable
private fun RowScope.InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    singleLine: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f).padding(top = if (singleLine) 0.dp else 5.dp),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 4,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.outline)
                inner()
            }
        },
    )
}
