package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PersonalEventJsonTest {

    @Test
    fun `日程 JSON 往返相等`() {
        val list = listOf(
            PersonalEvent("a", "体检", LocalDate.of(2026, 9, 11), LocalTime.of(8, 0), LocalTime.of(10, 0), "校医院", "带学生卡"),
            PersonalEvent("b", "组会", LocalDate.of(2026, 9, 12), LocalTime.of(14, 30), LocalTime.of(16, 0)),
        )
        assertEquals(list, PersonalEventJson.decode(PersonalEventJson.encode(list)))
        assertEquals("08:00–10:00", list[0].timeLabel)
    }

    @Test
    fun `坏条目跳过，整体不崩`() {
        val text = """[{"id":"a","title":"体检","date":"2026-09-11","start":"08:00","end":"10:00"},{"id":"bad"}]"""
        val got = PersonalEventJson.decode(text)
        assertEquals(1, got.size)
        assertTrue(PersonalEventJson.decode("garbage").isEmpty())
    }
}
