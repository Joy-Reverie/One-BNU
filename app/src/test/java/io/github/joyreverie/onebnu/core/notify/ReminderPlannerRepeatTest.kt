package io.github.joyreverie.onebnu.core.notify

import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerRepeatTest {

    @Test
    fun `重复日程在 lookahead 里的每一天都各生成一条提醒项`() {
        val from = LocalDate.of(2026, 9, 14) // 周一
        val gym = PersonalEvent(
            "g", "健身", from, LocalTime.of(19, 0), LocalTime.of(20, 0), "体育馆",
            repeatDays = setOf(1, 3, 5),
        )
        val items = ReminderPlanner.items(null, listOf(gym), from, 7, Settings.PERIOD_TIMES)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18)),
            items.map { it.start.toLocalDate() },
        )
        val next = ReminderPlanner.next(items, LocalDateTime.of(2026, 9, 14, 19, 30), 10)
        assertEquals(LocalDateTime.of(2026, 9, 16, 18, 50), next?.first)
    }
}
