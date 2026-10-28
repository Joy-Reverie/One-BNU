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
 * schedule_grid.html 照线上网格视图的版式写成（每门课一个 div：课程名 / 老师 / 周次[节次] / 地点），
 * 内容与 schedule_list.html 是同一套虚构课表。
 */
class ScheduleGridParserTest {

    private val term = Term("2026", "0", "2026-2027学年秋季学期")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少 fixture: $name" }
            .bufferedReader(charset("UTF-8")).readText()

    private fun withGrid(): Schedule =
        Parsers.parseSchedule(ScheduleGridParser.join(fixture("schedule_list.html"), fixture("schedule_grid.html")), term)

    private fun Schedule.course(name: String) = courses.first { it.name == name }

    /** 线上网格视图的一格：每门课一个 div。 */
    private fun cell(vararg entries: String) =
        entries.joinToString("") { "<div style='padding-bottom:5px;clear:both;'>${it.replace("|", "<br>")}</div>" }

    /** 只有一列（[day]）有课的网格页，表头与线上一样左边空两列。 */
    private fun grid(day: Int, rowCell: String): String {
        val header = (1..7).joinToString("") { "<td>星期${"一二三四五六日"[it - 1]}</td>" }
        val cols = (1..7).joinToString("") { if (it == day) "<td>$rowCell</td>" else "<td><div class='div_nokb'></div></td>" }
        return "<table><tr class='H'><td colspan='2'></td>$header</tr><tr><td rowspan='2'>下午</td><td><b>四</b></td>$cols</tr></table>"
    }

