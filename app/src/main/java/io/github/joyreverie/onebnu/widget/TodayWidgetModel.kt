package io.github.joyreverie.onebnu.widget

import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.ui.theme.courseColorIndex
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今日课表小组件要显示的内容，纯计算、不碰 Android API，便于单元测试。
 *
 * 版式：顶部一条品牌渐变色带放日期、星期、周次与课程数；下面每节课一行（起止时间、课名、教室·教师·节次）。
 * 高度决定能放几行；放不下时优先把已经上完的课挪出视野，让正在上和接下来的课优先可见。
 */
object TodayWidgetModel {

    enum class Status { FINISHED, ONGOING, UPCOMING }

    data class Row(
        val start: String,
        val end: String,
        val name: String,
        val location: String,
        val teacher: String,
        val periods: String,
        val status: Status,
        /** 课程配色下标，与应用内课表一致。 */
        val colorIndex: Int,
        /** 用户自己加的日程，而不是课。 */
        val isEvent: Boolean = false,
    ) {
        /** 第二行：教室 · 教师 · 节次（日程则是 日程 · 地点）。 */
        val detail: String get() = listOf(if (isEvent) "日程" else "", location, teacher, periods)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** 2×2 小尺寸：只放当前或下一节。 */
    data class Small(
        val dateLabel: String,
        val weekdayLabel: String,
        val countLabel: String,
        val focus: Row?,
        /** 如「第 2/5 节 · 还有 3 节」。 */
        val footer: String?,
        val message: String?,
    )

    data class Model(
        /** 如「9月10日」。 */
        val dateLabel: String,
        /** 如「周四」。 */
        val weekdayLabel: String,
        /** 如「第 1 周」。 */
        val weekLabel: String,
        /** 色带右侧：「3 节课」「刷新中…」或空。 */
        val countLabel: String,
        val rows: List<Row>,
        /** 列表下方的一行小字，如「1 节已结束 · 还有 2 节」。 */
        val footer: String?,
        /** 有值时代替列表显示（未登录、没有课等）。 */
        val message: String?,
    )

    data class Input(
        val schedule: Schedule?,
        /** 今天的个人日程。 */
        val events: List<PersonalEvent> = emptyList(),
        val hasCredentials: Boolean,
        val refreshing: Boolean,
        val lastError: String?,
        val today: LocalDate,
        val now: LocalTime,
        /** 12 节的作息，"HH:mm-HH:mm"。 */
        val periodTimes: List<String>,
        val heightDp: Int,
        val useOfficialCalendar: Boolean = true,
    )

    // 与 widget_today.xml / widget_row.xml 的实际尺寸对应（dp）：色带 46 + 列表上内边距 6 + 底部内边距 6
    private const val CHROME_DP = 46 + 6 + 6
    private const val ROW_DP = 38
    private const val FOOTER_DP = 14

    /**
     * 启动器常在小组件四周留 8～10dp 内边距，而报给应用的尺寸未必扣掉了它（Pixel 启动器实测如此），
     * 按报告值排满会把最后一行压在脚注下面。算行数时一律先扣掉这一截。
     */
    private const val HOST_PADDING_DP = 16

    const val COLOR_COUNT = 8

    /** 日程统一用调色板里的木铎金。 */
    const val EVENT_COLOR_INDEX = 7

    private val DAYS = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * 给定高度下能放几行课。[total] 是今天的课程数：全放得下就不占脚注的位置，
     * 放不下才给脚注留出一行。
     */
    fun capacity(heightDp: Int, total: Int): Int {
        val avail = heightDp - HOST_PADDING_DP - CHROME_DP
        val fitAll = avail / ROW_DP
        if (total <= fitAll) return total.coerceAtLeast(1)
        return ((avail - FOOTER_DP) / ROW_DP).coerceAtLeast(1)
    }

    /** 放了 [rows] 行之后，脚注那一行还放不放得下。最矮时宁可不要脚注，也不能让它压住课程。 */
    fun footerFits(heightDp: Int, rows: Int): Boolean =
        heightDp - HOST_PADDING_DP - CHROME_DP - rows * ROW_DP >= FOOTER_DP

    fun build(i: Input): Model {
        val dateLabel = "${i.today.monthValue}月${i.today.dayOfMonth}日"
        val weekdayLabel = "周${DAYS[i.today.dayOfWeek.value - 1]}"

        val schedule = i.schedule
        val termStart = schedule?.let { AcademicCalendar.firstMonday(it.term, i.useOfficialCalendar) }
            ?: AcademicCalendar.currentTermStart(i.today, i.useOfficialCalendar)
        val week = AcademicCalendar.weekOf(termStart, i.today)
        val weekLabel = if (week < 1) "开学前" else "第 $week 周"
        val refreshingLabel = if (i.refreshing) "刷新中…" else ""

        // 缓存里的学期比今天所在学期旧：换学期了，提醒刷新而不是拿旧课表硬算。
        // 缓存的是还没开学的下学期则不算过期，走下面的「学期尚未开始」。
        val (curYear, curSeason) = AcademicCalendar.currentTerm(i.today, i.useOfficialCalendar)
        val termOutdated = schedule != null &&
            AcademicCalendar.sortKey(schedule.term) < curYear * 10 + curSeason.order

        val eventRows = i.events.sortedBy { it.start }.map { eventRow(it, i.now) }
        val message: String? = when {
            eventRows.isNotEmpty() -> null   // 有日程就先把日程摆出来，课表的问题放到脚注里说
            schedule == null && !i.hasCredentials -> "打开 One BNU 登录后显示今日课表"
            schedule == null && i.refreshing -> "正在获取课表…"
            schedule == null && i.lastError != null -> "获取课表失败：${i.lastError}"
            schedule == null -> "正在获取课表…"
            termOutdated -> "学期已更换，点右上角刷新获取新课表"
            week < 1 -> "学期尚未开始，${termStart.monthValue}月${termStart.dayOfMonth}日开学"
            schedule.courses.isEmpty() -> "本学期没有选课记录"
            else -> null
        }
        if (message != null) return Model(dateLabel, weekdayLabel, weekLabel, refreshingLabel, emptyList(), null, message)

        val courseRows = if (schedule != null && !termOutdated && week >= 1) {
            schedule.slotsOn(week, i.today.dayOfWeek.value).map { (course, session) -> row(course, session, i.periodTimes, i.now) }
        } else {
            emptyList()
        }
        val rows = (courseRows + eventRows).sortedBy { it.start }
        if (rows.isEmpty()) {
            return Model(dateLabel, weekdayLabel, weekLabel, refreshingLabel, emptyList(), null, "今天没有课 ☕")
        }
        val (visible, before, after) = window(rows, capacity(i.heightDp, rows.size))
        val footer = listOfNotNull(
            before.takeIf { it > 0 }?.let { "$it 节已结束" },
            after.takeIf { it > 0 }?.let { "还有 $it 节" },
        ).joinToString(" · ").ifBlank { null }?.takeIf { footerFits(i.heightDp, visible.size) }
        val count = refreshingLabel.ifBlank { countLabel(rows) }
        return Model(dateLabel, weekdayLabel, weekLabel, count, visible, footer, null)
    }

    /** 「3 节课」「3 节课 · 1 日程」「2 日程」。 */
    private fun countLabel(rows: List<Row>): String {
        val courses = rows.count { !it.isEvent }
        val events = rows.size - courses
        return listOfNotNull(
            courses.takeIf { it > 0 }?.let { "$it 节课" },
            events.takeIf { it > 0 }?.let { "$it 日程" },
        ).joinToString(" · ")
    }

    /** 2×2 小尺寸：取第一节还没结束的课（都结束了就取最后一节）。 */
    fun buildSmall(i: Input): Small {
        val full = build(i.copy(heightDp = 10_000))
        if (full.message != null || full.rows.isEmpty()) {
            return Small(full.dateLabel, full.weekdayLabel, full.countLabel, null, null, full.message ?: "今天没有课 ☕")
        }
        val rows = full.rows
        val idx = rows.indexOfFirst { it.status != Status.FINISHED }.let { if (it < 0) rows.lastIndex else it }
        val focus = rows[idx]
        val after = rows.size - idx - 1
        // 脚注只说一件事：进行中 / 还剩几节，2×2 的宽度容不下更多
        val footer = when {
            focus.status == Status.FINISHED -> "今天的课已结束"
            focus.status == Status.ONGOING -> if (after > 0) "进行中 · 还有 $after 节" else "进行中 · 最后一节"
            after > 0 -> "今天还有 ${after + 1} 节"
            else -> "今天只有这一节"
        }
        return Small(full.dateLabel, full.weekdayLabel, full.countLabel, focus, footer, null)
    }

    private fun eventRow(e: PersonalEvent, now: LocalTime): Row = Row(
        start = e.start.format(PersonalEvent.HM),
        end = e.end.format(PersonalEvent.HM),
        name = e.title,
        location = e.location,
        teacher = "",
        periods = "",
        status = when {
            now.isAfter(e.end) -> Status.FINISHED
            now.isBefore(e.start) -> Status.UPCOMING
            else -> Status.ONGOING
        },
        colorIndex = EVENT_COLOR_INDEX,
        isEvent = true,
    )

    /** 放不下时的取舍：从第一节还没结束的课开始显示；后面不够就往前补已结束的。 */
    internal fun window(rows: List<Row>, capacity: Int): Triple<List<Row>, Int, Int> {
        if (rows.size <= capacity) return Triple(rows, 0, 0)
        val firstLive = rows.indexOfFirst { it.status != Status.FINISHED }.let { if (it < 0) rows.size else it }
        val start = minOf(firstLive, rows.size - capacity)
        return Triple(rows.subList(start, start + capacity), start, rows.size - start - capacity)
    }

    private fun row(course: Course, s: ClassSession, periodTimes: List<String>, now: LocalTime): Row {
        val start = periodTimes.getOrNull(s.startPeriod - 1)?.substringBefore('-')?.trim().orEmpty()
        val end = periodTimes.getOrNull(s.endPeriod - 1)?.substringAfter('-')?.trim().orEmpty()
        return Row(
            start = start.ifBlank { "--:--" },
            end = end.ifBlank { "--:--" },
            name = course.name,
            location = s.location,
            teacher = course.teacherLabel,
            periods = s.periodLabel,
            status = statusOf(start, end, now),
            colorIndex = courseColorIndex(course.name, COLOR_COUNT),
        )
    }

    private fun statusOf(start: String, end: String, now: LocalTime): Status {
        val s = parseTime(start) ?: return Status.UPCOMING
        val e = parseTime(end) ?: return Status.UPCOMING
        return when {
            now.isAfter(e) -> Status.FINISHED
            now.isBefore(s) -> Status.UPCOMING
            else -> Status.ONGOING
        }
    }

    private fun parseTime(t: String): LocalTime? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(t) ?: return null
        return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }
}
