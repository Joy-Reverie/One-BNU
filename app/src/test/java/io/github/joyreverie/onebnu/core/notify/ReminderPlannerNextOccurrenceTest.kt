package io.github.joyreverie.onebnu.core.notify

import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [ReminderPlanner.nextOccurrence] 是 1.9.25 起唯一的生产排程路径：直接按课表周次与日程日期算下一次，
 * 不受固定天数窗口限制。
 */
class ReminderPlannerNextOccurrenceTest {

    // 2026-2027 秋季：第 1 周周一是 2026-09-07，2026-09-10 是第 1 周周四
    private val term = Term("2026", "0", "2026-2027学年秋季学期")
    private val periods = Settings.PERIOD_TIMES

    private fun session(dow: Int, a: Int, b: Int, where: String, weeks: Set<Int> = (1..20).toSet()) =
        ClassSession(weeks, weeks.joinToString(","), dow, a, b, where)

    private fun course(code: String, name: String, vararg sessions: ClassSession) =
        Course(code, name, 2.0, 32, "01", listOf("教师"), sessions.toList())

    private fun schedule(vararg courses: Course) = Schedule(term, "1", "同学", "班", courses.toList())

    private val math = course("A", "高等数学", session(4, 1, 2, "教七 201"))    // 周四 08:00–09:40
    private val english = course("B", "大学英语", session(4, 1, 2, "教二 108")) // 周四 08:00，撞课
    private val pe = course("C", "体育", session(5, 3, 4, "体育馆"))            // 周五 10:00–11:40

    private fun next(
        schedule: Schedule?,
        events: List<PersonalEvent>,
        now: LocalDateTime,
        lead: Int = 10,
        delivered: Set<LocalDateTime> = emptySet(),
    ) = ReminderPlanner.nextOccurrence(schedule, events, now, lead, periods, delivered)

    @Test
    fun `取最近一节，同一时刻的一起提醒`() {
        val (at, hit) = next(schedule(math, english, pe), emptyList(), LocalDateTime.of(2026, 9, 10, 7, 0))!!
        assertEquals(LocalDateTime.of(2026, 9, 10, 7, 50), at)
        assertEquals(listOf("高等数学", "大学英语"), hit.map { it.title })
        assertEquals(LocalDateTime.of(2026, 9, 10, 9, 40), hit[0].end)
        assertEquals("教七 201", hit[0].location)
    }

    @Test
    fun `按周次直接跳到下一个上课周，不受天数窗口限制`() {
        // 实验只在第 3、5 周的周二 13:30；第 1 周周二中午看，下一次是第 3 周周二 2026-09-22
        val lab = course("D", "物理实验", session(2, 5, 6, "实验楼", weeks = setOf(3, 5)))
        val (at, hit) = next(schedule(lab), emptyList(), LocalDateTime.of(2026, 9, 8, 12, 0))!!
        assertEquals(LocalDateTime.of(2026, 9, 22, 13, 20), at)
        assertEquals(listOf("物理实验"), hit.map { it.title })
    }

