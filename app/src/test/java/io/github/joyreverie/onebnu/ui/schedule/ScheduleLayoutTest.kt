package io.github.joyreverie.onebnu.ui.schedule

import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.ui.schedule.ScheduleLayout.GridItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScheduleLayoutTest {

    private fun event(name: String, start: String, end: String): GridItem<String> {
        val (top, bottom) = PeriodMapper.span(LocalTime.parse(start), LocalTime.parse(end), Settings.PERIOD_TIMES)
        return GridItem(top, bottom, name)
    }

    @Test
    fun `竖屏保持默认行高`() {
        assertEquals(76f, ScheduleLayout.fittedRowDp(isLandscape = false, availableDp = 500f, baseDp = 76f))
    }

    @Test
    fun `横屏按一屏放下 12 节来压缩行高`() {
        // 平板横屏：可用约 750dp → 62.5dp 一行，一天全部落在一屏
        assertEquals(62.5f, ScheduleLayout.fittedRowDp(isLandscape = true, availableDp = 750f, baseDp = 76f))
        // 高度足够时不放大到超过默认行高
        assertEquals(76f, ScheduleLayout.fittedRowDp(isLandscape = true, availableDp = 2000f, baseDp = 76f))
        // 手机横屏：太矮了也不能低于下限
        assertEquals(44f, ScheduleLayout.fittedRowDp(isLandscape = true, availableDp = 300f, baseDp = 46f))
    }

    @Test
    fun `不重叠的格子各自成块，重叠的并入一块一簇`() {
        val items = listOf(
            GridItem.periods(1, 2, "数学"),
            GridItem.periods(3, 4, "英语"),
            GridItem.periods(4, 5, "体检"),   // 与英语的第 4 节重叠
            GridItem.periods(9, 10, "体育"),
        )
        val groups = ScheduleLayout.groupColumn(items)
        assertEquals(listOf(1 to 2, 3 to 5, 9 to 10), groups.map { it.start to it.end })
        assertEquals(listOf("英语", "体检"), groups[1].items.map { it.payload })
        assertEquals(1, groups[1].clusters.size)
        assertEquals(3, groups[1].span)
    }

    @Test
    fun `一条日程把两门课串成一簇，同一时刻开始时课程排在前面`() {
        val groups = ScheduleLayout.groupColumn(
            listOf(
                GridItem.periods(5, 6, "近代史"),
                GridItem.periods(7, 8, "程序设计"),
                GridItem.periods(5, 7, "组会"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(5 to 8, groups[0].start to groups[0].end)
        assertEquals(1, groups[0].clusters.size)
        assertEquals(listOf("近代史", "组会", "程序设计"), groups[0].items.map { it.payload })
    }

    @Test
    fun `首尾相接的日程不算重叠：同一块里分成两簇，各自显示`() {
        // 8:00–9:00 落到第 2 节行内一点点，9:00–10:00 从那里接到第 3 节上沿：行范围都碰到第 2 行，但时间不重叠
        val groups = ScheduleLayout.groupColumn(listOf(event("吃饭", "08:00", "09:00"), event("自习", "09:00", "10:00")))
        assertEquals(1, groups.size)
        assertEquals(1 to 2, groups[0].start to groups[0].end)
        assertEquals(listOf(listOf("吃饭"), listOf("自习")), groups[0].clusters.map { c -> c.map { it.payload } })
        assertFalse(groups[0].items[0].overlaps(groups[0].items[1]))
    }

    @Test
    fun `真正重叠的日程并成一簇，与课程重叠的日程也一样`() {
        val groups = ScheduleLayout.groupColumn(listOf(event("吃饭", "08:00", "09:30"), event("自习", "09:00", "10:00")))
        assertEquals(1, groups.size)
        assertEquals(listOf(listOf("吃饭", "自习")), groups[0].clusters.map { c -> c.map { it.payload } })

        val mixed = ScheduleLayout.groupColumn(listOf(GridItem.periods(1, 2, "数学"), event("自习", "09:00", "10:00")))
        assertEquals(1, mixed.size)
        assertEquals(listOf("数学", "自习"), mixed[0].clusters.single().map { it.payload })
        assertTrue(mixed[0].items[0].overlaps(mixed[0].items[1]))
    }

    @Test
    fun `起点被前一门课盖住的课也能画出来，且节次被夹在 1 到 12 之间`() {
        val groups = ScheduleLayout.groupColumn(
            listOf(GridItem.periods(1, 3, "A"), GridItem.periods(2, 2, "B"), GridItem.periods(12, 15, "C")),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("A", "B"), groups[0].items.map { it.payload })
        assertEquals(12 to 12, groups[1].start to groups[1].end)
        assertEquals(11f to 12f, groups[1].items[0].top to groups[1].items[0].bottom)
    }

    @Test
    fun `缩放有上下限，字号只跟一半`() {
        assertEquals(0.6f, ScheduleLayout.clampZoom(0.1f))
        assertEquals(1.8f, ScheduleLayout.clampZoom(5f))
        assertEquals(1f, ScheduleLayout.fontScale(1f))
        assertEquals(1.3f, ScheduleLayout.fontScale(1.8f))
        assertEquals(0.85f, ScheduleLayout.fontScale(0.6f))
        assertEquals(60f * 1.5f, ScheduleLayout.rowDp(isLandscape = false, availableDp = 0f, baseDp = 60f, zoom = 1.5f))
    }
}
