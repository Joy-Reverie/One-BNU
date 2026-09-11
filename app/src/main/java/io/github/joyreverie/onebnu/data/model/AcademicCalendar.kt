package io.github.joyreverie.onebnu.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 校历推算。
 *
 * 教务系统没有开放校历接口。周次的依据分两层：
 *  1. **官方校历**（[OfficialCalendars]）：学校发布后抄录进来，有则一律以它为准；
 *  2. **学校惯例**：没录入的学期按惯例推算 ——
 *     秋季学期第 1 周的周一 = 9 月 1 日起的第一个周一，
 *     春季学期落在 2 月 20 日（全体学生注册日前后）之后的第一个周一。
 *     用 2026-2027 学年官方校历核对过：9 月 1 日是周二，推出 9 月 7 日为第 1 周周一，与校历一致。
 *
 * 这样每个学期都能各自算出自己的起点，不必让用户手工校准，
 * 也不会出现「切到上学期，周次还按本学期算」的错位。
 */
object AcademicCalendar {

    enum class Season(val order: Int, val label: String) {
        AUTUMN(0, "秋季"),
        SPRING(1, "春季"),
        SUMMER(2, "夏季"),
    }

    /** 学期属于哪一季。教务给的 xq 编码各系统不一，从学期名判断更稳。 */
    fun season(term: Term): Season = when {
        term.name.contains("春") -> Season.SPRING
        term.name.contains("夏") -> Season.SUMMER
        term.name.contains("秋") -> Season.AUTUMN
        // 名称异常时按 xq 兜底：0/1 => 秋/春
        term.xq == "1" -> Season.SPRING
        term.xq == "2" -> Season.SUMMER
        else -> Season.AUTUMN
    }

    /** 学年起始年份，如 2026 表示 2026-2027 学年。 */
    fun academicYear(term: Term): Int? = term.xn.toIntOrNull()

    // ------------------------------------------------------------------
    // 官方校历
    // ------------------------------------------------------------------

    /** 该学期的官方校历；尚未录入时为 null。 */
    fun official(year: Int, season: Season, useOfficial: Boolean = true): OfficialCalendar? =
        if (useOfficial) OfficialCalendars.ALL.firstOrNull { it.year == year && it.season == season } else null

    fun official(term: Term, useOfficial: Boolean = true): OfficialCalendar? =
        academicYear(term)?.let { official(it, season(term), useOfficial) }

    // ------------------------------------------------------------------
    // 学期起点
    // ------------------------------------------------------------------

    /** 该学期第 1 周的周一：有官方校历以校历为准，否则按学校惯例推算。 */
    fun firstMonday(year: Int, season: Season, useOfficial: Boolean = true): LocalDate =
        official(year, season, useOfficial)?.firstMonday ?: ruleFirstMonday(year, season)

    /** 该学期第 1 周的周一；学年编码异常时为 null。 */
    fun firstMonday(term: Term, useOfficial: Boolean = true): LocalDate? =
        academicYear(term)?.let { firstMonday(it, season(term), useOfficial) }

    private fun ruleFirstMonday(y: Int, season: Season): LocalDate = when (season) {
        Season.AUTUMN -> mondayOnOrAfter(LocalDate.of(y, 9, 1))
        Season.SPRING -> mondayOnOrAfter(LocalDate.of(y + 1, 2, 20))
        Season.SUMMER -> mondayOnOrAfter(LocalDate.of(y + 1, 7, 1))
    }

    /**
     * 该学期是否已经开始，用来把「未来的学期」挡在选择器之外 ——
     * 教务的下拉里会列出还没到的学期，选了只会是一片空白。
     */
    fun hasBegun(term: Term, today: LocalDate = LocalDate.now()): Boolean {
        val y = academicYear(term) ?: return true
        val openAt = when (season(term)) {
            // 秋季学期自当年 9 月起可查
            Season.AUTUMN -> LocalDate.of(y, 9, 1)
            // 春季学期跨年，进入次年 1 月即可查
            Season.SPRING -> LocalDate.of(y + 1, 1, 1)
            Season.SUMMER -> LocalDate.of(y + 1, 6, 1)
        }
        return !today.isBefore(openAt)
    }

    /** 排序键：先按学年，再按季节，便于取「最新的一个学期」。 */
    fun sortKey(term: Term): Int = (academicYear(term) ?: 0) * 10 + season(term).order

    /**
     * 入学之后的学期。[enrollmentYear] 取自学籍里的年级；
     * 拿不到时不做下限过滤，宁可多列也不要把该看的挡掉。
     */
    fun selectable(
        terms: List<Term>,
        enrollmentYear: Int?,
        today: LocalDate = LocalDate.now(),
    ): List<Term> = terms
        .filter { hasBegun(it, today) }
        .filter { t -> enrollmentYear == null || (academicYear(t) ?: 0) >= enrollmentYear }
        .sortedByDescending { sortKey(it) }

    /** 从年级字符串里取出入学年份，如「2026」「2026级」。 */
    fun enrollmentYear(grade: String?): Int? =
        Regex("""(19|20)\d{2}""").find(grade.orEmpty())?.value?.toIntOrNull()

    // ------------------------------------------------------------------
    // 今天所在学期
    // ------------------------------------------------------------------

    /**
     * 今天所在的学期（学年起始年, 季节）。
     * 只在秋、春之间判断 —— 夏季学期很短且不固定，暑假里按春季学期的延续处理。
     */
    fun currentTerm(today: LocalDate = LocalDate.now(), useOfficial: Boolean = true): Pair<Int, Season> {
        // 候选按起点从晚到早：今年秋季、今年春季（属上一学年）、去年秋季；取第一个已开始的
        val candidates = listOf(
            today.year to Season.AUTUMN,
            today.year - 1 to Season.SPRING,
            today.year - 1 to Season.AUTUMN,
        )
        return candidates.firstOrNull { (y, s) -> !firstMonday(y, s, useOfficial).isAfter(today) } ?: candidates.last()
    }

    /**
     * 今天所在学期第 1 周的周一。
     * 首页「第几周」、校历页、空闲教室都按它推算，不需要用户手工校准。
     */
    fun currentTermStart(today: LocalDate = LocalDate.now(), useOfficial: Boolean = true): LocalDate =
        currentTerm(today, useOfficial).let { (y, s) -> firstMonday(y, s, useOfficial) }

    /** 今天是所在学期的第几周（最小为 1）。 */
    fun currentWeek(today: LocalDate = LocalDate.now(), useOfficial: Boolean = true): Int =
        weekOf(currentTermStart(today, useOfficial), today).coerceAtLeast(1)

    // ------------------------------------------------------------------
    // 周次 ⇄ 日期
    // ------------------------------------------------------------------

    /** [date] 是自 [termStart] 起的第几周：起点当周为 1，起点前一周为 0，再往前为负。 */
    fun weekOf(termStart: LocalDate, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(termStart, date).floorDiv(7L) + 1).toInt()

    /** 第 [week] 周的周 [dayOfWeek]（1=周一 … 7=周日）对应的日期。 */
    fun dateOf(termStart: LocalDate, week: Int, dayOfWeek: Int): LocalDate =
        termStart.plusWeeks((week - 1).toLong()).plusDays((dayOfWeek - 1).toLong())

    private fun mondayOnOrAfter(d: LocalDate): LocalDate =
        d.plusDays(((DayOfWeek.MONDAY.value - d.dayOfWeek.value) + 7) % 7L)
}
