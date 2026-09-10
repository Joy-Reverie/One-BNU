package io.github.joyreverie.onebnu.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderStyleTest {

    @Test
    fun `默认是通知提醒，存坏了也退回通知`() {
        // Settings 读取时用的就是这条：解析失败回落到通知，不会意外变成响铃
        assertEquals(
            ReminderStyle.NOTIFICATION,
            runCatching { ReminderStyle.valueOf("") }.getOrDefault(ReminderStyle.NOTIFICATION),
        )
        assertEquals(
            ReminderStyle.NOTIFICATION,
            runCatching { ReminderStyle.valueOf("RINGTONE") }.getOrDefault(ReminderStyle.NOTIFICATION),
        )
        assertEquals(ReminderStyle.ALARM, ReminderStyle.valueOf("ALARM"))
        assertEquals(ReminderStyle.NOTIFICATION, ReminderStyle.entries.first())
    }

    @Test
    fun `两种方式都写明了各自的行为`() {
        assertEquals("通知提醒", ReminderStyle.NOTIFICATION.label)
        assertEquals("闹钟提醒", ReminderStyle.ALARM.label)
        assertTrue(ReminderStyle.NOTIFICATION.description.contains("通知"))
        assertTrue(ReminderStyle.ALARM.description.contains("响铃"))
    }
}
