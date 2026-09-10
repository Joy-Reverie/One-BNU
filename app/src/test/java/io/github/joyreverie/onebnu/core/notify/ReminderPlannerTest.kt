package io.github.joyreverie.onebnu.core.notify

import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerTest {

    private val term = Term("2026", "0", "2026-2027学年秋季学期")
    private fun session(dow: Int, a: Int, b: Int, where: String) = ClassSession((1..20).toSet(), "1-20", dow, a, b, where)
    private val schedule = Schedule(
        term, "1", "同学", "班",
        listOf(
            Course("A", "高等数学", 2.0, 32, "01", listOf("张三"), listOf(session(4, 1, 2, "教七 201"))),   // 周四 08:00
            Course("B", "大学英语", 2.0, 32, "01", listOf("李四"), listOf(session(4, 1, 2, "教二 108"))),   // 周四 08:00，撞课
            Course("C", "体育", 1.0, 32, "01", listOf("赵六"), listOf(session(5, 3, 4, "体育馆"))),        // 周五 10:00
        ),
    )
    private val event = PersonalEvent("e1", "体检", LocalDate.of(2026, 9, 11), LocalTime.of(8, 0), LocalTime.of(10, 0), "校医院")

    @Test
    fun `课程与日程按开始时间合并`() {
        val items = ReminderPlanner.items(schedule, listOf(event), LocalDate.of(2026, 9, 10), 2, Settings.PERIOD_TIMES)
        assertEquals(listOf("高等数学", "大学英语", "体检", "体育"), items.map { it.title })
        assertEquals(LocalDateTime.of(2026, 9, 10, 8, 0), items[0].start)
        assertEquals(LocalDateTime.of(2026, 9, 10, 9, 40), items[0].end)
        assertEquals(LocalDateTime.of(2026, 9, 11, 8, 0), items[2].start)
    }

    @Test
    fun `下一次提醒取最近一节，同一时刻的一起提醒`() {
        val items = ReminderPlanner.items(schedule, listOf(event), LocalDate.of(2026, 9, 10), 2, Settings.PERIOD_TIMES)
        val (at, hit) = ReminderPlanner.next(items, LocalDateTime.of(2026, 9, 10, 7, 0), 10)!!
        assertEquals(LocalDateTime.of(2026, 9, 10, 7, 50), at)
        assertEquals(listOf("高等数学", "大学英语"), hit.map { it.title })
    }

    @Test
    fun `提醒时刻已过但还没开始，立刻提醒而不是跳过`() {
        // 07:55 时 08:00 的课已过了提前 10 分钟的点，但课还没开始：应当立刻提醒
        val items = ReminderPlanner.items(schedule, listOf(event), LocalDate.of(2026, 9, 10), 2, Settings.PERIOD_TIMES)
        val now = LocalDateTime.of(2026, 9, 10, 7, 55)
        val (at, hit) = ReminderPlanner.next(items, now, 10)!!
        assertEquals(now, at)
        assertEquals(listOf("高等数学", "大学英语"), hit.map { it.title })
    }

    @Test
    fun `新加的近期日程立刻提醒，且已提醒过的不再重复`() {
        val soon = PersonalEvent(
            "e9", "组会", LocalDate.of(2026, 9, 10), LocalTime.of(15, 0), LocalTime.of(16, 0), "生地楼 306",
        )
        val items = ReminderPlanner.items(null, listOf(soon), LocalDate.of(2026, 9, 10), 1, Settings.PERIOD_TIMES)
        // 14:57 添加、提前 10 分钟：立刻提醒
        val now = LocalDateTime.of(2026, 9, 10, 14, 57)
        val (at, hit) = ReminderPlanner.next(items, now, 10)!!
        assertEquals(now, at)
        assertEquals(listOf("组会"), hit.map { it.title })
        // 提醒过之后再重排：同一条不再算进来，否则会一直响
        val done = LocalDateTime.of(2026, 9, 10, 15, 0)
        assertNull(ReminderPlanner.next(items, now, 10, deliveredThrough = done))
    }

    @Test
    fun `已经开始的事项不再提醒`() {
        val items = ReminderPlanner.items(schedule, listOf(event), LocalDate.of(2026, 9, 10), 2, Settings.PERIOD_TIMES)
        // 08:30 时周四 08:00 的课已在进行中，顺延到次日的体检
        val (at, hit) = ReminderPlanner.next(items, LocalDateTime.of(2026, 9, 10, 8, 30), 10)!!
        assertEquals(LocalDateTime.of(2026, 9, 11, 7, 50), at)
        assertEquals(listOf("体检"), hit.map { it.title })
    }

    @Test
    fun `日程与课程同一时刻开始时一起提醒，关掉日程后只剩课程`() {
        val sameTime = PersonalEvent(
            "e8", "早会", LocalDate.of(2026, 9, 10), LocalTime.of(8, 0), LocalTime.of(8, 30), "线上",
        )
        val withEvents = ReminderPlanner.items(schedule, listOf(sameTime), LocalDate.of(2026, 9, 10), 1, Settings.PERIOD_TIMES)
        val (_, both) = ReminderPlanner.next(withEvents, LocalDateTime.of(2026, 9, 10, 7, 0), 10)!!
        assertEquals(listOf("高等数学", "大学英语", "早会"), both.map { it.title })
        // 「日程也提醒」关掉时，上层传空的日程列表
        val coursesOnly = ReminderPlanner.items(schedule, emptyList(), LocalDate.of(2026, 9, 10), 1, Settings.PERIOD_TIMES)
        val (_, onlyCourses) = ReminderPlanner.next(coursesOnly, LocalDateTime.of(2026, 9, 10, 7, 0), 10)!!
        assertEquals(listOf("高等数学", "大学英语"), onlyCourses.map { it.title })
    }

    @Test
    fun `没有更多事项时返回 null`() {
        val items = ReminderPlanner.items(schedule, emptyList(), LocalDate.of(2026, 9, 10), 1, Settings.PERIOD_TIMES)
        assertNull(ReminderPlanner.next(items, LocalDateTime.of(2026, 9, 10, 12, 0), 10))
    }

    @Test
    fun `开学前和没有课表时只剩日程`() {
        val items = ReminderPlanner.items(null, listOf(event), LocalDate.of(2026, 9, 10), 3, Settings.PERIOD_TIMES)
        assertEquals(listOf("体检"), items.map { it.title })
    }

    @Test
    fun `通知文案按真实剩余时间算，而不是照抄提前时间`() {
        val now = LocalDateTime.of(2026, 9, 10, 14, 57)
        // 立刻提醒的场景：离开始只剩 3 分钟
        assertEquals("3 分钟后开始", ClassReminder.remainingLabel(LocalDateTime.of(2026, 9, 10, 15, 0), now))
        // 正常提前 10 分钟，允许几秒的触发延迟
        assertEquals("10 分钟后开始", ClassReminder.remainingLabel(LocalDateTime.of(2026, 9, 10, 15, 7), now))
        assertEquals("10 分钟后开始", ClassReminder.remainingLabel(LocalDateTime.of(2026, 9, 10, 15, 6, 58), now))
        // 已经到点或过点
        assertEquals("即将开始", ClassReminder.remainingLabel(now, now))
        assertEquals("即将开始", ClassReminder.remainingLabel(now.minusMinutes(1), now))
    }
}
