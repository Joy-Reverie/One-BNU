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
import android.util.TypedValue
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
import kotlin.math.roundToInt

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
        /** 课程色条、进度填充与小猫的颜色：课表里同一门课左侧强调条的颜色。 */
        val courseAccents: IntArray,
        /** 进度轨道还没走到的部分。 */
        val track: Int,
    )

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 110

    /** 窄于这个宽度（dp）用 2×2 小版式：只放当前或下一节。 */
    const val SMALL_BELOW_DP = 200

    /**
     * 启动器常在小组件四周留 8～10dp 内边距而报给应用的尺寸未必扣掉了它，算轨道宽度时和
     * [TodayWidgetModel] 算行数一样先扣掉这一截：宁可小猫在下课时停在离终点几 dp 的地方，也不能跑出去被裁掉。
     */
    private const val HOST_PADDING_DP = 16

    /** 大版式里轨道两侧的水平内边距（widget_today.xml 的 14 + 12）与左侧缩进（widget_row.xml 的 60）。 */
    private const val ROWS_HORIZONTAL_DP = 14 + 12
    private const val TRACK_INDENT_DP = 60

    /** 小版式正文的水平内边距（widget_today_small.xml 的 12 + 10）。 */
    private const val SMALL_HORIZONTAL_DP = 12 + 10

    /** 小猫图的宽度（dp），与 widget_track.xml 一致。 */
    private const val CAT_DP = 25f

    /** 2×2 版式矮于这个高度（dp）时正文放不下轨道（小格启动器的两格只有 140～150dp），不显示。 */
    private const val SMALL_TRACK_MIN_HEIGHT_DP = 180

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

    /**
     * 小组件下一次该重绘的时刻（毫秒）：有课正在上时是下一个整分（进度轨道上的小猫每分钟往前挪一步），
     * 否则是下一个上下课时刻；今天没有变化了就用明天零点（翻到新的一天）。
     * 系统自己的半小时唤起太粗，只靠它「进行中」会滞后最多半小时。
     */
    fun nextChangeMillis(context: Context): Long {
        val today = LocalDate.now()
        val now = LocalTime.now()
        val rows = TodayWidgetModel.build(input(loadBase(context), 10_000, today, now)).rows
        val at = TodayWidgetModel.nextTick(rows, now)?.takeIf { it.isAfter(now) }?.let { today.atTime(it) }
            ?: today.plusDays(1).atStartOfDay()
        return at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
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

    private fun input(base: Base, heightDp: Int, today: LocalDate, now: LocalTime, fontScale: Float = 1f) =
        TodayWidgetModel.Input(
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
            fontScale = fontScale,
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
        if (widthDp < SMALL_BELOW_DP) return renderSmall(context, base, widthDp, heightDp, today, now, palette)
        // 行高随系统字体一起长（widget_row.xml 是 minHeight），能放几行必须按同一倍数算
        val model = TodayWidgetModel.build(
            input(base, heightDp, today, now, context.resources.configuration.fontScale),
        )

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
            model.rows.forEach { rv.addView(R.id.widget_rows, rowView(context, it, palette, widthDp)) }
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
        heightDp: Int,
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
            val accent = palette.courseAccents[focus.colorIndex % palette.courseAccents.size]
            rv.setTextViewText(R.id.small_start, focus.start)
            rv.setTextColor(R.id.small_start, if (finished) palette.secondary else accent)
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
            val progress = focus.progress
            if (progress != null && heightDp >= SMALL_TRACK_MIN_HEIGHT_DP) {
                rv.setViewVisibility(R.id.small_track, View.VISIBLE)
                bindTrack(context, rv, progress, widthDp - HOST_PADDING_DP - SMALL_HORIZONTAL_DP, accent, palette)
            }
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

    private fun rowView(context: Context, r: TodayWidgetModel.Row, palette: Palette, widthDp: Int): RemoteViews {
        val finished = r.status == TodayWidgetModel.Status.FINISHED
        val ongoing = r.status == TodayWidgetModel.Status.ONGOING
        val emphasis = when {
            finished -> palette.secondary
            ongoing -> palette.accent
            else -> palette.primary
        }
        val accent = palette.courseAccents[r.colorIndex % palette.courseAccents.size]

        val rv = RemoteViews(context.packageName, R.layout.widget_row)
        rv.setTextViewText(R.id.row_start, r.start)
        rv.setTextColor(R.id.row_start, emphasis)
        rv.setTextViewText(R.id.row_end, r.end)
        rv.setTextViewText(R.id.row_name, r.name)
        rv.setTextColor(R.id.row_name, emphasis)
        rv.setTextViewText(R.id.row_detail, if (ongoing) "进行中 · ${r.detail}" else r.detail)
        // 课程色条：颜色与应用内课表一致，已结束的淡一些
        rv.setInt(R.id.row_bar, "setColorFilter", accent)
        rv.setInt(R.id.row_bar, "setImageAlpha", if (finished) 90 else 255)
        val progress = r.progress
        if (progress != null) {
            rv.setViewVisibility(R.id.row_track, View.VISIBLE)
            bindTrack(context, rv, progress, widthDp - HOST_PADDING_DP - ROWS_HORIZONTAL_DP - TRACK_INDENT_DP, accent, palette)
        }
        return rv
    }

    /**
     * 进行中那节课下面的进度轨道（widget_track.xml）：一只小猫沿轨道向右跑，位置是这节课已过去的比例。
     *
     * RemoteViews 不能按比例摆放视图，这里按轨道宽度 [trackDp] 算出小猫应在的位置，用它所在容器的左内边距推过去；
     * 已走过的填充同样算出宽度直接设上（Android 12 起才能改宽度，更早的系统只留底线与小猫）。
     * 小猫本体是 ProgressBar 的不确定进度动画 —— 桌面小组件里只有它会自动播放帧动画。
     */
    private fun bindTrack(
        context: Context,
        rv: RemoteViews,
        progress: Float,
        trackDp: Int,
        accent: Int,
        palette: Palette,
    ) {
        val travel = (trackDp - CAT_DP).coerceAtLeast(0f)
        val catLeftDp = travel * progress.coerceIn(0f, 1f)
        val density = context.resources.displayMetrics.density
        rv.setViewPadding(R.id.cat_slot, (catLeftDp * density).roundToInt(), 0, 0, 0)
        rv.setInt(R.id.track_line, "setColorFilter", palette.track)
        rv.setInt(R.id.track_fill, "setColorFilter", accent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 填充画到小猫身下，末端藏在它身体后面
            rv.setViewLayoutWidth(R.id.track_fill, catLeftDp + CAT_DP * 0.6f, TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewVisibility(R.id.track_fill, View.VISIBLE)
            rv.setColorStateList(R.id.cat, "setIndeterminateTintList", ColorStateList.valueOf(accent))
        }
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
        val mode = runCatching { ServiceLocator.appearance.themeMode.value }.getOrDefault(ThemeMode.SYSTEM)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val theme = runCatching { ServiceLocator.appearance.colorTheme }.getOrDefault(ColorTheme.INDIGO)
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
            // 色条要的是课表里那条深一档的强调色；浅底色（courseColors）在白底上几乎看不见
            courseAccents = themePalette.courseAccents,
            track = if (dark) 0xFF2B313C.toInt() else 0xFFE6E9F0.toInt(),
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
