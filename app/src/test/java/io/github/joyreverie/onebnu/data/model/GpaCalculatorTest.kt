package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpaCalculatorTest {

    @Test
    fun `从平时期末总评反推成绩权重`() {
        val composition = ScoreComposition.infer(60.0, 30.0, 42.0)
        assertEquals(40.0, composition!!.usualWeightPercent, 0.001)
        assertEquals(60.0, composition.finalWeightPercent, 0.001)
    }

    private fun grade(
        name: String,
        score: Double,
        point: Double = score / 20,
        credits: Double = 2.0,
        remark: String = "",
    ) = Grade(
        xn = "2026",
        xq = "0",
        termLabel = "2026-2027 秋季学期",
        courseCode = "TEST-${name.hashCode()}",
        courseName = name,
        credits = credits,
        scoreText = score.toInt().toString(),
        score = score,
        officialPoint = point,
        remark = remark,
    )

    @Test
    fun `缓考暂记零分不会计入任何绩点口径`() {
        val passed = grade("正常课程", score = 90.0, point = 4.0, credits = 3.0)
        val deferred = grade("缓考课程", score = 0.0, point = 0.0, credits = 2.0, remark = "缓考")

        val summary = GpaCalculator.summarize(listOf(passed, deferred), GpaScale.OFFICIAL)

        assertFalse(deferred.countable)
        assertNull(GpaScale.OFFICIAL.pointOf(deferred))
        assertFalse(GpaCalculator.isPassed(deferred))
        assertEquals(4.0, summary.gpa!!, 0.001)
        assertEquals(90.0, summary.weightedAverage!!, 0.001)
        assertEquals(3.0, summary.gradedCredits, 0.001)
        assertEquals(1, summary.deferredCount)
        assertEquals(1, summary.excludedCount)
    }

    @Test
    fun `手动取消勾选只影响可计算课程`() {
        val high = grade("高分课程", score = 90.0, point = 4.0)
        val low = grade("低分课程", score = 60.0, point = 1.0)
        // 即使被错误地塞进“手动排除”集合，缓考仍属于自动排除，而非人工排除。
        val deferred = grade("缓考课程", score = 0.0, point = 0.0, remark = "缓考")

        val summary = GpaCalculator.summarize(
            listOf(high, low, deferred),
            GpaScale.OFFICIAL,
            manuallyExcludedCourseKeys = setOf(low.calculationKey, deferred.calculationKey),
        )

        assertEquals(4.0, summary.gpa!!, 0.001)
        assertEquals(2.0, summary.gradedCredits, 0.001)
        assertEquals(1, summary.manuallyExcludedCount)
        assertEquals(1, summary.deferredCount)
        assertEquals(2, summary.excludedCount)
    }

    @Test
    fun `通过制的合格不当成 65 分拉低绩点`() {
        val excellent = grade("机器学习", score = 95.0, point = 4.5, credits = 3.0)
        val pass = Grade(
            xn = "2026", xq = "0", termLabel = "2026-2027 秋季学期",
            courseCode = "AIS20000001", courseName = "体育", credits = 1.0,
            scoreText = "合格", score = null, officialPoint = null, remark = "",
        )

        assertNull("「合格」没有分数含义", GradeScale.letterToScore("合格"))
        assertNull(GpaScale.LINEAR_5.pointOf(pass))
        assertNull(GpaScale.STANDARD_4.pointOf(pass))
        assertTrue("通过制仍然算取得学分", GpaCalculator.isPassed(pass))

        val five = GpaCalculator.summarize(listOf(excellent, pass), GpaScale.LINEAR_5)
        assertEquals(4.5, five.gpa!!, 0.001)
        assertEquals(95.0, five.weightedAverage!!, 0.001)
        assertEquals(3.0, five.gradedCredits, 0.001)
        assertEquals(4.0, five.earnedCredits, 0.001)
    }

    @Test
    fun `不合格既不给学分也不按 45 分计入`() {
        val fail = Grade(
            xn = "2026", xq = "0", termLabel = "2026-2027 秋季学期",
            courseCode = "AIS20000002", courseName = "劳动教育", credits = 1.0,
            scoreText = "不合格", score = null, officialPoint = null, remark = "",
        )
        assertNull(GradeScale.letterToScore("不合格"))
        assertNull(GpaScale.LINEAR_5.pointOf(fail))
        assertFalse(GpaCalculator.isPassed(fail))
    }

    @Test
    fun `五级等第仍然按百分制中值折算`() {
        assertEquals(95.0, GradeScale.letterToScore("优秀")!!, 0.001)
        assertEquals(65.0, GradeScale.letterToScore("及格")!!, 0.001)
        assertEquals(45.0, GradeScale.letterToScore("不及格")!!, 0.001)
    }


    // ------------------------------------------------------------------
    // 重修 / 补考与学期排序
    // ------------------------------------------------------------------

    private fun attempt(
        code: String,
        name: String,
        score: Double,
        xn: String,
        xq: String,
        credits: Double = 2.0,
        remark: String = "",
    ) = Grade(
        xn = xn,
        xq = xq,
        termLabel = "$xn-${xn.toInt() + 1} ${if (xq == "0") "秋季" else "春季"}学期",
        courseCode = code,
        courseName = name,
        credits = credits,
        scoreText = score.toInt().toString(),
        score = score,
        officialPoint = null,
        remark = remark,
    )

    @Test
    fun `首修不及格再重修通过：只计最后一次，不重复计学分`() {
        val first = attempt("AIS001", "高等代数", 55.0, "2025", "0", credits = 3.0)
        val retake = attempt("AIS001", "高等代数", 85.0, "2025", "1", credits = 3.0, remark = "重修")
        val other = attempt("AIS002", "概率论", 90.0, "2025", "0", credits = 2.0)

        assertEquals(setOf(0), GpaCalculator.supersededIndices(listOf(first, retake, other)))
        val s = GpaCalculator.summarize(listOf(first, retake, other), GpaScale.LINEAR_5)
        assertEquals(1, s.supersededCount)
        assertEquals(1, s.excludedCount)
        assertEquals(5.0, s.earnedCredits, 0.001)
        assertEquals(5.0, s.gradedCredits, 0.001)
        // 3.5 × 3 + 4.0 × 2 = 18.5，/ 5 = 3.7
        assertEquals(3.7, s.gpa!!, 0.001)
    }

    @Test
    fun `首修及格后标了重修再修：学分只算一次，成绩按后一次`() {
        val first = attempt("AIS003", "数据结构", 62.0, "2024", "1", credits = 3.0)
        val retake = attempt("AIS003", "数据结构", 88.0, "2025", "0", credits = 3.0, remark = "重修")
        val s = GpaCalculator.summarize(listOf(retake, first), GpaScale.LINEAR_5)
        assertEquals(1, s.supersededCount)
        assertEquals(3.0, s.earnedCredits, 0.001)
        assertEquals(3.8, s.gpa!!, 0.001)
    }

    @Test
    fun `每学期都修且都及格的同号课程不是重修，各自计入`() {
        val a = attempt("GRA001", "形势与政策", 90.0, "2024", "0", credits = 0.5)
        val b = attempt("GRA001", "形势与政策", 92.0, "2024", "1", credits = 0.5)
        val c = attempt("GRA001", "形势与政策", 95.0, "2025", "0", credits = 0.5)
        val s = GpaCalculator.summarize(listOf(a, b, c), GpaScale.LINEAR_5)
        assertEquals(0, s.supersededCount)
        assertEquals(1.5, s.earnedCredits, 0.001)
        assertEquals(3, s.courseCount - s.excludedCount)
    }

    @Test
    fun `各学期按学年学期先后排序，同一学年春季排在秋季前面`() {
        val autumn = attempt("A", "甲", 80.0, "2025", "0")
        val spring = attempt("B", "乙", 80.0, "2025", "1")
        val older = attempt("C", "丙", 80.0, "2024", "1")
        val labels = GpaCalculator.byTerm(listOf(autumn, older, spring), GpaScale.LINEAR_5).map { it.first }
        assertEquals(listOf("2025-2026 春季学期", "2025-2026 秋季学期", "2024-2025 春季学期"), labels)
    }
}
