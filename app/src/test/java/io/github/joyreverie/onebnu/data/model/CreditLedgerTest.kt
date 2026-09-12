package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditLedgerTest {

    private fun course(code: String, name: String, credits: Double, study: String = "初修") =
        Course(code, name, credits, null, "01", emptyList(), emptyList(), studyType = study)

    private val autumn = Term("2026", "0", "2026-2027学年秋季学期")
    private val spring = Term("2026", "1", "2026-2027学年春季学期")

    @Test
    fun `成绩单课程性质的归类`() {
        assertEquals(CourseCategory.DEGREE_BASIC, CategoryRules.fromGradeType("学位基础课"))
        assertEquals(CourseCategory.DEGREE_MAJOR, CategoryRules.fromGradeType("学位专业课"))
        assertEquals(CourseCategory.PUBLIC_REQUIRED, CategoryRules.fromGradeType("公共必修课"))
        assertEquals(CourseCategory.PUBLIC_ELECTIVE, CategoryRules.fromGradeType("公共选修"))
        assertEquals(CourseCategory.EXPANSION, CategoryRules.fromGradeType("专业拓展课"))
        assertNull(CategoryRules.fromGradeType(""))
    }

    @Test
    fun `没有依据时按课程号与学分推断`() {
        assertEquals(CourseCategory.PUBLIC_REQUIRED, CategoryRules.infer(course("GRA20225821", "理论与实践课", 2.0)))
        assertEquals(CourseCategory.PUBLIC_REQUIRED, CategoryRules.infer(course("GRA20221101", "综合学术英语", 2.0)))
        assertEquals(CourseCategory.PUBLIC_ELECTIVE, CategoryRules.infer(course("GRA20220901", "数据与智能技术应用", 2.0)))
        assertEquals(CourseCategory.DEGREE_BASIC, CategoryRules.infer(course("AIS21197082", "高级算法设计", 3.0)))
        assertEquals(CourseCategory.DEGREE_MAJOR, CategoryRules.infer(course("AIS21158302", "机器学习", 2.0)))
    }

    @Test
    fun `手动指定优先于成绩单，成绩单优先于推断，重修不计学分`() {
        val schedules = listOf(
            Schedule(autumn, "1", "张三", "", listOf(
                course("AIS21197082", "高级算法设计", 3.0),
                course("AIS21158302", "机器学习", 2.0),
                course("GRA20225821", "理论与实践课", 2.0),
            )),
            Schedule(spring, "1", "张三", "", listOf(
                course("AIS21197082", "高级算法设计", 3.0, study = "重修"),
                course("AIS21100001", "数据挖掘", 2.0),
            )),
        )
        val grades = listOf(
            Grade("2026", "0", "", "AIS21158302", "机器学习", 2.0, "90", 90.0, null, courseType = "学位专业课"),
        )
        val manual = mapOf("AIS21158302" to CourseCategory.EXPANSION)
        val ledger = CategoryRules.build(schedules, grades, manual)

        assertEquals(2, ledger.terms.size)
        val autumnEntries = ledger.terms[0].entries.associateBy { it.course.code }
        assertEquals(CategorySource.MANUAL, autumnEntries["AIS21158302"]!!.source)
        assertEquals(CourseCategory.EXPANSION, autumnEntries["AIS21158302"]!!.category)
        assertEquals(CategorySource.INFERRED, autumnEntries["AIS21197082"]!!.source)
        assertEquals(7.0, ledger.terms[0].total, 0.001)
        // 春季重修的 3 学分不计
        assertEquals(2.0, ledger.terms[1].total, 0.001)
        assertEquals(9.0, ledger.total, 0.001)
        assertEquals(3.0, ledger.totalOf(CourseCategory.DEGREE_BASIC), 0.001)
        assertEquals(2.0, ledger.totalOf(CourseCategory.EXPANSION), 0.001)
        assertEquals(2.0, ledger.totalOf(CourseCategory.PUBLIC_REQUIRED), 0.001)
        // 成绩单归类：把手动的拿掉后机器学习应按成绩单算学位专业课
        val noManual = CategoryRules.build(schedules, grades, emptyMap())
        assertEquals(CategorySource.GRADE, noManual.terms[0].entries.first { it.course.code == "AIS21158302" }.source)
    }

    @Test
    fun `珠海非 GRA 公共课按课程名称识别，培养方案模块优先`() {
        val publicCourse = course("MAR20531801", "马克思主义与当代科技", 1.0)
        assertEquals(CourseCategory.PUBLIC_REQUIRED, CategoryRules.infer(publicCourse))
        val schedule = Schedule(autumn, "1", "张三", "", listOf(publicCourse))
        val ledger = CategoryRules.build(
            listOf(schedule),
            emptyList(),
            emptyMap(),
            mapOf("MAR20531801" to CourseCategory.PUBLIC_REQUIRED),
        )
        assertEquals(CourseCategory.PUBLIC_REQUIRED, ledger.entries.single().category)
        assertEquals(CategorySource.MODULE, ledger.entries.single().source)
    }

    @Test
    fun `专业选修不会被误归为公共选修`() {
        assertEquals(CourseCategory.DEGREE_MAJOR, CategoryRules.fromGradeType("专业选修课"))
        assertEquals(CourseCategory.DEGREE_MAJOR, CategoryRules.fromGradeType("学位专业课"))
        assertEquals(CourseCategory.DEGREE_MAJOR, CategoryRules.fromGradeType("学位必修课"))
    }

    @Test
    fun `选课结果官方类别优先于培养方案与成绩单`() {
        val c = course("AIS21100001", "课程甲", 2.0)
        val schedule = Schedule(autumn, "1", "张三", "", listOf(c))
        val grade = Grade("2026", "0", "", "AIS21100001", "课程甲", 2.0, "90", 90.0, null, courseType = "学位基础课")
        val ledger = CategoryRules.build(
            schedules = listOf(schedule),
            grades = listOf(grade),
            manual = emptyMap(),
            modules = mapOf("AIS21100001" to CourseCategory.DEGREE_BASIC),
            selection = mapOf("AIS21100001" to CourseCategory.DEGREE_MAJOR),
        )
        assertEquals(CourseCategory.DEGREE_MAJOR, ledger.entries.single().category)
        assertEquals(CategorySource.SELECTION_RESULT, ledger.entries.single().source)
    }
}
