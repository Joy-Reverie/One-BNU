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
    fun `已经过了提醒时刻的课不再提醒，顺延到下一项`() {
        val items = ReminderPlanner.items(schedule, listOf(event), LocalDate.of(2026, 9, 10), 2, Settings.PERIOD_TIMES)
        val (at, hit) = ReminderPlanner.next(items, LocalDateTime.of(2026, 9, 10, 7, 55), 10)!!
        assertEquals(LocalDateTime.of(2026, 9, 11, 7, 50), at)
        assertEquals(listOf("体检"), hit.map { it.title })
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
}
