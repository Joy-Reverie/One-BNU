package io.github.joyreverie.onebnu.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleLayoutTest {

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
    fun `不重叠的格子各自成组，重叠的并入一组`() {
        val items = listOf(
            ScheduleLayout.GridItem(1, 2, "数学"),
            ScheduleLayout.GridItem(3, 4, "英语"),
            ScheduleLayout.GridItem(4, 5, "体检"),   // 与英语的第 4 节重叠
            ScheduleLayout.GridItem(9, 10, "体育"),
        )
        val groups = ScheduleLayout.groupColumn(items)
        assertEquals(listOf(1 to 2, 3 to 5, 9 to 10), groups.map { it.start to it.end })
        assertEquals(listOf("英语", "体检"), groups[1].items.map { it.payload })
        assertEquals(2, groups[1].columns.size)
        assertEquals(3, groups[1].span)
    }

    @Test
    fun `组内互不重叠的格子共用一个子列`() {
        val groups = ScheduleLayout.groupColumn(
            listOf(
                ScheduleLayout.GridItem(5, 6, "近代史"),
                ScheduleLayout.GridItem(7, 8, "程序设计"),
                ScheduleLayout.GridItem(5, 7, "组会"),   // 把两门课串成一组
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(5 to 8, groups[0].start to groups[0].end)
        // 同一节开始时保持传入顺序（课程在前），两门课上下错开共用一列，组会另起一列
        assertEquals(listOf("近代史", "组会", "程序设计"), groups[0].items.map { it.payload })
        assertEquals(listOf(listOf("近代史", "程序设计"), listOf("组会")), groups[0].columns.map { c -> c.map { it.payload } })
    }

    @Test
    fun `起点被前一门课盖住的课也能画出来，且节次被夹在 1 到 12 之间`() {
        val groups = ScheduleLayout.groupColumn(
            listOf(ScheduleLayout.GridItem(1, 3, "A"), ScheduleLayout.GridItem(2, 2, "B"), ScheduleLayout.GridItem(12, 15, "C")),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("A", "B"), groups[0].items.map { it.payload })
        assertEquals(12 to 12, groups[1].start to groups[1].end)
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
