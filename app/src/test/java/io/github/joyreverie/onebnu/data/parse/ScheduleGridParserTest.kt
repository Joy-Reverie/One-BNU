package io.github.joyreverie.onebnu.data.parse

import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.TeacherWeeks
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.model.WeekFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按课表方式显示」把多位老师按周对到每次安排上。
 * schedule_grid.html 是按网格版式拼出的结构样例，与 schedule_list.html 是同一套虚构课表。
 */
class ScheduleGridParserTest {

    private val term = Term("2026", "0", "2026-2027学年秋季学期")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少 fixture: $name" }
            .bufferedReader(charset("UTF-8")).readText()

    private fun withGrid(): Schedule =
        Parsers.parseSchedule(ScheduleGridParser.join(fixture("schedule_list.html"), fixture("schedule_grid.html")), term)

    private fun Schedule.course(name: String) = courses.first { it.name == name }

    @Test
    fun `每一周只显示当周的老师`() {
        val ml = withGrid().course("机器学习")
        val s = ml.sessions.first()
        assertEquals(listOf("王明"), ml.teachersOn(s, 2))
        assertEquals(listOf("王明"), ml.teachersOn(s, 8))
        val later = ml.sessions.first { 9 in it.weeks }
        assertEquals(listOf("李华"), ml.teachersOn(later, 9))
        assertEquals(listOf("李华"), ml.teachersOn(later, 16))
    }

    @Test
    fun `详情里列出每位老师和他的周次`() {
        assertEquals(
            listOf(TeacherWeeks("王明", (2..8).toSet()), TeacherWeeks("李华", (9..16).toSet())),
            withGrid().course("机器学习").teacherWeeks,
        )
        assertEquals(
            listOf(TeacherWeeks("陈静", (2..9).toSet()), TeacherWeeks("赵磊", (10..16).toSet())),
            withGrid().course("计算机视觉").teacherWeeks,
        )
    }

    @Test
    fun `一格里老师与周次交替写也能两两配对`() {
        val c = withGrid().course("高级算法设计")
        val fri12 = c.sessions.filter { it.dayOfWeek == 5 && it.startPeriod == 1 }
        assertEquals(listOf("张伟"), c.teachersOn(fri12.first { 3 in it.weeks }, 3))
        assertEquals(listOf("刘洋"), c.teachersOn(fri12.first { 10 in it.weeks }, 10))
        // 周六那次只在 title 提示里写了老师
        val sat = c.sessions.first { it.dayOfWeek == 6 && it.startPeriod == 1 }
        assertEquals(listOf("刘洋"), c.teachersOn(sat, 6))
    }

    @Test
    fun `网格里没分开写的仍显示全部老师`() {
        val c = withGrid().course("高级算法设计")
        val fri34 = c.sessions.first { it.dayOfWeek == 5 && it.startPeriod == 3 && 3 in it.weeks }
        assertTrue(fri34.teachers.isEmpty())
        assertEquals(listOf("张伟", "刘洋"), c.teachersOn(fri34, 3))
        // 两位老师一起上的课本来就没有分周
        val talk = withGrid().course("人工智能前沿讲座")
        assertEquals(listOf("周杰", "吴敏"), talk.teachersOn(talk.sessions.first(), 5))
    }

    @Test
    fun `没有网格视图时和以前一样`() {
        val plain = Parsers.parseSchedule(fixture("schedule_list.html"), term)
        assertEquals(plain, Parsers.parseSchedule(ScheduleGridParser.join(fixture("schedule_list.html"), null), term))
        val ml = plain.course("机器学习")
        assertEquals(listOf("王明", "李华"), ml.teachersOn(ml.sessions.first(), 3))
        assertTrue(ml.teacherWeeks.isEmpty())
    }

    @Test
    fun `网格页是空的或乱的不影响课表`() {
        val list = fixture("schedule_list.html")
        val plain = Parsers.parseSchedule(list, term)
        assertEquals(plain, Parsers.parseSchedule(ScheduleGridParser.join(list, "<html><body>系统繁忙</body></html>"), term))
        assertEquals(plain, Parsers.parseSchedule(ScheduleGridParser.join(list, "not html <<<"), term))
    }

    @Test
    fun `拼接与拆开互逆`() {
        val (a, b) = ScheduleGridParser.split(ScheduleGridParser.join("LIST", "GRID"))
        assertEquals("LIST", a)
        assertEquals("GRID", b)
        assertNull(ScheduleGridParser.split("LIST").second)
    }

    @Test
    fun `没有表头时按整页文字认，星期由列表视图决定`() {
        val course = Course(
            "X1", "数据挖掘", 2.0, 32, "01", listOf("孙丽", "何强"),
            listOf(ClassSession((1..16).toSet(), "1-16", 1, 5, 6, "教八 210")),
        )
        val html = "<div>数据挖掘 孙丽 1-15周(单)</div><div>数据挖掘 何强 2-16周(双)</div>"
        val attached = ScheduleGridParser.attach(listOf(course), ScheduleGridParser.parse(html, listOf(course))).single()
        val s = attached.sessions.single()
        assertEquals(listOf("孙丽"), attached.teachersOn(s, 1))
        assertEquals(listOf("何强"), attached.teachersOn(s, 2))
        assertEquals("1-15(单)", attached.teacherWeeks.first().weeksLabel)
    }

    @Test
    fun `较长的课名不会被较短的课名截走`() {
        val a = Course("A", "机器学习", 2.0, 32, "01", listOf("王明", "李华"), listOf(ClassSession((1..8).toSet(), "1-8", 1, 1, 2, "x")))
        val b = Course("B", "机器学习实验", 1.0, 16, "01", listOf("王明", "李华"), listOf(ClassSession((1..8).toSet(), "1-8", 1, 1, 2, "y")))
        val entries = ScheduleGridParser.entries("机器学习实验 李华 1-8周", 1, null, listOf(a, b))
        assertEquals(listOf(1), entries.map { it.course })
    }

    @Test
    fun `周次写法`() {
        assertEquals("2-8", WeekFormat.label((2..8).toSet()))
        assertEquals("1-15(单)", WeekFormat.label((1..15 step 2).toSet()))
        assertEquals("2-16(双)", WeekFormat.label((2..16 step 2).toSet()))
        assertEquals("3,5", WeekFormat.label(setOf(3, 5)))
        assertEquals("1-4,9,12-13", WeekFormat.label(setOf(1, 2, 3, 4, 9, 12, 13)))
        assertEquals("", WeekFormat.label(emptySet()))
    }
}
