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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.joyreverie.onebnu.AppVisibility
import io.github.joyreverie.onebnu.MainActivity
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.ReminderStyle
import io.github.joyreverie.onebnu.ui.notify.AlarmActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒与日程提醒：课程、日程开始前 N 分钟提醒一次，两类各自开关、共用一个提前时间。
 *
 * 实现要点：
 *  - 用 AlarmManager 的精确闹钟一次只排「下一次」，响了再排下一次；课表、日程、设置一变就重排。
 *    应用被系统清理后闹钟仍会拉起我们的广播接收器；开机、应用更新、改系统时间后也会重排。
 *  - 同一开始时刻只送一次：到点时先在 [io.github.joyreverie.onebnu.core.store.Settings] 里「查 + 记」再送达，
 *    见 [onAlarm]。
 *  - 真正拦住提醒的是系统权限与国产 ROM 的后台限制。「我的」页只在系统**确实**会拦住时才出一行状态
 *    （通知权限、频道被关、精确闹钟、全屏通知、电池优化），每行一个按钮由用户自己去系统页，从不自动跳。
 *  - 通知只有两行：课名 + 「N 分钟后开始 · 教室」，没有多余小字；高优先级渠道保证横幅弹出。
 *  - 两种送达方式（[ReminderStyle]）：通知提醒发一条普通通知；闹钟提醒改用 [AlarmManager.setAlarmClock]
 *    登记 —— 系统把它当作用户可见的闹钟，Doze 不延后、状态栏显示闹钟图标、国产 ROM 对它的拦截也最轻 ——
 *    到点由 [AlarmService] 按闹钟音量响铃，最长两分钟。
 */
object ClassReminder {

