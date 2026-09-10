package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 校历推算。周次一旦算错，整张课表就会整体错位，所以按学校 2026-2027 学年
 * 官方校历逐条核对。
 */
class AcademicCalendarTest {

    private fun autumn(y: Int) = Term("$y", "0", "$y-${y + 1}学年秋季学期")
    private fun spring(y: Int) = Term("$y", "1", "$y-${y + 1}学年春季学期")

    @Test
    fun `2026-2027 秋季第一周周一与官方校历一致`() {
        // 官方校历：9 月 7 日（周一）为第 1 周
        assertEquals(LocalDate.of(2026, 9, 7), AcademicCalendar.firstMonday(autumn(2026)))
    }

    @Test
    fun `春季学期落在次年 2 月注册日之后的第一个周一`() {
        // 2027 年 2 月 21 日为全体学生注册日（周日），次日开课
        assertEquals(LocalDate.of(2027, 2, 22), AcademicCalendar.firstMonday(spring(2026)))
    }

    @Test
    fun `按学期名判断季节，不依赖各系统不一的 xq 编码`() {
        assertEquals(AcademicCalendar.Season.AUTUMN, AcademicCalendar.season(autumn(2026)))
        assertEquals(AcademicCalendar.Season.SPRING, AcademicCalendar.season(spring(2026)))
        assertEquals(
            AcademicCalendar.Season.SUMMER,
            AcademicCalendar.season(Term("2026", "2", "2026-2027学年夏季学期")),
        )
    }

    // ------------------------------------------------------------------
    // 官方校历
    // ------------------------------------------------------------------

    @Test
    fun `已录入官方校历的学期以校历为准`() {
        val cal = AcademicCalendar.official(autumn(2026))
        assertNotNull(cal)
        assertEquals(LocalDate.of(2026, 9, 7), cal!!.firstMonday)
        assertEquals(23, cal.weeks)
        assertEquals("2026-2027 学年秋季学期", cal.termLabel)
        // 最后一个编号周（第 23 周）是 2027-02-08 ～ 02-14
        assertEquals(LocalDate.of(2027, 2, 14), cal.lastSunday)
        assertTrue(cal.contains(LocalDate.of(2027, 2, 14)))
        assertFalse(cal.contains(LocalDate.of(2027, 2, 15)))
    }

    @Test
    fun `未录入官方校历的学期退回惯例推算`() {
        assertNull(AcademicCalendar.official(spring(2026)))
        assertEquals(LocalDate.of(2027, 2, 22), AcademicCalendar.firstMonday(2026, AcademicCalendar.Season.SPRING))
    }

    @Test
    fun `官方校历要点落在正确的周`() {
        val cal = OfficialCalendars.AUTUMN_2026
        assertTrue(cal.eventsInWeek(3).any { it.label.contains("中秋") })
        // 国庆 10/1-7 跨第 4 周（9/28-10/4）与第 5 周（10/5-10/11）
        assertTrue(cal.eventsInWeek(4).any { it.label.contains("国庆节") })
        assertTrue(cal.eventsInWeek(5).any { it.label.contains("国庆节") })
        assertFalse(cal.eventsInWeek(6).any { it.label.contains("国庆") })
        // 1/11 学生放寒假是第 19 周周一，自此都是假期周
        assertTrue(cal.eventsInWeek(19).any { it.label.contains("寒假") })
        assertFalse(cal.isBreakWeek(18))
        assertTrue(cal.isBreakWeek(19))
        assertTrue(cal.isBreakWeek(23))
    }

