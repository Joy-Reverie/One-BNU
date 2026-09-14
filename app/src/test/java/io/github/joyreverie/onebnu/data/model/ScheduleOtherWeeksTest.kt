package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「显示非本周课程」依赖的划分：某天的安排按「这周上 / 排在别的周」一分为二。 */
class ScheduleOtherWeeksTest {

    private fun session(weeks: Iterable<Int>, label: String, dow: Int, a: Int, b: Int) =
        ClassSession(weeks.toSet(), label, dow, a, b, "教四 117")

    private fun course(code: String, name: String, vararg sessions: ClassSession) =
        Course(code, name, 2.0, 32, "01", listOf("王明"), sessions.toList())

    private val schedule = Schedule(
        Term("2026", "0", "2026-2027学年秋季学期"), "200000000000", "张三", "计算机科学与技术",
        listOf(
            course("AIS101", "统计学习", session(1..15 step 2, "1-15(单)", 3, 3, 4)),
            course("AIS102", "深度学习", session(2..16 step 2, "2-16(双)", 3, 3, 4)),
            course("AIS103", "数据挖掘", session(9..16, "9-16", 3, 7, 8), session(9..16, "9-16", 1, 1, 2)),
        ),
    )

    @Test
    fun `这周上的与排在别的周的正好把当天的安排一分为二`() {
        val on = schedule.slotsOn(week = 2, dayOfWeek = 3).map { it.first.name }
        val off = schedule.otherWeekSlotsOn(week = 2, dayOfWeek = 3).map { it.first.name }
        assertEquals(listOf("深度学习"), on)
        assertEquals(listOf("统计学习", "数据挖掘"), off)
        assertEquals(schedule.slotsOnDay(3).size, on.size + off.size)
    }

    @Test
    fun `单双周交替：换一周两边对调`() {
        assertEquals(listOf("统计学习"), schedule.slotsOn(week = 3, dayOfWeek = 3).map { it.first.name })
        assertEquals(listOf("深度学习", "数据挖掘"), schedule.otherWeekSlotsOn(week = 3, dayOfWeek = 3).map { it.first.name })
    }

    @Test
    fun `别的星期的安排不会混进来，且按起始节排序`() {
        assertEquals(listOf(1), schedule.otherWeekSlotsOn(week = 2, dayOfWeek = 1).map { it.second.startPeriod })
        assertEquals(listOf(3, 3, 7), schedule.slotsOnDay(3).map { it.second.startPeriod })
        assertEquals(emptyList<Int>(), schedule.otherWeekSlotsOn(week = 2, dayOfWeek = 7).map { it.second.startPeriod })
    }
}