    @Test
    fun `每一周只显示当周的老师`() {
        val ml = withGrid().course("机器学习")
        val early = ml.sessions.first { 2 in it.weeks }
        val late = ml.sessions.first { 9 in it.weeks }
        assertEquals(listOf("王明"), ml.teachersOn(early, 2))
        assertEquals(listOf("王明"), ml.teachersOn(early, 8))
        assertEquals(listOf("李华"), ml.teachersOn(late, 9))
        assertEquals(listOf("李华"), ml.teachersOn(late, 16))
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
    fun `一门课多段安排、一格里两位老师都对得上`() {
        val c = withGrid().course("高级算法设计")
        for (start in listOf(1, 3)) {
            val early = c.sessions.first { it.dayOfWeek == 5 && it.startPeriod == start && 3 in it.weeks }
            val late = c.sessions.first { it.dayOfWeek == 5 && it.startPeriod == start && 10 in it.weeks }
            assertEquals(listOf("张伟"), c.teachersOn(early, 3))
            assertEquals(listOf("刘洋"), c.teachersOn(late, 10))
        }
        // 第 6 周周六补的两次课
        c.sessions.filter { it.dayOfWeek == 6 }.forEach { assertEquals(listOf("张伟"), c.teachersOn(it, 6)) }
        assertEquals(
            listOf(TeacherWeeks("张伟", (2..6).toSet()), TeacherWeeks("刘洋", (7..16).toSet())),
            c.teacherWeeks,
        )
    }

    @Test
    fun `名单上有、网格里没排到的老师不算当周老师`() {
        val talk = withGrid().course("人工智能前沿讲座")
        assertEquals(listOf("吴敏"), talk.teachersOn(talk.sessions.first(), 5))
        assertEquals(listOf(TeacherWeeks("吴敏", (2..16).toSet())), talk.teacherWeeks)
    }

    @Test
    fun `同一时段老师交替轮换`() {
        // 线上真实出现过的排法：同一格四段，两位老师按周穿插；列表视图把它拆成 2-5,7-8 / 6 / 9 / 10-16 四次
        val course = Course(
            "X1", "数据挖掘", 2.0, 32, "01", listOf("孙丽", "何强"),
            listOf(
                ClassSession(setOf(2, 3, 4, 5, 7, 8), "2-5,7-8", 2, 7, 8, "四117"),
                ClassSession(setOf(6), "6", 2, 7, 8, "四117"),
                ClassSession(setOf(9), "9", 2, 7, 8, "四117"),
                ClassSession((10..16).toSet(), "10-16", 2, 7, 8, "四117"),
            ),
        )
        val html = grid(
            2,
            cell("数据挖掘|何强 |6[7-8]|四117", "数据挖掘|孙丽 |9[7-8]|四117", "数据挖掘|何强 |10-16[7-8]|四117", "数据挖掘|孙丽 |2-5,7-8[7-8]|四117"),
        )
        val c = ScheduleGridParser.attach(listOf(course), ScheduleGridParser.parse(html, listOf(course))).single()
        val byWeek = (2..16).associateWith { w -> c.sessions.first { w in it.weeks }.let { c.teachersOn(it, w) } }
        assertEquals((2..16).associateWith { if (it == 6 || it >= 10) listOf("何强") else listOf("孙丽") }, byWeek)
        assertEquals(
            listOf(TeacherWeeks("孙丽", setOf(2, 3, 4, 5, 7, 8, 9)), TeacherWeeks("何强", setOf(6) + (10..16))),
            c.teacherWeeks,
        )
        assertEquals("2-5,7-9", c.teacherWeeks.first().weeksLabel)
    }

    @Test
    fun `周次带前导零或只有一节`() {
        val course = Course(
            "X2", "科技写作", 2.0, 32, "01", listOf("孙丽", "郑浩"),
            listOf(
                ClassSession(setOf(2), "02", 3, 7, 8, "二112"),
                ClassSession(setOf(3), "3", 3, 8, 8, "在线教学"),
            ),
        )
        val html = grid(3, cell("科技写作|孙丽 |02[7-8]|二112", "科技写作|郑浩 |3[8]|在线教学"))
        val c = ScheduleGridParser.attach(listOf(course), ScheduleGridParser.parse(html, listOf(course))).single()
        assertEquals(listOf("孙丽"), c.teachersOn(c.sessions[0], 2))
        assertEquals(listOf("郑浩"), c.teachersOn(c.sessions[1], 3))
    }

    @Test
    fun `同一周同一天两段课按节次分开`() {
        // 两位老师同一周同一天各上一段：只能靠方括号里的节次分清
        val course = Course(
            "X3", "实验设计", 2.0, 32, "01", listOf("林芳", "徐静"),
            listOf(
                ClassSession((2..16).toSet(), "2-16", 4, 7, 8, "七103"),
                ClassSession((2..16).toSet(), "2-16", 4, 9, 10, "七103"),
            ),
        )
        val html = grid(4, cell("实验设计|林芳 |2-16[7-8]|七103", "实验设计|徐静 |2-16[9-10]|七103"))
        val c = ScheduleGridParser.attach(listOf(course), ScheduleGridParser.parse(html, listOf(course))).single()
        assertEquals(listOf("林芳"), c.teachersOn(c.sessions[0], 5))
        assertEquals(listOf("徐静"), c.teachersOn(c.sessions[1], 5))
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
    fun `其他写法：带「周」字、没有表头`() {
        val course = Course(
            "X4", "数据挖掘", 2.0, 32, "01", listOf("孙丽", "何强"),
            listOf(ClassSession((1..16).toSet(), "1-16", 1, 5, 6, "教八 210")),
        )
        val html = "<div>数据挖掘 孙丽 1-15周(单)</div><div>数据挖掘 何强 2-16周(双)</div>"
        val c = ScheduleGridParser.attach(listOf(course), ScheduleGridParser.parse(html, listOf(course))).single()
        val s = c.sessions.single()
        assertEquals(listOf("孙丽"), c.teachersOn(s, 1))
        assertEquals(listOf("何强"), c.teachersOn(s, 2))
        assertEquals("1-15(单)", c.teacherWeeks.first().weeksLabel)
    }

    @Test
    fun `较长的课名不会被较短的课名截走`() {
        val a = Course("A", "机器学习", 2.0, 32, "01", listOf("王明", "李华"), listOf(ClassSession((1..8).toSet(), "1-8", 1, 1, 2, "x")))
        val b = Course("B", "机器学习实验", 1.0, 16, "01", listOf("王明", "李华"), listOf(ClassSession((1..8).toSet(), "1-8", 1, 1, 2, "y")))
        val entries = ScheduleGridParser.entries("机器学习实验 李华 1-8[1-2]", 1, null, listOf(a, b))
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
