package io.github.joyreverie.onebnu.core.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.joyreverie.onebnu.MainActivity
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒：每节课（和日程）开始前 N 分钟发一条通知。
 *
 * 实现要点：
 *  - 用 AlarmManager 的精确闹钟一次只排「下一次」，响了再排下一次；课表、日程、设置一变就重排。
 *    应用被系统清理后闹钟仍会拉起我们的广播接收器；开机、应用更新、改系统时间后也会重排。
 *  - 真正拦住提醒的是国产系统的后台限制（省电策略 / 自启动），所以「我的」页给了
 *    「允许后台运行」入口（忽略电池优化），并提示去系统设置放开自启动。
 *  - 通知只有两行：课名 + 「N 分钟后开始 · 教室」，没有多余小字；高优先级渠道保证横幅弹出。
 */
object ClassReminder {

    const val CHANNEL_ID = "class_reminder"
    const val ACTION_ALARM = "io.github.joyreverie.onebnu.reminder.ALARM"
    private const val EXTRA_START = "start_epoch"
    private const val REQUEST_ALARM = 2001
    private const val REQUEST_OPEN = 2002
    private const val LOOKAHEAD_DAYS = 8
    private const val NOTIFICATION_BASE_ID = 3000

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "每节课开始前提前提醒"
            enableVibration(true)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** 能否用精确闹钟（Android 12 起需要权限；我们声明了 USE_EXACT_ALARM，Android 13+ 自动获得）。 */
    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** 所有待提醒事项（今天起 8 天）。 */
    fun upcoming(context: Context, now: LocalDateTime = LocalDateTime.now()): List<ReminderItem> {
        val schedule = ServiceLocator.scheduleCache.load()?.schedule
        val events = ServiceLocator.events.all()
        return ReminderPlanner.items(schedule, events, now.toLocalDate(), LOOKAHEAD_DAYS, Settings.PERIOD_TIMES)
    }

    /** 下一次提醒的说明，如「周五 07:50 · 高级算法设计」；没有则为 null。 */
    fun nextDescription(context: Context): String? {
        val settings = ServiceLocator.settings
        if (!settings.remindersEnabled) return null
        val now = LocalDateTime.now()
        val (at, items) = ReminderPlanner.next(upcoming(context, now), now, settings.reminderLeadMinutes) ?: return null
        val day = when (at.toLocalDate()) {
            now.toLocalDate() -> "今天"
            now.toLocalDate().plusDays(1) -> "明天"
            else -> "周" + listOf("一", "二", "三", "四", "五", "六", "日")[at.dayOfWeek.value - 1]
        }
        return "$day ${at.format(TIME_FMT)} · ${items.joinToString("、") { it.title }}"
    }

    /** 按当前课表 / 日程 / 设置重排下一次闹钟；关闭提醒时取消。 */
    fun reschedule(context: Context) {
        val settings = ServiceLocator.settings
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        if (!settings.remindersEnabled) {
            am.cancel(pi)
            return
        }
        val now = LocalDateTime.now()
        val next = ReminderPlanner.next(upcoming(context, now), now, settings.reminderLeadMinutes)
        if (next == null) {
            am.cancel(pi)
            return
        }
        val (fireAt, items) = next
        val start = items.first().start
        val fireMillis = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val startMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra(EXTRA_START, startMillis)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMillis, pending)
            } else {
                // 拿不到精确闹钟权限时退回非精确闹钟，可能晚几分钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMillis, pending)
            }
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_ALARM,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 闹钟响了：给这一刻开始的每个事项发一条通知，然后排下一次。 */
    fun onAlarm(context: Context, intent: Intent) {
        val settings = ServiceLocator.settings
        if (settings.remindersEnabled) {
            val startMillis = intent.getLongExtra(EXTRA_START, 0L)
            if (startMillis > 0) {
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZoneId.systemDefault())
                val items = ReminderPlanner.startingAt(upcoming(context, start.minusDays(1)), start)
                items.forEachIndexed { i, item -> notify(context, item, settings.reminderLeadMinutes, NOTIFICATION_BASE_ID + i) }
            }
        }
        reschedule(context)
    }

    /** 给用户看一眼横幅长什么样。 */
    fun showTest(context: Context) {
        val now = LocalDateTime.now()
        val lead = ServiceLocator.settings.reminderLeadMinutes
        notify(
            context,
            ReminderItem(now.plusMinutes(lead.toLong()), now.plusMinutes(lead + 95L), "高等数学（一）", "教七楼 201", isEvent = false),
            lead,
            NOTIFICATION_BASE_ID + 99,
        )
    }

    private fun notify(context: Context, item: ReminderItem, leadMinutes: Int, id: Int) {
        if (!notificationsAllowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = listOf(
            "$leadMinutes 分钟后开始",
            item.location.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setColor(0xFF1B3C6E.toInt())
            .setContentTitle(item.title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (item.isEvent) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

/** 闹钟到点。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ClassReminder.ACTION_ALARM) ClassReminder.onAlarm(context, intent)
    }
}

/** 开机、应用更新、系统时间变化后闹钟会丢，这里重排。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> ClassReminder.reschedule(context)
        }
    }
}
