package io.github.joyreverie.onebnu.core.notify

import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import java.time.LocalDate
import java.time.LocalDateTime

/** 一条会被提醒的事项：一节课或一条日程。 */
data class ReminderItem(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val title: String,
    val location: String,
    val isEvent: Boolean,
)

/**
 * 上课提醒的排期，纯计算。
 *
 * 只排「下一次」：每次闹钟响后再排下一次，不预先埋一堆闹钟。这样课表或日程一变，
 * 重新算一次即可，也避免系统对闹钟数量的限制。
 */
object ReminderPlanner {

    /** 从 [from] 起 [days] 天内的全部课程与日程，按开始时间排序。 */
    fun items(
        schedule: Schedule?,
        events: List<PersonalEvent>,
        from: LocalDate,
        days: Int,
        periodTimes: List<String>,
    ): List<ReminderItem> {
        val out = ArrayList<ReminderItem>()
        val termStart = schedule?.let { AcademicCalendar.firstMonday(it.term) }
        for (offset in 0 until days) {
            val date = from.plusDays(offset.toLong())
            if (schedule != null && termStart != null) {
                val week = AcademicCalendar.weekOf(termStart, date)
                if (week >= 1) {
                    schedule.slotsOn(week, date.dayOfWeek.value).forEach { (course, session) ->
                        val s = periodTimes.getOrNull(session.startPeriod - 1)?.let { PeriodMapper.parsePeriod(it) }?.first
                        val e = periodTimes.getOrNull(session.endPeriod - 1)?.let { PeriodMapper.parsePeriod(it) }?.second
                        if (s != null && e != null) {
                            out += ReminderItem(date.atTime(s), date.atTime(e), course.name, session.location, isEvent = false)
                        }
                    }
                }
            }
            events.filter { it.occursOn(date) }.forEach { ev ->
                out += ReminderItem(date.atTime(ev.start), date.atTime(ev.end), ev.title, ev.location, isEvent = true)
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * 下一次该响铃的时刻，以及那一刻开始的事项（同一时刻多节课就一起提醒）。
     *
     * 两种「不按 start - lead」的情况：
     *  - **提醒时刻已过但还没开始**：比如提前 10 分钟、而用户在开始前 5 分钟才添加这条日程，
     *    这时立刻提醒（返回 [now]），而不是整条跳过 —— 跳过的话新加的近期日程永远等不到提醒。
     *  - **已经送达过的不再算**：[deliveredThrough] 是上一次已提醒的事项开始时刻，
     *    小于等于它的一律排除。否则「立刻提醒」这条会在每次重排时被重新算出来，变成一直响。
     */
    fun next(
        items: List<ReminderItem>,
        now: LocalDateTime,
        leadMinutes: Int,
        deliveredThrough: LocalDateTime? = null,
    ): Pair<LocalDateTime, List<ReminderItem>>? {
        val first = items
            // 已经开始的不再提醒；已送达过的不再重复
            .filter { it.start.isAfter(now) && (deliveredThrough == null || it.start.isAfter(deliveredThrough)) }
            .minByOrNull { it.start }
            ?: return null
        val fireAt = maxOf(first.start.minusMinutes(leadMinutes.toLong()), now)
        return fireAt to items.filter { it.start == first.start }
    }

    /** 在 [start] 这一刻开始的事项。 */
    fun startingAt(items: List<ReminderItem>, start: LocalDateTime): List<ReminderItem> = items.filter { it.start == start }
}
