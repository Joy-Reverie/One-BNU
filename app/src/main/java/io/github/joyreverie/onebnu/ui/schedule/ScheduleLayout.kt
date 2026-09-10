package io.github.joyreverie.onebnu.ui.schedule

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

    /** 网格一列里的一个格子：占第 [start]～[end] 节，[payload] 是课或日程。 */
    data class GridItem<T>(val start: Int, val end: Int, val payload: T)

    /**
     * 节次上互相重叠的一组格子，并排画在同一块区域里。[columns] 是并排的子列，
     * 同一子列内的格子互不重叠、只是上下错开，这样 5-6 节的课、7-8 节的课和一条 5-7 节的日程
     * 只占两列而不是三列，文字才有地方放。
     */
    data class GridGroup<T>(
        val start: Int,
        val end: Int,
        /** 组内全部格子，按起始节排序；界面按这个顺序切换显示。 */
        val items: List<GridItem<T>>,
        val columns: List<List<GridItem<T>>>,
    ) {
        val span: Int get() = end - start + 1
    }

    /**
     * 把一列里的格子按节次重叠关系分组：只要与前一组有任何一节重叠就归入同一组，
     * 组内再贪心塞进尽量少的子列。界面上一组一次只显示一个格子、底部切换条换下一个，
     * 这样撞课、以及与课重叠的日程都不会因为起点被别的课盖住而消失。
     */
    fun <T> groupColumn(items: List<GridItem<T>>, periods: Int = PERIODS): List<GridGroup<T>> {
        val sorted = items
            .map { item ->
                val start = item.start.coerceIn(1, periods)
                item.copy(start = start, end = item.end.coerceIn(start, periods))
            }
            // 只按起始节排序且保持稳定：同一节开始的，调用方先放进来的（课程）排在前面、默认显示
            .sortedBy { it.start }
        val groups = ArrayList<GridGroup<T>>()
        var current = ArrayList<GridItem<T>>()
        var start = 0
        var end = -1
        fun flush() {
            if (current.isEmpty()) return
            val columns = ArrayList<ArrayList<GridItem<T>>>()
            for (item in current) {
                val slot = columns.firstOrNull { it.last().end < item.start } ?: ArrayList<GridItem<T>>().also { columns += it }
                slot += item
            }
            groups += GridGroup(start, end, current.toList(), columns)
            current = ArrayList()
        }
        for (item in sorted) {
            if (current.isNotEmpty() && item.start > end) flush()
            if (current.isEmpty()) { start = item.start; end = item.end } else end = maxOf(end, item.end)
            current += item
        }
        flush()
        return groups
    }
}
