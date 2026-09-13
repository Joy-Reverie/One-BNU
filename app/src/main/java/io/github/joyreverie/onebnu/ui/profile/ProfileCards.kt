package io.github.joyreverie.onebnu.ui.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.notify.AlarmService
import io.github.joyreverie.onebnu.core.notify.ClassReminder
import io.github.joyreverie.onebnu.core.store.ReminderStyle
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.widget.MiuiShortcutPermission
import io.github.joyreverie.onebnu.widget.TodayWidgetProvider
import io.github.joyreverie.onebnu.widget.WidgetSize

/** 「我的」页：把今日课表小组件放到桌面，可选尺寸。 */
@Composable
fun WidgetPinCard() {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    // 最近一次请求过的尺寸；有值时显示「没反应就手动添加」的提示
    var requested by remember { mutableStateOf<WidgetSize?>(null) }
    var showSteps by remember { mutableStateOf<WidgetSize?>(null) }

    // MIUI 的「桌面快捷方式」权限：没开时一键添加会被静默吞掉，先把它摆在按钮上面
    var needsMiuiGrant by remember { mutableStateOf(MiuiShortcutPermission.needsGrant(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) needsMiuiGrant = MiuiShortcutPermission.needsGrant(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard("今日课表小组件") {
        if (needsMiuiGrant) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("桌面快捷方式权限", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "小米系统需先允许，否则添加到桌面没有反应",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { MiuiShortcutPermission.openSettings(context) }) { Text("去授权") }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { choosing = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Outlined.AddToHomeScreen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加到桌面")
        }
        requested?.let { size ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已请求把 ${size.label} 放到桌面，桌面应弹出确认框。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSteps = size }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("没反应？")
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("选择尺寸") },
            text = {
                Column {
                    WidgetSize.entries.forEach { size ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    choosing = false
                                    if (MiuiShortcutPermission.needsGrant(context)) {
                                        // 权限没开时先带去授权，不然桌面什么都不会弹
                                        needsMiuiGrant = true
                                        MiuiShortcutPermission.openSettings(context)
                                    } else if (TodayWidgetProvider.requestPin(context, size.provider)) {
                                        requested = size
                                    } else {
                                        showSteps = size
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                size.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(56.dp),
                            )
                            Text(size.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } },
        )
    }

    showSteps?.let { size -> ManualPinDialog(size = size, onDismiss = { showSteps = null }) }
}

/**
 * 一键添加没反应时的手动步骤。MIUI 等桌面会在没授权「桌面快捷方式」时把请求静默吞掉，
 * 所以除了步骤还给一个跳到应用信息页的入口。
 */
@Composable
private fun ManualPinDialog(size: WidgetSize, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加到桌面") },
        text = {
            Column {
                listOf(
                    "回到桌面，长按空白处（小米也可双指捏合）",
                    "点「添加小部件」「小组件」或「工具」",
                    "找到 One BNU，把「今日课表 ${size.label}」拖到桌面",
                ).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "小米手机若点「添加到桌面」没有任何反应，需要先在「应用信息 → 权限管理」里允许「桌面快捷方式」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }) { Text("打开应用信息") }
        },
    )
}

/** 「我的」页：上课 / 日程提醒的开关、提前时间、提醒方式、后台运行权限。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderCard() {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    var remindClasses by remember { mutableStateOf(settings.remindClasses) }
    var remindEvents by remember { mutableStateOf(settings.remindEvents) }
    var lead by remember { mutableIntStateOf(settings.reminderLeadMinutes) }
    var editingLead by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(settings.reminderStyle) }
    val ringing by AlarmService.ringing.collectAsState()
    var next by remember { mutableStateOf(ClassReminder.nextDescription(context)) }
    var batteryOk by remember { mutableStateOf(ClassReminder.ignoringBatteryOptimizations(context)) }
    var exactOk by remember { mutableStateOf(ClassReminder.canScheduleExact(context)) }
    var notifyOk by remember { mutableStateOf(ClassReminder.notificationsAllowed(context)) }
    var notifyReady by remember { mutableStateOf(ClassReminder.notificationsReady(context)) }
    val enabled = remindClasses || remindEvents

    // 从系统设置页回来时刷新各项权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = ClassReminder.ignoringBatteryOptimizations(context)
                exactOk = ClassReminder.canScheduleExact(context)
                notifyOk = ClassReminder.notificationsAllowed(context)
                notifyReady = ClassReminder.notificationsReady(context)
                next = ClassReminder.nextDescription(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 提醒范围一变，下一次是谁也就变了。 */
    fun apply(isEvent: Boolean, on: Boolean) {
        if (isEvent) {
            remindEvents = on
            settings.remindEvents = on
        } else {
            remindClasses = on
            settings.remindClasses = on
        }
        ClassReminder.reschedule(context)
        notifyReady = ClassReminder.notificationsReady(context)
        next = ClassReminder.nextDescription(context)
    }

    // 两个开关都要先有通知权限；记下是哪一个在等授权，授权回来接着开它
    var awaiting by remember { mutableStateOf<Boolean?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyOk = granted
        notifyReady = ClassReminder.notificationsReady(context)
        val isEvent = awaiting
        awaiting = null
        if (granted && isEvent != null) apply(isEvent, true)
        if (!granted) Toast.makeText(context, "需要允许通知才能提醒", Toast.LENGTH_LONG).show()
    }

    fun toggle(isEvent: Boolean, on: Boolean) {
        when {
            !on -> apply(isEvent, false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifyOk -> {
                awaiting = isEvent
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> apply(isEvent, true)
        }
    }

    SectionCard("提醒") {
        ReminderToggleRow(
            icon = Icons.Outlined.School,
            label = "上课提醒",
            checked = remindClasses,
            onCheckedChange = { toggle(isEvent = false, on = it) },
        )
        Divider(Modifier.padding(vertical = 4.dp))
        ReminderToggleRow(
            icon = Icons.Outlined.EditCalendar,
            label = "日程提醒",
            checked = remindEvents,
            onCheckedChange = { toggle(isEvent = true, on = it) },
        )

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Schedule, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    next?.let { "下一次 $it" } ?: "近期没有课程或日程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (!notifyReady) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("通知弹窗未开启", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "允许通知后，提醒才会在屏幕顶部弹出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    TextButton(onClick = {
                        ClassReminder.openNotificationSettings(context)
                    }) { Text("去开启") }
                }
            }
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Row(
            Modifier.fillMaxWidth().clickable { editingLead = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("提前时间", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$lead 分钟", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒方式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (ringing) {
                TextButton(onClick = { AlarmService.stop(context) }) {
                    Text("停止", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = {
                    if (style == ReminderStyle.ALARM && AlarmService.silencedByDnd(context)) {
                        Toast.makeText(context, "勿扰模式已开，闹钟只震动不响铃", Toast.LENGTH_LONG).show()
                    }
                    ClassReminder.showTest(context, style)
                }) { Text("试一下") }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReminderStyle.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = style == s,
                    onClick = {
                        style = s
                        settings.reminderStyle = s
                        // 闹钟与通知登记方式不同，改完要重排下一次
                        ClassReminder.reschedule(context)
                        next = ClassReminder.nextDescription(context)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ReminderStyle.entries.size),
                    label = { Text(s.label) },
                )
            }
        }
        if (enabled) {
            // 已经放行就不再占地方：这几行只在系统真的会拦截提醒时出现，授权后自动消失
            if (!batteryOk) {
                Divider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("后台运行", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "未忽略电池优化，提醒可能被系统延后或拦截",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }) { Text("允许") }
                }
            }
            if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Divider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("准时送达", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "系统的「闹钟和提醒」权限未开，通知可能晚几分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text("去开启") }
                }
            }
        }
    }

    if (editingLead) {
        LeadMinutesDialog(
            current = lead,
            onDismiss = { editingLead = false },
            onConfirm = {
                lead = it
                settings.reminderLeadMinutes = it
                editingLead = false
                ClassReminder.reschedule(context)
                next = ClassReminder.nextDescription(context)
            },
        )
    }
}

/**
 * 提醒卡片里的一行开关。上课与日程用同一个样式：图标 + 名称 + 开关，整行可点，
 * 图标随开关点亮 —— 两类提醒是平级的，看起来也该一样。
 */
@Composable
private fun ReminderToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeadMinutesDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.trim().toIntOrNull()?.takeIf { it in 1..120 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提前多少分钟提醒") },
        text = {
            Column {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(5, 10, 15, 20, 30, 45, 60).forEach { m ->
                        FilterChip(selected = value == m, onClick = { text = m.toString() }, label = { Text("$m") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("自定义（1～120 分钟）") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
