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
    fun `作息之外贴到最近的一节`() {
        assertEquals(1..1, PeriodMapper.periodsFor(LocalTime.of(6, 30), LocalTime.of(7, 30), p))
        assertEquals(12..12, PeriodMapper.periodsFor(LocalTime.of(22, 0), LocalTime.of(23, 0), p))
        // 正好落在午休（11:40-13:30）：贴到下一节
        assertEquals(5..5, PeriodMapper.periodsFor(LocalTime.of(12, 0), LocalTime.of(13, 0), p))
    }
}
