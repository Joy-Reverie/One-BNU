package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PersonalEventRepeatTest {

    // 2026-09-10 是周四
    private val thursday = LocalDate.of(2026, 9, 10)
    private fun event(days: Set<Int>, until: LocalDate? = null) =
        PersonalEvent("e", "组会", thursday, LocalTime.of(14, 0), LocalTime.of(16, 0), repeatDays = days, repeatUntil = until)

    @Test
    fun `不重复的日程只在当天`() {
        val e = event(emptySet())
        assertTrue(e.occursOn(thursday))
        assertFalse(e.occursOn(thursday.plusWeeks(1)))
        assertEquals("不重复", e.repeatLabel)
    }

    @Test
    fun `每周重复从开始日期起、到结束日期止，只落在选中的星期`() {
        val e = event(setOf(1, 3), until = LocalDate.of(2026, 9, 30))
        assertFalse("开始日期之前不算", e.occursOn(LocalDate.of(2026, 9, 9)))
        assertFalse("开始日本身是周四，不在选中的星期里", e.occursOn(thursday))
        assertTrue(e.occursOn(LocalDate.of(2026, 9, 14)))   // 周一
        assertTrue(e.occursOn(LocalDate.of(2026, 9, 16)))   // 周三
        assertFalse(e.occursOn(LocalDate.of(2026, 9, 15)))  // 周二
        assertTrue(e.occursOn(LocalDate.of(2026, 9, 30)))   // 结束日当天（周三）还算
        assertFalse(e.occursOn(LocalDate.of(2026, 10, 5)))  // 之后不算
        assertEquals("每周一、三 · 至 9月30日", e.repeatLabel)
    }

    @Test
    fun `预设的说明文字`() {
        assertEquals("每天", event(PersonalEvent.EVERY_DAY).repeatLabel)
        assertEquals("工作日", event(PersonalEvent.WEEKDAYS).repeatLabel)
        assertEquals("周末", event(PersonalEvent.WEEKEND).repeatLabel)
        assertEquals("每周四", event(setOf(4)).repeatLabel)
    }

    @Test
    fun `重复规则能编解码，旧数据没有重复字段也能读`() {
        val list = listOf(event(setOf(2, 4), until = LocalDate.of(2027, 1, 10)), event(emptySet()))
        val back = PersonalEventJson.decode(PersonalEventJson.encode(list))
        assertEquals(list, back)
        val legacy = """[{"id":"x","title":"体检","date":"2026-09-11","start":"08:00","end":"10:00"}]"""
        val e = PersonalEventJson.decode(legacy).single()
        assertFalse(e.repeats)
        assertEquals(null, e.repeatUntil)
    }
}