    // A new channel is required because Android permanently preserves a user's
    // sound choice on an existing channel. v3 restores the default alert sound
    // for users whose previous reminder channel had become silent.
    const val CHANNEL_ID = "class_reminder_popup_v3"
    const val ACTION_ALARM = "io.github.joyreverie.onebnu.reminder.ALARM"
    private const val CHANNEL_DESCRIPTION = "课程与日程开始前提前提醒"
    private const val EXTRA_START = "start_epoch"
    private const val REQUEST_ALARM = 2001
    private const val REQUEST_OPEN = 2002
    private const val LOOKAHEAD_DAYS = 8 // 仅用于到点时取出同一时刻的事项，不限制排程范围。
    private const val NOTIFICATION_BASE_ID = 3000

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    /** 「查 + 记 + 送」放在同一把锁里，同一开始时刻绝不会送两遍。 */
    private val deliveryLock = Any()

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Android keeps every channel forever. Remove the two pre-v3 channels
        // so users see one actionable "上课提醒" entry with the fresh sound.
        listOf("class_reminder", "class_reminder_popup_v2")
            .forEach { nm.deleteNotificationChannel(it) }
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // 名称与说明可以事后更新；声音、重要性归用户掌管，系统不让应用再改
            if (existing.description != CHANNEL_DESCRIPTION) {
                existing.description = CHANNEL_DESCRIPTION
                nm.createNotificationChannel(existing)
            }
            return
        }
        val channel = NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 220, 120, 220)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // 系统状态：每一项都读真实状态，读不到就当作正常，不按机型猜
    // ------------------------------------------------------------------

    /** 能否用精确闹钟（Android 12 起需要权限；我们声明了 USE_EXACT_ALARM，Android 13+ 自动获得）。 */
    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /**
     * 某个频道在系统里的重要性；频道还没建（第一次发通知前）或读不到时为 null，当作正常。
     * 这是「横幅弹不弹」唯一能可靠读到的信号：用户把频道调到「默认」以下横幅就不弹，调成「无」则一条都发不出。
     * 国产 ROM 自己的「悬浮通知」开关标准 API 读不到，所以不猜、不提示。
     */
    fun channelImportance(context: Context, channelId: String = CHANNEL_ID): Int? = runCatching {
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.importance
    }.getOrNull()

    /** 用户在系统里把这一类通知整个关掉了。 */
    fun channelBlocked(context: Context, channelId: String = CHANNEL_ID): Boolean =
        channelImportance(context, channelId) == NotificationManager.IMPORTANCE_NONE

    /** 频道重要性低于「高」：通知只进通知栏，不在屏幕顶部弹横幅。 */
    fun channelQuiet(context: Context, channelId: String = CHANNEL_ID): Boolean {
        val importance = channelImportance(context, channelId) ?: return false
        return importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Android 14 起「全屏通知」是单独的特殊权限；被关掉后锁屏上的闹钟页只会降级成横幅。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    // ------------------------------------------------------------------
    // 系统设置页：都只由卡片上的按钮触发；打不开返回 false 由界面提示，绝不自动跳、不重试
    // ------------------------------------------------------------------

    /** 应用通知设置（优先直达该频道），用户可恢复横幅、声音和震动。 */
    fun openNotificationSettings(context: Context, channelId: String = CHANNEL_ID): Boolean {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        return listOf(channelIntent, appIntent, detailsIntent).any { openSystemPage(context, it) }
    }

    /** Android 12 的「闹钟和提醒」权限页（13 起自动获得，用不到）。 */
    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return openSystemPage(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
    }

    /** Android 14 的「全屏通知」权限页。 */
    fun openFullScreenIntentSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return openSystemPage(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
    }

    /** 系统的「忽略电池优化」确认框。 */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean =
        openSystemPage(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun openSystemPage(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    // ------------------------------------------------------------------
    // 排程
    // ------------------------------------------------------------------

    /** 所有待提醒事项（今天起 8 天）；「上课提醒」与「日程提醒」各自关掉后就不算那一类。 */
    fun upcoming(context: Context, now: LocalDateTime = LocalDateTime.now()): List<ReminderItem> {
        val settings = ServiceLocator.settings
        val schedule = if (settings.remindClasses) ServiceLocator.scheduleCache.load()?.schedule else null
        val events = if (settings.remindEvents) ServiceLocator.events.all() else emptyList()
        return ReminderPlanner.items(
            schedule, events, now.toLocalDate(), LOOKAHEAD_DAYS, settings.periodTimes,
            useOfficialCalendar = true,
        )
    }

    /** 已经提醒过、开始时刻还没到的那些时刻。 */
    private fun delivered(): Set<LocalDateTime> =
        ServiceLocator.settings.remindedStarts()
            .map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
            .toSet()

    private fun nextReminder(now: LocalDateTime): Pair<LocalDateTime, List<ReminderItem>>? {
        val settings = ServiceLocator.settings
        return ReminderPlanner.nextOccurrence(
            schedule = if (settings.remindClasses) ServiceLocator.scheduleCache.load()?.schedule else null,
            events = if (settings.remindEvents) ServiceLocator.events.all() else emptyList(),
            now = now,
            leadMinutes = settings.reminderLeadMinutes,
            periodTimes = settings.periodTimes,
            delivered = delivered(),
        )
    }

    /** 下一次提醒的说明，如「周五 07:50 · 高级算法设计」；没有则为 null。 */
    fun nextDescription(context: Context): String? {
        val settings = ServiceLocator.settings
        if (!settings.remindersEnabled) return null
        val now = LocalDateTime.now()
        val (at, items) = nextReminder(now) ?: return null
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
        val next = nextReminder(now)
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
            when {
                // 闹钟方式走系统的「闹钟」通道：不受 Doze 与省电策略延后，状态栏会出现闹钟图标，
                // 点图标能回到应用（showIntent）。定时由系统保管，应用被清理也不影响。
                settings.reminderStyle == ReminderStyle.ALARM && canScheduleExact(context) ->
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(fireMillis, openIntent(context)), pending)
                canScheduleExact(context) ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMillis, pending)
                // 拿不到精确闹钟权限时退回非精确闹钟，可能延后送达
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireMillis, pending)
            }
        }
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_ALARM,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------
    // 送达
    // ------------------------------------------------------------------

    /**
     * 到点了：把这一刻开始的事项按当前方式送达，然后排下一次。
     *
     * 幂等：同一开始时刻只送一次。闹钟到点时进程往往是冷启动，[ServiceLocator.init] 里的 reschedule 会先跑，
     * 那时这一刻还没记成「已送达」，会被算成「该立刻提醒」再登记一次闹钟 —— 于是同一刻可能收到两条广播。
     * 所以先「查 + 记」再送，整段放在一把锁里；第二条广播查到已记过，什么都不送。
     */
    fun onAlarm(context: Context, intent: Intent) {
        val settings = ServiceLocator.settings
        val startMillis = intent.getLongExtra(EXTRA_START, 0L)
        if (settings.remindersEnabled && startMillis > 0) {
            synchronized(deliveryLock) {
                if (settings.markReminded(startMillis)) {
                    val now = LocalDateTime.now()
                    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZoneId.systemDefault())
                    // 系统可能把闹钟压后很久才送达：已结束的不送，已开始的照送、文案如实写「已开始 N 分钟」
                    val items = ReminderPlanner.due(
                        ReminderPlanner.startingAt(upcoming(context, start.minusDays(1)), start),
                        now,
                    )
                    deliver(context, items, settings.reminderStyle, key = startMillis, now = now)
                }
            }
        }
        reschedule(context)
    }

    /** 闹钟方式起不来（系统拒绝后台启动前台服务）时退回通知：宁可安静，也不能整条丢掉。 */
    private fun deliver(context: Context, items: List<ReminderItem>, style: ReminderStyle, key: Long, now: LocalDateTime) {
        if (items.isEmpty()) return
        if (style == ReminderStyle.ALARM) {
            val title = alarmTitle(items)
            val text = alarmText(items, now)
            if (AlarmService.start(context, title, text, key)) {
                // 亮屏且正在用应用时，系统只会把全屏意图降级成横幅；通知权限被关掉时连横幅都没有，
                // 「停止」就没处点了。所以应用在前台、或通知发不出去时，直接把全屏页拉起来：
                // 前台一定成功；后台在 Android 10–13 上闹钟广播后的几秒内也允许，Android 14 起会被系统
                // 静默拒绝 —— 那就只剩响铃两分钟自动停，不会再有别的循环。
                if (AppVisibility.foreground || !notificationsAllowed(context)) {
                    runCatching { context.startActivity(AlarmActivity.intent(context, title, text)) }
                }
                return
            }
        }
        items.forEachIndexed { i, item -> notify(context, item, NOTIFICATION_BASE_ID + i, now) }
    }

    private fun alarmTitle(items: List<ReminderItem>): String = items.joinToString("、") { it.title }

    private fun alarmText(items: List<ReminderItem>, now: LocalDateTime): String = listOfNotNull(
        remainingLabel(items.first().start, now),
        items.firstNotNullOfOrNull { it.location.takeIf { l -> l.isNotBlank() } },
    ).joinToString(" · ")

    /**
     * 「N 分钟后开始」按**真实剩余时间**算，而不是照抄提前时间：
     * 新加的近期日程是立刻提醒的，那时离开始往往已不足提前时间；
     * 系统把闹钟压后送达时课可能已经开始，那就如实写「已开始 N 分钟」，不能再说「即将开始」。
     */
    internal fun remainingLabel(start: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val minutes = Math.round(Duration.between(now, start).seconds / 60.0)
        return when {
            minutes >= 1 -> "$minutes 分钟后开始"
            minutes <= -1 -> "已开始 ${-minutes} 分钟"
            else -> "即将开始"
        }
    }

    /**
     * 试一下当前的提醒方式：通知就发一条横幅，闹钟就响起来。
     * 通知权限没开、什么都显示不出来时返回 false，由界面提示，不能点了没反应。
     */
    fun showTest(context: Context, style: ReminderStyle = ServiceLocator.settings.reminderStyle): Boolean {
        val now = LocalDateTime.now()
        val lead = ServiceLocator.settings.reminderLeadMinutes
        val sample = ReminderItem(
            now.plusMinutes(lead.toLong()), now.plusMinutes(lead + 95L),
            "高等数学（一）", "教七楼 201", isEvent = false,
        )
        if (style == ReminderStyle.ALARM &&
            AlarmService.start(context, sample.title, alarmText(listOf(sample), now), AlarmService.TEST_KEY)
        ) {
            return true
        }
        if (!notificationsAllowed(context)) return false
        notify(context, sample, NOTIFICATION_BASE_ID + 99, now)
        return true
    }

    private fun notify(context: Context, item: ReminderItem, id: Int, now: LocalDateTime) {
        if (!notificationsAllowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = listOfNotNull(
            remainingLabel(item.start, now),
            item.location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setColor(0xFF1B3C6E.toInt())
            .setContentTitle(item.title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
