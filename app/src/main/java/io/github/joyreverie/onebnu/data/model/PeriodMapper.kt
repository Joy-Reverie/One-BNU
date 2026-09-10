package io.github.joyreverie.onebnu.data.model

import java.time.LocalTime

/**
 * 把一段具体时间映射到课表的节次区间，用来把日程画进课表网格。
 * 与某一节的上课时间有交集就算占用这一节；整段落在作息之外时贴到最近的一节，
 * 保证日程总能在网格里被看见。
 */
object PeriodMapper {

    /** "HH:mm-HH:mm" 形式的一节作息拆成起止时刻。 */
    fun parsePeriod(spec: String): Pair<LocalTime, LocalTime>? {
        val parts = spec.split('-')
        if (parts.size != 2) return null
        val a = parse(parts[0]) ?: return null
        val b = parse(parts[1]) ?: return null
        return a to b
    }

    fun parse(hhmm: String): LocalTime? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(hhmm) ?: return null
        return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }

    fun periodsFor(start: LocalTime, end: LocalTime, periodTimes: List<String>): IntRange {
        val periods = periodTimes.mapNotNull { parsePeriod(it) }
        if (periods.isEmpty()) return 1..1
        val hit = periods.withIndex()
            .filter { (_, p) -> start.isBefore(p.second) && end.isAfter(p.first) }
            .map { it.index + 1 }
        if (hit.isNotEmpty()) return hit.first()..hit.last()
        // 没有交集：整段在第一节之前、最后一节之后，或者正好落在课间
        if (!end.isAfter(periods.first().first)) return 1..1
        if (!start.isBefore(periods.last().second)) return periods.size..periods.size
        val next = periods.indexOfFirst { !it.first.isBefore(start) }.let { if (it < 0) periods.lastIndex else it }
        return (next + 1)..(next + 1)
    }
}
