package io.github.joyreverie.onebnu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.joyreverie.onebnu.MainActivity
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import java.time.LocalDate
import java.time.LocalTime

/**
 * 把 [TodayWidgetModel] 画成 RemoteViews。
 *
 * Android 12 起启动器会把它当前所有可能的尺寸一起给过来，这里每个尺寸各画一份，
 * 旋转、拉伸时由启动器就地切换，不必等我们下一次更新；更早的系统只按竖屏尺寸画一份。
 */
object TodayWidgetRenderer {

    /** 与尺寸无关的输入，一次读出来给各尺寸复用。 */
    data class Base(
        val schedule: Schedule?,
        val hasCredentials: Boolean,
        val refreshing: Boolean,
        val lastError: String?,
        val events: List<PersonalEvent> = emptyList(),
    )

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 110

    /** 窄于这个宽度（dp）用 2×2 小版式：只放当前或下一节。 */
    const val SMALL_BELOW_DP = 200

    fun build(context: Context, options: Bundle): RemoteViews {
        val base = loadBase(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(sizes.associateWith { render(context, base, it.width.toInt(), it.height.toInt()) })
            }
        }
        // Android 11 及以前：MIN_WIDTH × MAX_HEIGHT 是竖屏下的尺寸
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        return render(context, base, w, h)
    }

    private fun loadBase(context: Context): Base {
        val state = WidgetState(context, ServiceLocator.activeCampus)
        return Base(
            schedule = ServiceLocator.scheduleCache.load()?.schedule,
            hasCredentials = ServiceLocator.secure.hasCredentials,
            refreshing = state.refreshing,
            lastError = state.lastError,
            events = ServiceLocator.events.on(LocalDate.now()),
        )
    }

    private fun input(base: Base, heightDp: Int, today: LocalDate, now: LocalTime) = TodayWidgetModel.Input(
        schedule = base.schedule,
        events = base.events.filter { it.occursOn(today) },
        hasCredentials = base.hasCredentials,
        refreshing = base.refreshing,
        lastError = base.lastError,
        today = today,
        now = now,
        periodTimes = ServiceLocator.settings.periodTimes,
        useOfficialCalendar = ServiceLocator.activeCampus == Campus.BEIJING,
        heightDp = heightDp,
    )

    fun render(
        context: Context,
        base: Base,
        widthDp: Int,
        heightDp: Int,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(),
    ): RemoteViews {
        if (widthDp < SMALL_BELOW_DP) return renderSmall(context, base, widthDp, today, now)
        val model = TodayWidgetModel.build(input(base, heightDp, today, now))

        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        rv.setTextViewText(R.id.widget_date, model.dateLabel)
        rv.setTextViewText(R.id.widget_weekday, model.weekdayLabel)
        rv.setTextViewText(R.id.widget_week, model.weekLabel)
        rv.setViewVisibility(R.id.widget_weekday, View.VISIBLE)
        rv.setViewVisibility(R.id.widget_week, View.VISIBLE)
        rv.setTextViewText(R.id.widget_count, model.countLabel)

        rv.removeAllViews(R.id.widget_rows)
        if (model.message != null) {
            rv.setViewVisibility(R.id.widget_rows, View.GONE)
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            rv.setTextViewText(R.id.widget_empty, model.message)
        } else {
            rv.setViewVisibility(R.id.widget_empty, View.GONE)
            rv.setViewVisibility(R.id.widget_rows, View.VISIBLE)
            val palette = coursePalette(context)
            model.rows.forEach { rv.addView(R.id.widget_rows, rowView(context, it, palette)) }
        }

        if (model.footer != null) {
            rv.setViewVisibility(R.id.widget_footer, View.VISIBLE)
            rv.setTextViewText(R.id.widget_footer, model.footer)
        } else {
            rv.setViewVisibility(R.id.widget_footer, View.GONE)
        }

        rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        rv.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
        return rv
    }

    /** 2×2：色带只放日期与星期，正文是当前 / 下一节的时间、课名、教室。 */
    private fun renderSmall(context: Context, base: Base, widthDp: Int, today: LocalDate, now: LocalTime): RemoteViews {
        val m = TodayWidgetModel.buildSmall(input(base, 10_000, today, now))
        val rv = RemoteViews(context.packageName, R.layout.widget_today_small)
        rv.setTextViewText(R.id.widget_date, m.dateLabel)
        rv.setTextViewText(R.id.widget_weekday, m.weekdayLabel)
        rv.setViewVisibility(R.id.widget_weekday, View.VISIBLE)
        // 小格启动器上 2×2 只有 150dp 宽，日期 + 星期之后放不下计数，与其截断不如不显示
        rv.setTextViewText(R.id.widget_count, m.countLabel)
        rv.setViewVisibility(R.id.widget_count, if (widthDp < 175) View.GONE else View.VISIBLE)

        val focus = m.focus
        if (focus == null) {
            rv.setViewVisibility(R.id.small_body, View.GONE)
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            rv.setTextViewText(R.id.widget_empty, m.message ?: "")
        } else {
            val primary = ContextCompat.getColor(context, R.color.widget_text_primary)
            val secondary = ContextCompat.getColor(context, R.color.widget_text_secondary)
            val accent = ContextCompat.getColor(context, R.color.widget_accent)
            val finished = focus.status == TodayWidgetModel.Status.FINISHED
            val palette = coursePalette(context)
            rv.setViewVisibility(R.id.widget_empty, View.GONE)
            rv.setViewVisibility(R.id.small_body, View.VISIBLE)
            rv.setTextViewText(R.id.small_start, focus.start)
            rv.setTextColor(R.id.small_start, if (finished) secondary else palette[focus.colorIndex % palette.size])
            rv.setTextViewText(R.id.small_end, "– ${focus.end}")
            rv.setTextViewText(R.id.small_name, focus.name)
            rv.setTextColor(
                R.id.small_name,
                when (focus.status) {
                    TodayWidgetModel.Status.FINISHED -> secondary
                    TodayWidgetModel.Status.ONGOING -> accent
                    TodayWidgetModel.Status.UPCOMING -> primary
                },
            )
            rv.setTextViewText(R.id.small_detail, focus.detail)
        }
        if (m.footer != null) {
            rv.setViewVisibility(R.id.widget_footer, View.VISIBLE)
            rv.setTextViewText(R.id.widget_footer, m.footer)
        } else {
            rv.setViewVisibility(R.id.widget_footer, View.GONE)
        }
        rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return rv
    }

    private fun rowView(context: Context, r: TodayWidgetModel.Row, palette: IntArray): RemoteViews {
        val primary = ContextCompat.getColor(context, R.color.widget_text_primary)
        val secondary = ContextCompat.getColor(context, R.color.widget_text_secondary)
        val accent = ContextCompat.getColor(context, R.color.widget_accent)
        val finished = r.status == TodayWidgetModel.Status.FINISHED
        val ongoing = r.status == TodayWidgetModel.Status.ONGOING
        val emphasis = when {
            finished -> secondary
            ongoing -> accent
            else -> primary
        }

        val rv = RemoteViews(context.packageName, R.layout.widget_row)
        rv.setTextViewText(R.id.row_start, r.start)
        rv.setTextColor(R.id.row_start, emphasis)
        rv.setTextViewText(R.id.row_end, r.end)
        rv.setTextViewText(R.id.row_name, r.name)
        rv.setTextColor(R.id.row_name, emphasis)
        rv.setTextViewText(R.id.row_detail, if (ongoing) "进行中 · ${r.detail}" else r.detail)
        // 课程色条：颜色与应用内课表一致，已结束的淡一些
        rv.setInt(R.id.row_bar, "setColorFilter", palette[r.colorIndex % palette.size])
        rv.setInt(R.id.row_bar, "setImageAlpha", if (finished) 90 else 255)
        return rv
    }

    private fun coursePalette(context: Context): IntArray {
        val ta = context.resources.obtainTypedArray(R.array.widget_course_colors)
        try {
            return IntArray(ta.length()) { ta.getColor(it, 0) }
        } finally {
            ta.recycle()
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, TodayWidgetProvider::class.java).setAction(TodayWidgetProvider.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