    @Test
    fun `每份录入的官方校历数据自洽`() {
        // 以后每学期补录校历时，这条用例负责把手抄错误拦下来
        assertTrue(OfficialCalendars.ALL.isNotEmpty())
        assertEquals(OfficialCalendars.ALL.size, OfficialCalendars.ALL.distinctBy { it.year to it.season }.size)
        OfficialCalendars.ALL.forEach { cal ->
            assertEquals("${cal.termLabel} 第 1 周起点必须是周一", DayOfWeek.MONDAY, cal.firstMonday.dayOfWeek)
            assertTrue("${cal.termLabel} 周数异常：${cal.weeks}", cal.weeks in 15..30)
            cal.studentBreak?.let { assertTrue("${cal.termLabel} 放假日应在学期范围内", cal.contains(it)) }
            cal.events.forEach { e ->
                assertFalse("${e.label} 起止颠倒", e.end.isBefore(e.start))
                // 注册、报到等事项可落在第 1 周前或最后一周后各一周内
                assertFalse("${e.label} 早于学期太多", e.start.isBefore(cal.firstMonday.minusWeeks(1)))
                assertFalse("${e.label} 晚于学期太多", e.end.isAfter(cal.lastSunday.plusWeeks(1)))
            }
            assertEquals("${cal.termLabel} 要点应按日期排序", cal.events.sortedBy { it.start }, cal.events)
        }
    }

    // ------------------------------------------------------------------
    // 可选学期
    // ------------------------------------------------------------------

