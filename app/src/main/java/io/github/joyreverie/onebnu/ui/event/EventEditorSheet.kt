package io.github.joyreverie.onebnu.ui.event

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MILLIS = 86_400_000L

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

    val timeOk = end.isAfter(start)
    val valid = title.isNotBlank() && timeOk
    val preview = PersonalEvent("", "", date, start, end, repeatDays = repeatDays, repeatUntil = repeatUntil)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(if (initial == null) "添加日程" else "编辑日程", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("事件") },
                placeholder = { Text("如：体检、组会、讲座") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldButton(
                    icon = Icons.Outlined.CalendarMonth,
                    label = if (repeatDays.isEmpty()) "日期" else "开始日期",
                    value = "${date.monthValue}月${date.dayOfMonth}日 周${PersonalEvent.DAY_NAMES[date.dayOfWeek.value - 1]}",
                    weight = 1.4f,
                ) { pickingDate = true }
                FieldButton(icon = Icons.Outlined.Schedule, label = "开始", value = start.format(PersonalEvent.HM)) { pickingTime = 0 }
                FieldButton(icon = null, label = "结束", value = end.format(PersonalEvent.HM)) { pickingTime = 1 }
            }
            if (!timeOk) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "结束时间需要晚于开始时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))

            Row {
                FieldButton(icon = Icons.Outlined.Repeat, label = "重复", value = preview.repeatLabel) { editingRepeat = true }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("地点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (initial != null && initial.repeats) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "这是重复日程，修改或删除会应用到每一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null && onDelete != null) {
                    OutlinedButton(
                        onClick = { onDelete(initial) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Button(
                    onClick = {
                        onSave(
                            PersonalEvent(
                                id = initial?.id ?: PersonalEvent.newId(),
                                title = title.trim(),
                                date = date,
                                start = start,
                                end = end,
                                location = location.trim(),
                                note = note.trim(),
                                repeatDays = repeatDays,
                                repeatUntil = if (repeatDays.isEmpty()) null else repeatUntil,
                            ),
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
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

@Composable
private fun RowScope.FieldButton(
    icon: ImageVector?,
    label: String,
    value: String,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.weight(weight).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