    @Test
    fun `已开始的课不算，顺延到下周同一节`() {
        val (at, hit) = next(schedule(math), emptyList(), LocalDateTime.of(2026, 9, 10, 8, 30))!!
        assertEquals(LocalDateTime.of(2026, 9, 17, 7, 50), at)
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 0), hit.single().start)
    }

    @Test
    fun `提醒时刻已过但还没开始，立刻提醒`() {
        val now = LocalDateTime.of(2026, 9, 10, 7, 55)
        val (at, hit) = next(schedule(math, english), emptyList(), now)!!
        assertEquals(now, at)
        assertEquals(listOf("高等数学", "大学英语"), hit.map { it.title })
    }

    @Test
    fun `周次为 0、节次超出作息表、星期无效的安排都跳过`() {
        val broken = course(
            "E", "坏数据",
            session(1, 1, 2, "x", weeks = setOf(0)),
            session(1, 13, 14, "x"),
            session(8, 1, 2, "x"),
        )
        assertNull(next(schedule(broken), emptyList(), LocalDateTime.of(2026, 9, 7, 0, 0)))
    }

    @Test
    fun `不重复的日程：过去的不算，将来的按日期算`() {
        val past = PersonalEvent("p", "开学典礼", LocalDate.of(2026, 9, 1), LocalTime.of(9, 0), LocalTime.of(10, 0))
        val checkup = PersonalEvent("e", "体检", LocalDate.of(2026, 9, 11), LocalTime.of(8, 0), LocalTime.of(10, 0), "校医院")
        // 周四 09:00：当天的课已开始；周五 08:00 的体检比 10:00 的体育早
        val (at, hit) = next(schedule(math, pe), listOf(past, checkup), LocalDateTime.of(2026, 9, 10, 9, 0))!!
        assertEquals(LocalDateTime.of(2026, 9, 11, 7, 50), at)
        assertEquals(listOf("体检"), hit.map { it.title })
        assertTrue(hit.single().isEvent)
    }

    @Test
    fun `重复日程从起始日起算，哪怕起始日在几年后`() {
        val date = LocalDate.of(2028, 3, 6)
        val gym = PersonalEvent(
            "g", "健身", date, LocalTime.of(19, 0), LocalTime.of(20, 0), "体育馆",
            repeatDays = setOf(date.dayOfWeek.value),
        )
        val (at, hit) = next(null, listOf(gym), LocalDateTime.of(2026, 9, 10, 9, 0))!!
        assertEquals(date.atTime(18, 50), at)
        assertEquals(listOf("健身"), hit.map { it.title })
    }

    @Test
    fun `重复日程今天这次已过就取下一个重复日；重复截止后没有下一次`() {
        val gym = PersonalEvent(
            "g", "健身", LocalDate.of(2026, 9, 14), LocalTime.of(19, 0), LocalTime.of(20, 0), "体育馆",
            repeatDays = setOf(1, 3, 5),
        )
        // 周三 19:30：今天这次已开始，下一次是周五
        val (at, _) = next(null, listOf(gym), LocalDateTime.of(2026, 9, 16, 19, 30))!!
        assertEquals(LocalDateTime.of(2026, 9, 18, 18, 50), at)
        val ended = gym.copy(repeatUntil = LocalDate.of(2026, 9, 16))
        assertNull(next(null, listOf(ended), LocalDateTime.of(2026, 9, 16, 19, 30)))
    }

    @Test
    fun `日程与课程同一时刻一起提醒；关掉日程时上层只传空列表`() {
        val meeting = PersonalEvent("m", "早会", LocalDate.of(2026, 9, 10), LocalTime.of(8, 0), LocalTime.of(8, 30), "线上")
        val now = LocalDateTime.of(2026, 9, 10, 7, 0)
        val (_, both) = next(schedule(math, english), listOf(meeting), now)!!
        assertEquals(listOf("高等数学", "大学英语", "早会"), both.map { it.title })
        val (_, coursesOnly) = next(schedule(math, english), emptyList(), now)!!
        assertEquals(listOf("高等数学", "大学英语"), coursesOnly.map { it.title })
    }

    @Test
    fun `没有课表时只剩日程；学期结束、什么都没有时为 null`() {
        val checkup = PersonalEvent("e", "体检", LocalDate.of(2026, 9, 11), LocalTime.of(8, 0), LocalTime.of(10, 0), "校医院")
        assertEquals(listOf("体检"), next(null, listOf(checkup), LocalDateTime.of(2026, 9, 10, 9, 0))!!.second.map { it.title })
        assertNull(next(null, emptyList(), LocalDateTime.of(2026, 9, 10, 9, 0)))
        // 20 周之后课表里再没有下一次
        assertNull(next(schedule(math), emptyList(), LocalDateTime.of(2027, 3, 1, 9, 0)))
    }

    @Test
    fun `到点时冷启动的重排与投递竞态：先记再送，同一刻只送一次`() {
        // Settings.markReminded 的内存版：已记过就返回 false
        val delivered = mutableSetOf<LocalDateTime>()
        fun markReminded(start: LocalDateTime) = delivered.add(start)

        val sched = schedule(math, english, pe)
        val start = LocalDateTime.of(2026, 9, 10, 8, 0)
        val now = start.minusMinutes(10) // 闹钟正好到点

        // 1) 进程冷启动，init 里的 reschedule 先跑：这一刻还没记送达，同一条被算成「立刻提醒」—— 竞态的来源
        val (at, hit) = next(sched, emptyList(), now, delivered = delivered)!!
        assertEquals(now, at)
        assertEquals(start, hit.first().start)

        // 2) onAlarm：先「查 + 记」再送。第二条同一刻的广播记不进去，什么都不送
        assertTrue(markReminded(start))
        assertFalse(markReminded(start))

        // 3) 送达后的重排：这一刻不再出现，直接排到周五的体育
        val (nextAt, nextHit) = next(sched, emptyList(), now, delivered = delivered)!!
        assertEquals(LocalDateTime.of(2026, 9, 11, 9, 50), nextAt)
        assertEquals(listOf("体育"), nextHit.map { it.title })
    }
}
