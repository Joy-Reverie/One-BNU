package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GpaCalculatorTest {

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
}