    @Test
    fun `秋季学期自当年 9 月起可选`() {
        assertFalse(AcademicCalendar.hasBegun(autumn(2026), LocalDate.of(2026, 8, 31)))
        assertTrue(AcademicCalendar.hasBegun(autumn(2026), LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `春季学期要到次年 1 月才可选`() {
        assertFalse(AcademicCalendar.hasBegun(spring(2026), LocalDate.of(2026, 12, 31)))
        assertTrue(AcademicCalendar.hasBegun(spring(2026), LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `2026 年 9 月的 2026 级学生只能看到本学期`() {
        val all = listOf(autumn(2025), spring(2025), autumn(2026), spring(2026), autumn(2027))
        val got = AcademicCalendar.selectable(all, enrollmentYear = 2026, today = LocalDate.of(2026, 9, 9))
        assertEquals(listOf("2026-2027学年秋季学期"), got.map { it.name })
    }

    @Test
    fun `进入 2027 年后春季学期出现，且最新的排在最前`() {
        val all = listOf(autumn(2026), spring(2026), autumn(2027))
        val got = AcademicCalendar.selectable(all, enrollmentYear = 2026, today = LocalDate.of(2027, 1, 5))
        assertEquals(
            listOf("2026-2027学年春季学期", "2026-2027学年秋季学期"),
            got.map { it.name },
        )
    }

    @Test
    fun `入学之前的学期不出现`() {
        val all = listOf(autumn(2024), autumn(2025), autumn(2026))
        val got = AcademicCalendar.selectable(all, enrollmentYear = 2026, today = LocalDate.of(2026, 10, 1))
        assertEquals(listOf("2026-2027学年秋季学期"), got.map { it.name })
    }

    @Test
    fun `拿不到年级时不做下限过滤，宁可多列也不漏`() {
        val all = listOf(autumn(2024), autumn(2025), autumn(2026))
        val got = AcademicCalendar.selectable(all, enrollmentYear = null, today = LocalDate.of(2026, 10, 1))
        assertEquals(3, got.size)
    }

    @Test
    fun `从年级字符串取入学年份`() {
        assertEquals(2026, AcademicCalendar.enrollmentYear("2026"))
        assertEquals(2026, AcademicCalendar.enrollmentYear("2026级"))
        assertEquals(2025, AcademicCalendar.enrollmentYear("2025级硕士研究生"))
        assertEquals(null, AcademicCalendar.enrollmentYear("研一"))
        assertEquals(null, AcademicCalendar.enrollmentYear(null))
    }

    // ------------------------------------------------------------------
    // 今天所在学期
    // ------------------------------------------------------------------

    @Test
    fun `今天所在学期`() {
        assertEquals(2026 to AcademicCalendar.Season.AUTUMN, AcademicCalendar.currentTerm(LocalDate.of(2026, 11, 3)))
        // 跨年后仍属同一个秋季学期
        assertEquals(2026 to AcademicCalendar.Season.AUTUMN, AcademicCalendar.currentTerm(LocalDate.of(2027, 1, 5)))
        // 进入春季学期
        assertEquals(2026 to AcademicCalendar.Season.SPRING, AcademicCalendar.currentTerm(LocalDate.of(2027, 3, 1)))
        // 开学前一周仍算上一学期
        assertEquals(2025 to AcademicCalendar.Season.SPRING, AcademicCalendar.currentTerm(LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `今天所在学期的起点`() {
        // 秋季学期中
        assertEquals(
            LocalDate.of(2026, 9, 7),
            AcademicCalendar.currentTermStart(LocalDate.of(2026, 11, 3)),
        )
        // 跨年后仍属同一个秋季学期
        assertEquals(
            LocalDate.of(2026, 9, 7),
            AcademicCalendar.currentTermStart(LocalDate.of(2027, 1, 5)),
        )
        // 进入春季学期
        assertEquals(
            LocalDate.of(2027, 2, 22),
            AcademicCalendar.currentTermStart(LocalDate.of(2027, 3, 1)),
        )
    }

    @Test
    fun `开学第一天是第 1 周`() {
        assertEquals(1, AcademicCalendar.currentWeek(LocalDate.of(2026, 9, 7)))
        assertEquals(1, AcademicCalendar.currentWeek(LocalDate.of(2026, 9, 13)))
        assertEquals(2, AcademicCalendar.currentWeek(LocalDate.of(2026, 9, 14)))
        // 官方校历：10 月 5 日属于第 5 周
        assertEquals(5, AcademicCalendar.currentWeek(LocalDate.of(2026, 10, 5)))
        // 官方校历：2027 年 1 月 11 日（学生放假）属于第 19 周
        assertEquals(19, AcademicCalendar.currentWeek(LocalDate.of(2027, 1, 11)))
    }

    // ------------------------------------------------------------------
    // 周次 ⇄ 日期
    // ------------------------------------------------------------------

    @Test
    fun `日期换算成周次`() {
        val start = LocalDate.of(2026, 9, 7)
        assertEquals(1, AcademicCalendar.weekOf(start, LocalDate.of(2026, 9, 7)))
        assertEquals(1, AcademicCalendar.weekOf(start, LocalDate.of(2026, 9, 13)))
        assertEquals(2, AcademicCalendar.weekOf(start, LocalDate.of(2026, 9, 14)))
        assertEquals(5, AcademicCalendar.weekOf(start, LocalDate.of(2026, 10, 10)))
        assertEquals(19, AcademicCalendar.weekOf(start, LocalDate.of(2027, 1, 11)))
        assertEquals(23, AcademicCalendar.weekOf(start, LocalDate.of(2027, 2, 14)))
        // 起点之前：前一周为 0，再往前为负，调用方据此判断「学期未开始」
        assertEquals(0, AcademicCalendar.weekOf(start, LocalDate.of(2026, 9, 6)))
        assertEquals(0, AcademicCalendar.weekOf(start, LocalDate.of(2026, 8, 31)))
        assertEquals(-1, AcademicCalendar.weekOf(start, LocalDate.of(2026, 8, 30)))
    }

    @Test
    fun `周次加星期换算成日期，并与反向换算互逆`() {
        val start = LocalDate.of(2026, 9, 7)
        assertEquals(LocalDate.of(2026, 9, 10), AcademicCalendar.dateOf(start, 1, 4))
        assertEquals(LocalDate.of(2026, 10, 5), AcademicCalendar.dateOf(start, 5, 1))
        assertEquals(LocalDate.of(2027, 2, 14), AcademicCalendar.dateOf(start, 23, 7))
        for (w in 1..23) for (d in 1..7) {
            val date = AcademicCalendar.dateOf(start, w, d)
            assertEquals(w, AcademicCalendar.weekOf(start, date))
            assertEquals(d, date.dayOfWeek.value)
        }
    }
}
