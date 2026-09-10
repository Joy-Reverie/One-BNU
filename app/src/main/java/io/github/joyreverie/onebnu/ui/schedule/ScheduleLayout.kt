package io.github.joyreverie.onebnu.ui.schedule

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 课表网格的尺寸规则，纯计算、不依赖 Compose，便于单元测试。
 *
 * 横屏时宽度富余而高度紧张：行高按「一天 12 节尽量全部落进一屏」来算，
 * 但不低于 [MIN_ROW_DP]，否则课名就读不出来了。竖屏保持各屏幕档位的默认行高。
 * 用户双指缩放得到的 [zoom] 再乘在上面，并记住。
 */
object ScheduleLayout {

    const val PERIODS = 12

    const val ZOOM_MIN = 0.6f
    const val ZOOM_MAX = 1.8f
    const val ZOOM_DEFAULT = 1f

    /** 行高的下限（dp），再小课名与地点就挤不下两行了。 */
    const val MIN_ROW_DP = 44f

    /** 格子的最小纵向跨度（行），保证再短的日程也画得出来。 */
    const val MIN_SPAN = 0.1f

    /** 某屏幕档位的默认行高（dp）。 */
    fun baseRowDp(isShort: Boolean, isExpanded: Boolean, isMedium: Boolean): Float = when {
        isShort -> 46f
        isExpanded -> 76f
        isMedium -> 66f
        else -> 60f
    }

    /**
     * 未缩放的行高（dp）。
     * @param availableDp 网格可用高度（已扣掉星期表头）
     */
    fun fittedRowDp(isLandscape: Boolean, availableDp: Float, baseDp: Float): Float {
        if (!isLandscape || availableDp <= 0f) return baseDp
        return (availableDp / PERIODS).coerceIn(MIN_ROW_DP, baseDp)
    }

    /** 最终行高（dp）：适配后的行高乘以用户缩放。 */
    fun rowDp(isLandscape: Boolean, availableDp: Float, baseDp: Float, zoom: Float): Float =
        fittedRowDp(isLandscape, availableDp, baseDp) * clampZoom(zoom)

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** 字号随缩放变化，但幅度只取一半并限制范围：放大到 1.8 倍时字不至于过大，缩到 0.6 倍时仍可读。 */
    fun fontScale(zoom: Float): Float = (1f + (clampZoom(zoom) - 1f) * 0.5f).coerceIn(0.85f, 1.3f)

    /** 左侧刻度一格显示到什么程度。 */
    enum class GutterDetail {
        /** 只有节次号。 */
        NUMBER_ONLY,

        /** 节次号 + 上课时刻。 */
        START_ONLY,

        /** 节次号 + 上课 + 下课时刻。 */
        START_AND_END,
    }

    /** 节次号那一行占的高度（dp，文字缩放为 1 时）。 */
    private const val NUMBER_LINE_DP = 12f

    /** 一行时刻占的高度（dp，文字缩放为 1 时）。 */
    private const val TIME_LINE_DP = 10f

    /** 刻度上下留一点余量，不贴着相邻的行。 */
    private const val GUTTER_PADDING_DP = 4f

    /**
     * 行高 [rowDp] 放不下三行字时逐级降级：三行（节次 + 上下课）→ 两行（节次 + 上课）→ 只有节次号。
     *
     * [textScale] 是字号的实际倍数（缩放倍数 × 系统字体大小）。行高只跟缩放走、不跟系统字体走，
     * 所以系统字体调大时同样要降级，否则时间行会互相挤压甚至被裁掉。
     */
    fun gutterDetail(rowDp: Float, textScale: Float): GutterDetail {
        val scale = textScale.coerceAtLeast(0.5f)
        return when {
            rowDp >= (NUMBER_LINE_DP + 2 * TIME_LINE_DP + GUTTER_PADDING_DP) * scale -> GutterDetail.START_AND_END
            rowDp >= (NUMBER_LINE_DP + TIME_LINE_DP + GUTTER_PADDING_DP) * scale -> GutterDetail.START_ONLY
            else -> GutterDetail.NUMBER_ONLY
        }
    }

