package io.github.joyreverie.onebnu.data.model

import io.github.joyreverie.onebnu.R
import java.time.LocalDate

/**
 * 学校正式发布的一份校历。
 *
 * 教务系统不开放校历接口，学校每学期以图片形式公布一次校历。这里把图片内嵌进应用，
 * 并把第 1 周周一、周数、放假等关键信息抄录成数据，供周次推算与空闲教室按日期查询使用。
 * 界面上凡是引用到校历的地方都会明确标出学年学期，避免把上学期的校历当成本学期的。
 */
data class OfficialCalendar(
    /** 学年起始年份，2026 表示 2026-2027 学年。 */
    val year: Int,
    val season: AcademicCalendar.Season,
    /** 校历标题原文，如「2026～2027 学年第一学期校历」。 */
    val title: String,
    /** 第 1 周的周一。 */
    val firstMonday: LocalDate,
    /** 校历上标出的周数（含考试周与假期周）。 */
    val weeks: Int,
    /** 学生放假日；从这天起的周在周次表里标为假期。 */
    val studentBreak: LocalDate?,
    /** 校历图片资源（放在 drawable-nodpi，原图内嵌、可保存）。 */
    val imageRes: Int,
    /** 抄录自校历「内容」栏、与学生相关的带日期要点，按日期排序。 */
    val events: List<CalendarEvent>,
    /** 校历上没有具体日期的说明，如「元旦、春节放假安排另行通知」。 */
    val remarks: List<String> = emptyList(),
) {
    /** 学期名，如「2026-2027 学年秋季学期」，与教务学期名对应。 */
    val termLabel: String get() = "$year-${year + 1} 学年${season.label}学期"

    /** 最后一个编号周的周日。 */
    val lastSunday: LocalDate get() = firstMonday.plusWeeks(weeks.toLong()).minusDays(1)

    /** 该日期是否落在编号周范围内。 */
    fun contains(date: LocalDate): Boolean = !date.isBefore(firstMonday) && !date.isAfter(lastSunday)

    /** 第 [week] 周内发生（或跨越）的要点。 */
    fun eventsInWeek(week: Int): List<CalendarEvent> {
        val monday = firstMonday.plusWeeks((week - 1).toLong())
        val sunday = monday.plusDays(6)
        return events.filter { !it.end.isBefore(monday) && !it.start.isAfter(sunday) }
    }

    /** 第 [week] 周是否整周处于学生假期。 */
    fun isBreakWeek(week: Int): Boolean {
        val b = studentBreak ?: return false
        return !firstMonday.plusWeeks((week - 1).toLong()).isBefore(b)
    }
}

/** 校历上的一条带日期的要点；单日事件 [start] == [end]。 */
data class CalendarEvent(
    val start: LocalDate,
    val end: LocalDate,
    val label: String,
) {
    constructor(day: LocalDate, label: String) : this(day, day, label)

    val isSingleDay: Boolean get() = start == end
}

/**
 * 已录入的官方校历。
 *
 * **每学期学校发布新校历后，在这里补一条**：
 *  1. 把校历图片放到 `res/drawable-nodpi/`，命名为 `calendar_<学年起始年>_<autumn|spring>.jpg`；
 *  2. 照着已有条目抄录标题、第 1 周周一、周数、学生放假日和「内容」栏要点；
 *  3. 把新条目加进 [ALL]，跑一遍 `AcademicCalendarTest`（其中有一条自检会核对每份校历的数据自洽）。
 *
 * 没录入官方校历的学期会退回按学校惯例推算（见 [AcademicCalendar]），界面上会明确标注「推算」。
 */
object OfficialCalendars {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    /** 2026～2027 学年第一学期，党委/校长办公室编制。 */
    val AUTUMN_2026 = OfficialCalendar(
        year = 2026,
        season = AcademicCalendar.Season.AUTUMN,
        title = "2026～2027 学年第一学期校历",
        firstMonday = d(2026, 9, 7),
        weeks = 23,
        studentBreak = d(2027, 1, 11),
        imageRes = R.drawable.calendar_2026_autumn,
        events = listOf(
            CalendarEvent(d(2026, 9, 5), "本科生二、三、四年级和研究生二、三年级注册日"),
            CalendarEvent(d(2026, 9, 6), "北京校区本科生、研究生新生报到日"),
            CalendarEvent(d(2026, 9, 7), "北京校区本科生、研究生新生开学典礼"),
            CalendarEvent(d(2026, 9, 8), "校庆日"),
            CalendarEvent(d(2026, 9, 20), "国庆调休：周日上班"),
            CalendarEvent(d(2026, 9, 25), d(2026, 9, 27), "中秋节放假"),
            CalendarEvent(d(2026, 10, 1), d(2026, 10, 7), "国庆节放假调休"),
            CalendarEvent(d(2026, 10, 10), "国庆调休：周六上班"),
            CalendarEvent(d(2027, 1, 11), "学生放寒假"),
            CalendarEvent(d(2027, 2, 21), "全体学生注册日"),
        ),
        remarks = listOf(
            "元旦、春节放假安排待国务院办公厅公布 2027 年节假日安排后另行通知。",
        ),
    )

    // 注意：ALL 必须放在各条目之后声明，object 的属性按书写顺序初始化。
    /** 全部已录入的官方校历，新的排在前面。 */
    val ALL: List<OfficialCalendar> = listOf(
        AUTUMN_2026,
    )
}
