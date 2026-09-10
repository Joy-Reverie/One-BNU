package io.github.joyreverie.onebnu.data.model

import io.github.joyreverie.onebnu.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class PeriodMapperTest {

    private val p = Settings.PERIOD_TIMES

    @Test
    fun `八点到十点占第 1、2 节`() {
        assertEquals(1..2, PeriodMapper.periodsFor(LocalTime.of(8, 0), LocalTime.of(10, 0), p))
    }

    @Test
    fun `跨过课间也算占用`() {
        // 09:30–10:30：第 2 节（08:55-09:40）与第 3 节（10:00-10:45）
        assertEquals(2..3, PeriodMapper.periodsFor(LocalTime.of(9, 30), LocalTime.of(10, 30), p))
    }

    @Test
    fun `时刻按行内比例定位，课间压成边界，作息之外贴两端`() {
        assertEquals(0f, PeriodMapper.position(LocalTime.of(8, 0), p), 1e-4f)
        // 08:45 是第 1 节下课、08:50 在课间：都贴到第 2 节上沿
        assertEquals(1f, PeriodMapper.position(LocalTime.of(8, 45), p), 1e-4f)
        assertEquals(1f, PeriodMapper.position(LocalTime.of(8, 50), p), 1e-4f)
        // 09:00 在第 2 节（08:55-09:40）开始后 5 分钟：1 + 5/45
        assertEquals(1f + 5f / 45f, PeriodMapper.position(LocalTime.of(9, 0), p), 1e-4f)
        assertEquals(2f, PeriodMapper.position(LocalTime.of(10, 0), p), 1e-4f)
        // 午休（11:40-13:30）压成第 4、5 节之间的边界
        assertEquals(4f, PeriodMapper.position(LocalTime.of(12, 0), p), 1e-4f)
        assertEquals(0f, PeriodMapper.position(LocalTime.of(6, 30), p), 1e-4f)
        assertEquals(12f, PeriodMapper.position(LocalTime.of(22, 0), p), 1e-4f)
    }

    @Test
    fun `整段落在课间的日程仍有最小跨度`() {
        val (top, bottom) = PeriodMapper.span(LocalTime.of(12, 0), LocalTime.of(13, 0), p)
        assertEquals(4f, top, 1e-4f)
        assertEquals(4.1f, bottom, 1e-4f)
        val (t2, b2) = PeriodMapper.span(LocalTime.of(8, 0), LocalTime.of(9, 0), p)
        assertEquals(0f, t2, 1e-4f)
        assertEquals(1f + 5f / 45f, b2, 1e-4f)
    }

    @Test
    fun `作息之外贴到最近的一节`() {
        assertEquals(1..1, PeriodMapper.periodsFor(LocalTime.of(6, 30), LocalTime.of(7, 30), p))
        assertEquals(12..12, PeriodMapper.periodsFor(LocalTime.of(22, 0), LocalTime.of(23, 0), p))
        // 正好落在午休（11:40-13:30）：贴到下一节
        assertEquals(5..5, PeriodMapper.periodsFor(LocalTime.of(12, 0), LocalTime.of(13, 0), p))
    }
}
