package io.github.joyreverie.onebnu.ui.profile

import android.Manifest
import android.content.Context
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
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ScreenLockPortrait
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

/**
 * 「我的」页：上课 / 日程提醒的开关、提前时间、提醒方式。
 *
 * 下面的状态行只在系统**确实**会拦住提醒时出现，放行后自动消失，不会变成一行「已授权」；
 * 每行一个按钮由用户自己去系统页，进「我的」时绝不自动跳，打不开也会说一声。
 */
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
    var status by remember { mutableStateOf(ReminderStatus.read(context)) }
    // 正在等待用户授予通知权限的开关；授权回来后自动继续开启。
    var awaiting by remember { mutableStateOf<Boolean?>(null) }
    val enabled = remindClasses || remindEvents

    fun refresh() {
        status = ReminderStatus.read(context)
        next = ClassReminder.nextDescription(context)
    }

    /** 系统设置页只由按钮打开；打不开就说一声，不能点了没反应。 */
    fun openSystemPage(open: () -> Boolean) {
        if (!open()) {
            Toast.makeText(context, "没能打开系统设置，请到「设置 - 应用 - One BNU」里处理", Toast.LENGTH_LONG).show()
        }
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
        refresh()
    }

    // 从系统设置页回来时刷新各项权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
                awaiting?.let { pending ->
                    if (status.notifyOk) {
                        awaiting = null
                        apply(pending, true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 两个开关都要先有通知权限；记下是哪一个在等授权，授权回来接着开它
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val isEvent = awaiting
        awaiting = null
        refresh()
        when {
            granted && isEvent != null -> apply(isEvent, true)
            granted -> Unit
            isEvent != null -> Toast.makeText(context, "需要允许通知才能提醒", Toast.LENGTH_LONG).show()
            // 从状态行的按钮来的：系统弹窗被拒或已不再弹出，那就去设置页
            else -> openSystemPage { ClassReminder.openNotificationSettings(context) }
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSystemPage { ClassReminder.openNotificationSettings(context) }
        }
    }

    /**
     * 真正拦住提醒的只有「通知权限」这一条。
     * 悬浮通知、频道重要性属于提醒好不好看，不能拿来当开关的门槛 ——
     * 把频道重要性调成「默认」的用户以前永远开不了提醒，只会被反复甩去系统设置。
     */
    fun toggle(isEvent: Boolean, on: Boolean) {
        when {
            !on -> {
                awaiting = null
                apply(isEvent, false)
            }
            !status.notifyOk -> {
                awaiting = isEvent
                requestNotifications()
            }
            else -> {
                awaiting = null
                apply(isEvent, true)
            }
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
                    // 通知权限没开时什么都显示不出来，得说一声，不能点了没反应
                    if (!ClassReminder.showTest(context, style)) {
                        Toast.makeText(context, "通知权限未开启，提醒发不出来", Toast.LENGTH_LONG).show()
                    }
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
                        // 闹钟与通知登记方式不同，改完要重排下一次；闹钟方式还要看精确闹钟、全屏通知两项权限
                        ClassReminder.reschedule(context)
                        refresh()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ReminderStyle.entries.size),
                    label = { Text(s.label) },
                )
            }
        }

        if (enabled) {
            val alarm = style == ReminderStyle.ALARM
            // 只在系统真的会拦住提醒时出现，放行后自动消失。红底是发不出去，灰底只是效果打折。
            val rows = buildList {
                when {
                    !status.notifyOk -> add(
                        StatusItem(
                            Icons.Outlined.NotificationsOff, "通知权限未开启",
                            if (alarm) "允许后闹钟响时才会有「停止」按钮" else "允许通知后才会收到提醒",
                            error = true, action = "去开启",
                        ) { requestNotifications() },
                    )
                    !alarm && status.reminderChannelBlocked -> add(
                        StatusItem(
                            Icons.Outlined.NotificationsOff, "「上课提醒」通知已关闭", "系统里关掉了这一类通知，提醒发不出来",
                            error = true, action = "去开启",
                        ) { openSystemPage { ClassReminder.openNotificationSettings(context) } },
                    )
                    !alarm && status.reminderChannelQuiet -> add(
                        StatusItem(
                            Icons.Outlined.NotificationsPaused, "悬浮通知未开启", "提醒只进通知栏，不在屏幕顶部弹出",
                            error = false, action = "去开启",
                        ) { openSystemPage { ClassReminder.openNotificationSettings(context) } },
                    )
                    alarm && status.alarmChannelBlocked -> add(
                        StatusItem(
                            Icons.Outlined.NotificationsOff, "「上课闹钟」通知已关闭", "响铃时通知栏里不会有「停止」",
                            error = true, action = "去开启",
                        ) { openSystemPage { ClassReminder.openNotificationSettings(context, AlarmService.CHANNEL_ID) } },
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !status.exactOk) {
                    add(
                        StatusItem(
                            Icons.Outlined.AlarmOff, "闹钟和提醒权限未开启",
                            if (alarm) "闹钟无法准时响铃" else "提醒可能延后送达",
                            error = true, action = "去开启",
                        ) { openSystemPage { ClassReminder.openExactAlarmSettings(context) } },
                    )
                }
                if (alarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !status.fullScreenOk) {
                    add(
                        StatusItem(
                            Icons.Outlined.ScreenLockPortrait, "全屏通知权限未开启", "锁屏时闹钟不会全屏弹出",
                            error = false, action = "去开启",
                        ) { openSystemPage { ClassReminder.openFullScreenIntentSettings(context) } },
                    )
                }
                if (!status.batteryOk) {
                    add(
                        StatusItem(
                            Icons.Outlined.BatteryAlert, "后台运行受限", "未忽略电池优化，提醒可能被延后或拦截",
                            error = true, action = "允许",
                        ) { openSystemPage { ClassReminder.requestIgnoreBatteryOptimizations(context) } },
                    )
                }
            }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { StatusRow(it) }
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

/** 提醒卡片要看的系统状态，一次读齐；进页面、从系统设置回来、换提醒方式时重读。 */
private data class ReminderStatus(
    val notifyOk: Boolean,
    val reminderChannelBlocked: Boolean,
    val reminderChannelQuiet: Boolean,
    val alarmChannelBlocked: Boolean,
    val exactOk: Boolean,
    val fullScreenOk: Boolean,
    val batteryOk: Boolean,
) {
    companion object {
        fun read(context: Context) = ReminderStatus(
            notifyOk = ClassReminder.notificationsAllowed(context),
            reminderChannelBlocked = ClassReminder.channelBlocked(context),
            reminderChannelQuiet = ClassReminder.channelQuiet(context),
            alarmChannelBlocked = ClassReminder.channelBlocked(context, AlarmService.CHANNEL_ID),
            exactOk = ClassReminder.canScheduleExact(context),
            fullScreenOk = ClassReminder.canUseFullScreenIntent(context),
            batteryOk = ClassReminder.ignoringBatteryOptimizations(context),
        )
    }
}

/** 提醒卡里的一行状态：图标、标题、一行说明、一个按钮。 */
private class StatusItem(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val error: Boolean,
    val action: String,
    val onClick: () -> Unit,
)

@Composable
private fun StatusRow(item: StatusItem) {
    val container = if (item.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (item.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, Modifier.size(18.dp), tint = onContainer)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = onContainer)
        }
        TextButton(onClick = item.onClick) { Text(item.action) }
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
