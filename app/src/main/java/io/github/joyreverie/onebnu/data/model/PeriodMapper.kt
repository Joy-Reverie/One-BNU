package io.github.joyreverie.onebnu.data.model

import java.time.Duration
import java.time.LocalTime

/**
 * 把具体时刻映射到课表网格的纵向位置，用来把日程画进课表。
 *
 * [position] 以「行」为单位：第 k 节占 [k-1, k)。节内按时间线性插值，**课间算进它前面那一节的行里** ——
 * 网格的行高是均匀的，而真实时间不是，若课间没有位置，08:00–09:00 会被算成「整个第 1 节 + 第 2 节的前 5 分钟」，
 * 比紧接着的 09:00–10:00 高出四分之一，同样一小时看着一长一短。把课间并进上一行后，同长的两段高度基本一致
 * （残差只来自各行覆盖的真实时长不等：45 / 55 / 65 分钟）。
 *
 * 午休、晚上开课前这类长间隔（超过 [MAX_BREAK_MINUTES]）不并入，否则整个下午会被挤扁；落在里面的时刻贴到下一节上沿。
 * 作息之外贴到两端。[periodsFor] 是老的整节换算，提醒等只关心「占哪几节」的地方仍在用。
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

    fun format(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /**
     * 自定义作息能不能用：每节都解析得出、下课晚于上课、且不早于前一节下课。
     * 网格与提醒都假定各节按时间先后排列，顺序一乱课表就画错，所以在存之前挡住。
     *
     * @return 第一处问题的说明，null 表示这份作息没问题
     */
    fun validate(periodTimes: List<String>): String? {
        var prevEnd: LocalTime? = null
        for ((i, spec) in periodTimes.withIndex()) {
            val (start, end) = parsePeriod(spec) ?: return "第 ${i + 1} 节的时间格式不对"
            if (!end.isAfter(start)) return "第 ${i + 1} 节的下课时间要晚于上课时间"
            val prev = prevEnd
            if (prev != null && start.isBefore(prev)) return "第 ${i + 1} 节的上课时间不能早于第 $i 节下课"
            prevEnd = end
        }
        return null
    }

    /** 并入上一行的最长间隔（分钟）：课间 10 分钟、大课间 20 分钟算课间，午休与晚上开课前的空档不算。 */
    const val MAX_BREAK_MINUTES = 30L

    fun position(t: LocalTime, periodTimes: List<String>): Float {
        val periods = periodTimes.mapNotNull { parsePeriod(it) }
        if (periods.isEmpty()) return 0f
        if (!t.isAfter(periods.first().first)) return 0f
        if (!t.isBefore(periods.last().second)) return periods.size.toFloat()
        for ((i, p) in periods.withIndex()) {
            val (a, b) = p
            val rowEnd = rowEnd(periods, i)
            if (!t.isBefore(rowEnd)) continue
            // 落在长间隔里（午休、晚上开课前）：贴到下一节的上沿
            if (t.isBefore(a)) return i.toFloat()
            val length = Duration.between(a, rowEnd).toMinutes().toFloat().coerceAtLeast(1f)
            return i + Duration.between(a, t).toMinutes() / length
        }
        return periods.size.toFloat()
    }

    /** 第 [i] 节所在的行覆盖到什么时刻：下一节紧接着（间隔不超过 [MAX_BREAK_MINUTES]）就覆盖到它开始，否则到本节下课。 */
    private fun rowEnd(periods: List<Pair<LocalTime, LocalTime>>, i: Int): LocalTime {
        val end = periods[i].second
        val next = periods.getOrNull(i + 1)?.first ?: return end
        return if (Duration.between(end, next).toMinutes() <= MAX_BREAK_MINUTES) next else end
    }

    /**
     * 这一段是不是整个落在「网格里没有高度」的空档里：第一节之前，或午休、晚上开课前这类
     * 超过 [MAX_BREAK_MINUTES] 的长间隔。这种段落只能贴到下一节的上沿，
     * 与那一节课在真实时间上并不重叠 —— 课表因此不能把它们判成重叠、藏进 ⇅ 切换格。
     */
    fun insideLongBreak(start: LocalTime, end: LocalTime, periodTimes: List<String>): Boolean {
        val periods = periodTimes.mapNotNull { parsePeriod(it) }
        if (periods.isEmpty()) return false
        if (!end.isAfter(periods.first().first)) return true
        for (i in 0 until periods.lastIndex) {
            val from = rowEnd(periods, i)
            val to = periods[i + 1].first
            if (Duration.between(from, to).toMinutes() <= MAX_BREAK_MINUTES) continue
            if (!start.isBefore(from) && !end.isAfter(to)) return true
        }
        return false
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
