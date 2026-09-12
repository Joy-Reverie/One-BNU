package io.github.joyreverie.onebnu.data.parse

import io.github.joyreverie.onebnu.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全部针对 zyfw.bnu.edu.cn 真实返回的页面做解析。
 * fixtures 目录下的 HTML 是线上原样抓取的（exam_table.html 为真实表头 + 人工补的数据行，
 * 因为测试账号本学期没有已发布的考试）；学号、姓名、院系、课程与教师名都已替换为虚构值，
 * 只保留页面结构与格式。
 */
class ParsersTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "缺少 fixture: $name"
        }.bufferedReader().readText()

    private val term = Term("2026", "0", "2026-2027学年秋季学期")

    // ---------------- 课表 ----------------

    @Test
    fun `解析真实课表`() {
        val s = Parsers.parseSchedule(fixture("schedule_list.html"), term)

        // fixture 已脱敏：真实学号/姓名替换为占位值
        assertEquals("200000000000", s.studentId)
        assertEquals("张三", s.studentName)
        assertEquals(9, s.courses.size)
        assertEquals(17.0, s.totalCredits, 0.001)

        val zy = s.courses.first { it.name == "机器学习" }
        assertEquals("AIS21158302", zy.code)
        assertEquals(2.0, zy.credits, 0.001)
        assertEquals(32, zy.hours)
        assertEquals("01", zy.classNo)
        assertEquals(listOf("王明", "李华"), zy.teachers)
        assertEquals("初修", zy.studyType)

        // "2-8周 二[7-8] 四117(60),9-16周 二[7-8] 四117(60)"
        assertEquals(2, zy.sessions.size)
        val first = zy.sessions[0]
        assertEquals(2, first.dayOfWeek)
        assertEquals(7, first.startPeriod)
        assertEquals(8, first.endPeriod)
        assertEquals("四117", first.location)
        assertEquals(60, first.capacity)
        assertEquals((2..8).toSet(), first.weeks)
        assertEquals((9..16).toSet(), zy.sessions[1].weeks)
    }

    @Test
    fun `一门课的多段安排都被拆开`() {
        val s = Parsers.parseSchedule(fixture("schedule_list.html"), term)
        val gj = s.courses.first { it.name == "高级算法设计" }
        // 2-6 五[1-2] / 2-6 五[3-4] / 6 六[1-2] / 6 六[3-4] / 7-16 五[1-2] / 7-16 五[3-4]
        assertEquals(6, gj.sessions.size)
        assertTrue(gj.sessions.any { it.dayOfWeek == 6 && it.startPeriod == 1 })
        assertTrue(gj.sessions.any { it.location == "在线教学" })
    }

    @Test
    fun `按周次和星期取出当天的课`() {
        val s = Parsers.parseSchedule(fixture("schedule_list.html"), term)
        // 第 3 周周二第 7-8 节应有机器学习
        val tue = s.slotsOn(week = 3, dayOfWeek = 2)
        assertTrue(tue.any { it.first.name == "机器学习" && it.second.startPeriod == 7 })
        // 第 20 周（超出所有课程周次）应为空
        assertTrue(s.slotsOn(week = 20, dayOfWeek = 2).isEmpty())
    }

    // ---------------- 上课时间串 ----------------

    @Test
    fun `周次区间`() {
        val s = Parsers.parseSessions("2-8周 二[7-8] 四117(60)").single()
        assertEquals((2..8).toSet(), s.weeks)
        assertEquals(60, s.capacity)
    }

    @Test
    fun `离散周次不被误当作分段分隔符`() {
        val s = Parsers.parseSessions("9,13,17周 四[1-4] 京师大厦9406(120)").single()
        assertEquals(setOf(9, 13, 17), s.weeks)
        assertEquals(4, s.dayOfWeek)
        assertEquals(1, s.startPeriod)
        assertEquals(4, s.endPeriod)
    }

    @Test
    fun `单双周`() {
        assertEquals(setOf(1, 3, 5, 7), Parsers.expandWeeks("1-8", "单"))
        assertEquals(setOf(2, 4, 6, 8), Parsers.expandWeeks("1-8", "双"))
        val s = Parsers.parseSessions("2-16周(单) 五[3-4] 教七301").single()
        assertTrue(s.weeks.all { it % 2 == 1 })
    }

    @Test
    fun `单节次`() {
        val s = Parsers.parseSessions("3周 日[12] 在线教学").single()
        assertEquals(12, s.startPeriod)
        assertEquals(12, s.endPeriod)
        assertEquals(7, s.dayOfWeek)
        assertEquals("在线教学", s.location)
    }

    @Test
    fun `空串与无法识别的串不抛异常`() {
        assertTrue(Parsers.parseSessions("").isEmpty())
        assertTrue(Parsers.parseSessions("待定").isEmpty())
    }

    // ---------------- 考试 ----------------

    @Test
    fun `解析考试安排`() {
        val exams = Parsers.parseExams(fixture("exam_table.html"))
        assertEquals(2, exams.size)
        val e = exams[0]
        assertEquals("机器学习", e.courseName)
        assertEquals("2.0", e.credits)
        assertEquals("学位必修课", e.category)
        assertEquals("考试", e.examType)
        assertEquals("2026-06-18 08:00-10:00", e.time)
        assertEquals("教七201", e.location)
        assertEquals("23", e.seat)
        assertEquals("2026-06-18", e.date)
    }

    // ---------------- 教室 ----------------

    @Test
    fun `解析教室占用并推算空闲`() {
        val rooms = Parsers.parseClassrooms(fixture("classroom_list.html"))
        assertTrue("应至少解析出一间教室", rooms.isNotEmpty())

        val room = rooms.first { it.name.contains("9406") }
        assertTrue("9406 应有占用记录", room.busy.isNotEmpty())

        // 组织行为学：9,13,17 周 四[1-4]
        val slot = room.busy.first { it.weeks.contains(13) && it.dayOfWeek == 4 }
        assertEquals(1, slot.startPeriod)
        assertEquals(4, slot.endPeriod)

        // 第 13 周周四 1-2 节被占；同一天 7-8 节不冲突
        assertTrue(!room.isFreeAt(13, 4, 1..2))
        assertTrue(room.isFreeAt(13, 4, 7..8))
        // 第 14 周周四不在 {9,13,17} 内，应空闲
        assertTrue(room.isFreeAt(14, 4, 1..2))
    }

    // ---------------- 空结果 ----------------

    @Test
    fun `没有数据时返回空列表而不是崩溃`() {
        val html = fixture("grades_empty.html")
        assertTrue(Parsers.parseGrades(html).isEmpty())
        assertNotNull(Parsers.parseSchedule(html, term))
        assertTrue(Parsers.parseSchedule(html, term).courses.isEmpty())
    }

    @Test
    fun `成绩状态列里的缓考会随零分一起被识别`() {
        val html = """
            <table><thead><tr>
              <th>学年</th><th>学期</th><th>课程代码</th><th>课程名称</th><th>学分</th>
              <th>总评成绩</th><th>学分绩点</th><th>成绩状态</th>
            </tr></thead><tbody><tr>
              <td>2026</td><td>0</td><td>TEST001</td><td>测试课程</td><td>2</td>
              <td>0</td><td>0</td><td>缓考</td>
            </tr></tbody></table>
        """.trimIndent()

        val grade = Parsers.parseGrades(html).single()

        assertEquals("缓考", grade.remark)
        assertTrue(grade.isDeferredExam)
        assertTrue(!grade.countable)
    }
    // ---------------- 学籍（XML，非 HTML 表格）----------------

    @Test
    fun `解析学籍 XML`() {
        val p = Parsers.parseStudentProfile(fixture("student_info.xml"))
        assertNotNull(p)
        requireNotNull(p)
        assertEquals("张三", p.name)
        assertEquals("200000000000", p.studentId)
        assertEquals("男", p.gender)
        assertEquals("人工智能学院", p.department)
        assertEquals("计算机科学与技术", p.major)
        assertEquals("2026", p.grade)
        assertEquals("硕士", p.level)
        // 只保留有值的字段
        assertTrue(p.details.isNotEmpty())
        assertTrue(p.details.none { it.value.isBlank() })
        assertTrue(p.details.any { it.label == "院系" && it.value == "人工智能学院" })
    }

    @Test
    fun `学籍解析排除敏感字段`() {
        val xml = "<info><xm>张三</xm><yhxh>200000000000</yhxh>" +
            "<sfzjh>110101200001011234</sfzjh><gkksh>12345678</gkksh></info>"
        val p = requireNotNull(Parsers.parseStudentProfile(xml))
        assertTrue("身份证号不应出现", p.details.none { it.value.contains("110101") })
        assertTrue("准考证号不应出现", p.details.none { it.value == "12345678" })
    }

    @Test
    fun `学籍为空时返回 null 而不是空壳`() {
        assertNull(Parsers.parseStudentProfile("<info><zp></zp></info>"))
        assertNull(Parsers.parseStudentProfile(""))
    }
    // ---------------- 毕业学分 ----------------

    @Test
    fun `毕业学分空表不应把表头当成数据`() {
        val empty = """<table><thead><tr class='H'><td>序号</td><td>项目</td><td>学分</td></tr>
            </thead><tbody></tbody></table>"""
        assertTrue(Parsers.parseCreditRequirements(empty).isEmpty())
    }

    @Test
    fun `解析毕业学分行`() {
        val html = """<table><thead><tr class='H'><td>序号</td><td>项目</td><td>学分</td></tr></thead>
            <tbody>
              <tr><td>1</td><td>学位必修课</td><td>18.0</td></tr>
              <tr><td>2</td><td>专业选修课</td><td>10.0</td></tr>
            </tbody></table>"""
        val r = Parsers.parseCreditRequirements(html)
        assertEquals(2, r.size)
        assertEquals("学位必修课", r[0].label)
        assertEquals("18.0", r[0].value)
        assertEquals("专业选修课", r[1].label)
    }

    @Test
    fun `解析培养方案课程模块`() {
        assertEquals(
            mapOf("AAA20000001" to "公共必修课", "BBB20000002" to "学位专业课"),
            Parsers.parseCourseModules(fixture("course_modules_table.html")),
        )
    }

    @Test
    fun `解析选课结果官方课程类别`() {
        val html = """
            <table><thead><tr><td>课程号</td><td>课程名称</td><td>课程性质</td></tr></thead>
            <tbody>
              <tr><td>[AIS21100001]课程甲</td><td>课程甲</td><td>专业选修课</td></tr>
              <tr><td>GRA20220001</td><td>课程乙</td><td>公共必修课</td></tr>
            </tbody></table>
        """.trimIndent()
        assertEquals(
            mapOf("AIS21100001" to "专业选修课", "GRA20220001" to "公共必修课"),
            Parsers.parseCourseCategories(html),
        )
    }
}
