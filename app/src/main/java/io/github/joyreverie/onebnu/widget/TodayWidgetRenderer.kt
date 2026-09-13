package io.github.joyreverie.onebnu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.joyreverie.onebnu.MainActivity
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.ThemeMode
import io.github.joyreverie.onebnu.core.store.ColorTheme
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

    /** RemoteViews 会按启动器的系统夜间资源解析 XML，因此手动主题必须显式覆盖。 */
    private data class Palette(
        val backgroundColor: Int,
        val heroColor: Int,
        val pillColor: Int,
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val onHero: Int,
        val onHeroDim: Int,
        val courseColors: IntArray,
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
        useOfficialCalendar = true,
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
        val palette = palette(context)
        if (widthDp < SMALL_BELOW_DP) return renderSmall(context, base, widthDp, today, now, palette)
        val model = TodayWidgetModel.build(input(base, heightDp, today, now))

        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        applyLargePalette(rv, palette)
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
    private fun renderSmall(
        context: Context,
        base: Base,
        widthDp: Int,
        today: LocalDate,
        now: LocalTime,
        palette: Palette,
    ): RemoteViews {
        val m = TodayWidgetModel.buildSmall(input(base, 10_000, today, now))
        val rv = RemoteViews(context.packageName, R.layout.widget_today_small)
        applySmallPalette(rv, palette)
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
            val finished = focus.status == TodayWidgetModel.Status.FINISHED
            rv.setViewVisibility(R.id.widget_empty, View.GONE)
            rv.setViewVisibility(R.id.small_body, View.VISIBLE)
            rv.setTextViewText(R.id.small_start, focus.start)
            rv.setTextColor(R.id.small_start, if (finished) palette.secondary else palette.courseColors[focus.colorIndex % palette.courseColors.size])
            rv.setTextViewText(R.id.small_end, "– ${focus.end}")
            rv.setTextViewText(R.id.small_name, focus.name)
            rv.setTextColor(
                R.id.small_name,
                when (focus.status) {
                    TodayWidgetModel.Status.FINISHED -> palette.secondary
                    TodayWidgetModel.Status.ONGOING -> palette.accent
                    TodayWidgetModel.Status.UPCOMING -> palette.primary
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

    private fun rowView(context: Context, r: TodayWidgetModel.Row, palette: Palette): RemoteViews {
        val finished = r.status == TodayWidgetModel.Status.FINISHED
        val ongoing = r.status == TodayWidgetModel.Status.ONGOING
        val emphasis = when {
            finished -> palette.secondary
            ongoing -> palette.accent
            else -> palette.primary
        }

        val rv = RemoteViews(context.packageName, R.layout.widget_row)
        rv.setTextViewText(R.id.row_start, r.start)
        rv.setTextColor(R.id.row_start, emphasis)
        rv.setTextViewText(R.id.row_end, r.end)
        rv.setTextViewText(R.id.row_name, r.name)
        rv.setTextColor(R.id.row_name, emphasis)
        rv.setTextViewText(R.id.row_detail, if (ongoing) "进行中 · ${r.detail}" else r.detail)
        // 课程色条：颜色与应用内课表一致，已结束的淡一些
        rv.setInt(R.id.row_bar, "setColorFilter", palette.courseColors[r.colorIndex % palette.courseColors.size])
        rv.setInt(R.id.row_bar, "setImageAlpha", if (finished) 90 else 255)
        return rv
    }

    private fun applyLargePalette(rv: RemoteViews, p: Palette) {
        applyPaletteBackgrounds(rv, p)
        rv.setTextColor(R.id.widget_date, p.onHero)
        rv.setTextColor(R.id.widget_weekday, p.onHero)
        rv.setTextColor(R.id.widget_week, p.onHero)
        rv.setTextColor(R.id.widget_count, p.onHeroDim)
        rv.setTextColor(R.id.widget_empty, p.secondary)
        rv.setTextColor(R.id.widget_footer, p.secondary)
    }

    private fun applySmallPalette(rv: RemoteViews, p: Palette) {
        applyPaletteBackgrounds(rv, p)
        rv.setTextColor(R.id.widget_date, p.onHero)
        rv.setTextColor(R.id.widget_weekday, p.onHero)
        rv.setTextColor(R.id.widget_count, p.onHeroDim)
        rv.setTextColor(R.id.widget_empty, p.secondary)
        rv.setTextColor(R.id.widget_footer, p.secondary)
        rv.setTextColor(R.id.small_end, p.secondary)
        rv.setTextColor(R.id.small_name, p.primary)
        rv.setTextColor(R.id.small_detail, p.secondary)
    }

    private fun applyPaletteBackgrounds(rv: RemoteViews, p: Palette) {
        // Tint the existing rounded drawables so shape, padding and clipping stay
        // launcher-compatible while the selected app color propagates to widgets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bg = ColorStateList.valueOf(p.backgroundColor)
            val hero = ColorStateList.valueOf(p.heroColor)
            val pill = ColorStateList.valueOf(p.pillColor)
            rv.setColorStateList(R.id.widget_root, "setBackgroundTintList", bg)
            rv.setColorStateList(R.id.widget_header, "setBackgroundTintList", hero)
            rv.setColorStateList(R.id.widget_weekday, "setBackgroundTintList", pill)
            rv.setColorStateList(R.id.widget_week, "setBackgroundTintList", pill)
        } else {
            rv.setInt(R.id.widget_root, "setBackgroundColor", p.backgroundColor)
            rv.setInt(R.id.widget_header, "setBackgroundColor", p.heroColor)
            rv.setInt(R.id.widget_weekday, "setBackgroundColor", p.pillColor)
            rv.setInt(R.id.widget_week, "setBackgroundColor", p.pillColor)
        }
    }

    private fun palette(context: Context): Palette {
        val mode = runCatching { ServiceLocator.settings.themeMode.value }.getOrDefault(ThemeMode.SYSTEM)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val theme = runCatching { ServiceLocator.settings.colorTheme }.getOrDefault(ColorTheme.INDIGO)
        val themePalette = theme.palette(dark)
        return Palette(
            backgroundColor = themePalette.widgetBackground,
            heroColor = themePalette.heroCenter,
            pillColor = themePalette.widgetPill,
            primary = themePalette.primary,
            secondary = if (dark) themePalette.secondary else 0xFF767C89.toInt(),
            accent = themePalette.secondary,
            onHero = if (dark) 0xFFF5F7FB.toInt() else 0xFFFFFFFF.toInt(),
            onHeroDim = if (dark) 0xFFD0D7E3.toInt() else 0xFFDCE6F8.toInt(),
            courseColors = themePalette.courseColors,
        )
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