    /**
     * 左侧刻度列的宽度（dp）：要放得下 "08:00"。
     * 正常字号下与各屏幕档位的老宽度一致；系统字体或缩放把字放大时按比例加宽（至多 1.5 倍），
     * 否则时刻会被裁成 "08:0"。再宽就该让给七列课程了。
     */
    fun gutterDp(isExpanded: Boolean, isMedium: Boolean, textScale: Float): Float {
        val base = when {
            isExpanded -> 56f
            isMedium -> 48f
            else -> 42f
        }
        return base * textScale.coerceIn(1f, 1.5f)
    }

    /**
     * 网格一列里的一个格子。纵向位置以「行」为单位：第 k 节占 [k-1, k)，
     * 课程落在整行上，日程按具体时刻落在行内的任意位置（见 `PeriodMapper.span`）。
     */
    data class GridItem<T>(val top: Float, val bottom: Float, val payload: T) {

        /** 首尾相接不算重叠。 */
        fun overlaps(other: GridItem<*>): Boolean = top < other.bottom && other.top < bottom

        /** 所占的第一行 / 最后一行（1 起）。 */
        val firstRow: Int get() = floor(top).toInt() + 1
        val lastRow: Int get() = maxOf(ceil(bottom).toInt(), firstRow)

        companion object {
            /** 占整节 [start]～[end] 的格子（课程）。 */
            fun <T> periods(start: Int, end: Int, payload: T): GridItem<T> =
                GridItem(start - 1f, end.toFloat(), payload)
        }
    }

    /**
     * 行范围互有交集的一片格子，界面上作为一块整体布局，块内每个格子按自己的 [GridItem.top] /
     * [GridItem.bottom] 绝对定位。[clusters] 是块内按**时间**真正重叠的分簇：同一簇一次只显示一个、
     * 底部切换条换下一个；不同簇（如 8:00–9:00 与 9:00–10:00 两条相接的日程）同时显示。
     */
    data class GridGroup<T>(
        val start: Int,
        val end: Int,
        /** 块内全部格子，按上沿排序。 */
        val items: List<GridItem<T>>,
        val clusters: List<List<GridItem<T>>>,
    ) {
        val span: Int get() = end - start + 1
    }

    /**
     * 把一列里的格子分块：先按时间重叠关系连成簇（与簇内任一格子有交集即并入，首尾相接不算），
     * 再把行范围有交集的簇合成一块，供界面按行顺序布局。
     * 只按上沿排序且保持稳定：同一时刻开始的，调用方先放进来的（课程）排在前面、默认显示。
     */
    fun <T> groupColumn(items: List<GridItem<T>>, periods: Int = PERIODS): List<GridGroup<T>> {
        val max = periods.toFloat()
        val sorted = items
            .map { item ->
                val top = item.top.coerceIn(0f, max - MIN_SPAN)
                item.copy(top = top, bottom = item.bottom.coerceIn(top + MIN_SPAN, max))
            }
            .sortedBy { it.top }

        val clusters = ArrayList<MutableList<GridItem<T>>>()
        var current: MutableList<GridItem<T>>? = null
        var clusterEnd = 0f
        for (item in sorted) {
            val c = current
            if (c == null || item.top >= clusterEnd) {
                current = arrayListOf(item).also { clusters += it }
                clusterEnd = item.bottom
            } else {
                c += item
                clusterEnd = maxOf(clusterEnd, item.bottom)
            }
        }

        val groups = ArrayList<GridGroup<T>>()
        var block = ArrayList<List<GridItem<T>>>()
        var start = 0
        var end = -1
        fun flush() {
            if (block.isEmpty()) return
            groups += GridGroup(start, end, block.flatten().sortedBy { it.top }, block.toList())
            block = ArrayList()
        }
        for (cluster in clusters) {
            val s = cluster.minOf { it.firstRow }
            val e = cluster.maxOf { it.lastRow }
            if (block.isNotEmpty() && s > end) flush()
            if (block.isEmpty()) { start = s; end = e } else end = maxOf(end, e)
            block += cluster
        }
        flush()
        return groups
    }
}
