package io.github.joyreverie.onebnu.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 系统把闹钟压后才送达（Doze、省电策略、关机再开机）时的处理：
 * 已结束的不送，已开始的照送、文案如实写「已开始 N 分钟」。
 */
class ReminderLateDeliveryTest {

    private val start = LocalDateTime.of(2026, 9, 10, 8, 0)
    private val math = ReminderItem(start, start.plusMinutes(100), "高等数学", "教七 201", isEvent = false)
    private val english = ReminderItem(start, start.plusMinutes(100), "大学英语", "教二 108", isEvent = false)
    private val meeting = ReminderItem(start, start.plusMinutes(30), "早会", "线上", isEvent = true)
    private val pe = ReminderItem(start.plusHours(2), start.plusHours(3), "体育", "体育馆", isEvent = false)
    private val items = listOf(math, english, meeting, pe)

    private fun dueAt(now: LocalDateTime) = ReminderPlanner.due(ReminderPlanner.startingAt(items, start), now)

    @Test
    fun `准时送达：这一刻开始的事项全部送`() {
        assertEquals(listOf("高等数学", "大学英语", "早会"), dueAt(start.minusMinutes(10)).map { it.title })
    }

    @Test
    fun `晚送达但还没结束的照送，已结束的不送`() {
        assertEquals(listOf("高等数学", "大学英语", "早会"), dueAt(start.plusMinutes(20)).map { it.title })
        // 08:45：半小时的早会已经散了，两节课还在上
        assertEquals(listOf("高等数学", "大学英语"), dueAt(start.plusMinutes(45)).map { it.title })
    }

    @Test
    fun `晚到课都上完了：一条都不送`() {
        assertTrue(dueAt(start.plusMinutes(100)).isEmpty())
        assertTrue(dueAt(start.plusHours(5)).isEmpty())
    }

    @Test
    fun `文案按真实时间：晚送达写「已开始 N 分钟」，不再说「即将开始」`() {
        assertEquals("已开始 20 分钟", ClassReminder.remainingLabel(start, start.plusMinutes(20)))
        assertEquals("已开始 1 分钟", ClassReminder.remainingLabel(start, start.plusSeconds(31)))
        // 半分钟以内都算「即将开始」
        assertEquals("即将开始", ClassReminder.remainingLabel(start, start.plusSeconds(29)))
        assertEquals("即将开始", ClassReminder.remainingLabel(start, start))
        assertEquals("即将开始", ClassReminder.remainingLabel(start, start.minusSeconds(29)))
        assertEquals("1 分钟后开始", ClassReminder.remainingLabel(start, start.minusSeconds(31)))
        assertEquals("10 分钟后开始", ClassReminder.remainingLabel(start, start.minusMinutes(10)))
    }
}
