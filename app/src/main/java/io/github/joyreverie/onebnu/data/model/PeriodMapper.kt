package io.github.joyreverie.onebnu.data.model

import java.time.Duration
import java.time.LocalTime

/**
 * 把具体时刻映射到课表网格的纵向位置，用来把日程画进课表。
 *
 * [position] 以「行」为单位：第 k 节占 [k-1, k)，节内按时间线性插值；课间与午休在网格里没有高度，
 * 压成两节之间的边界；作息之外贴到两端。[periodsFor] 是老的整节换算，提醒等只关心「占哪几节」的地方仍在用。
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

    fun position(t: LocalTime, periodTimes: List<String>): Float {
        val periods = periodTimes.mapNotNull { parsePeriod(it) }
        if (periods.isEmpty()) return 0f
        if (!t.isAfter(periods.first().first)) return 0f
        if (!t.isBefore(periods.last().second)) return periods.size.toFloat()
        for ((i, p) in periods.withIndex()) {
            val (a, b) = p
            if (!t.isBefore(a) && t.isBefore(b)) {
                val length = Duration.between(a, b).toMinutes().toFloat().coerceAtLeast(1f)
                return i + Duration.between(a, t).toMinutes() / length
            }
            val next = periods.getOrNull(i + 1) ?: break
            // 落在课间：贴到下一节的上沿
            if (!t.isBefore(b) && t.isBefore(next.first)) return (i + 1).toFloat()
        }
        return periods.size.toFloat()
    }

    /** 一段时间在网格里的上下沿（行单位）；整段落在课间时下沿只比上沿高出最小跨度，界面再保证最小高度。 */
    fun span(start: LocalTime, end: LocalTime, periodTimes: List<String>, minSpan: Float = 0.1f): Pair<Float, Float> {
        val top = position(start, periodTimes)
        return top to maxOf(position(end, periodTimes), top + minSpan)
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
