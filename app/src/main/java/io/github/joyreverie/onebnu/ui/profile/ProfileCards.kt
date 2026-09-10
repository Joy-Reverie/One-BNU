package io.github.joyreverie.onebnu.ui.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.notify.ClassReminder
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

/** 「我的」页：上课提醒开关、提前时间、后台运行权限。 */
@Composable
fun ReminderCard() {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    var enabled by remember { mutableStateOf(settings.remindersEnabled) }
    var lead by remember { mutableIntStateOf(settings.reminderLeadMinutes) }
    var editingLead by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf(ClassReminder.nextDescription(context)) }
    var batteryOk by remember { mutableStateOf(ClassReminder.ignoringBatteryOptimizations(context)) }
    var exactOk by remember { mutableStateOf(ClassReminder.canScheduleExact(context)) }
    var notifyOk by remember { mutableStateOf(ClassReminder.notificationsAllowed(context)) }

    // 从系统设置页回来时刷新各项权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = ClassReminder.ignoringBatteryOptimizations(context)
                exactOk = ClassReminder.canScheduleExact(context)
                notifyOk = ClassReminder.notificationsAllowed(context)
                next = ClassReminder.nextDescription(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun apply(on: Boolean) {
        settings.remindersEnabled = on
        enabled = on
        ClassReminder.reschedule(context)
        next = ClassReminder.nextDescription(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyOk = granted
        if (granted) apply(true) else Toast.makeText(context, "需要允许通知才能提醒上课", Toast.LENGTH_LONG).show()
    }

    SectionCard("上课提醒") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("上课前提醒", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        !enabled -> "每节课和日程开始前发一条通知"
                        next != null -> "下一次：$next"
                        else -> "近期没有课程或日程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on) {
                        apply(false)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifyOk) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        apply(true)
                    }
                },
            )
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

        if (enabled) {
            // 已经放行就不再占地方：这几行只在系统真的会拦截提醒时出现，授权后自动消失
            if (!batteryOk) {
                Divider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("后台运行", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "未忽略电池优化，通知可能不按时。建议允许，并把省电策略设为「无限制」、允许自启动",
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
