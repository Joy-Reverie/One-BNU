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
    fun `时刻按行内比例定位，课间算进上一行，长间隔贴下一节上沿`() {
        assertEquals(0f, PeriodMapper.position(LocalTime.of(8, 0), p), 1e-4f)
        // 第 1 节的行覆盖 08:00-08:55（含 10 分钟课间）：08:45 下课在 45/55 处，08:50 在课间里
        assertEquals(45f / 55f, PeriodMapper.position(LocalTime.of(8, 45), p), 1e-4f)
        assertEquals(50f / 55f, PeriodMapper.position(LocalTime.of(8, 50), p), 1e-4f)
        // 第 2 节的行覆盖 08:55-10:00（含 20 分钟大课间）
        assertEquals(1f + 5f / 65f, PeriodMapper.position(LocalTime.of(9, 0), p), 1e-4f)
        assertEquals(2f, PeriodMapper.position(LocalTime.of(10, 0), p), 1e-4f)
        // 午休（11:40-13:30）超过 30 分钟，不并入上一行，落在里面的时刻贴到第 5 节上沿
        assertEquals(4f, PeriodMapper.position(LocalTime.of(12, 0), p), 1e-4f)
        assertEquals(4f, PeriodMapper.position(LocalTime.of(13, 30), p), 1e-4f)
        // 晚上开课前的 50 分钟同理
        assertEquals(8f, PeriodMapper.position(LocalTime.of(17, 30), p), 1e-4f)
        assertEquals(0f, PeriodMapper.position(LocalTime.of(6, 30), p), 1e-4f)
        assertEquals(12f, PeriodMapper.position(LocalTime.of(22, 0), p), 1e-4f)
    }

    @Test
    fun `时长相同的两段高度基本一致`() {
        fun height(from: LocalTime, to: LocalTime): Float {
            val (top, bottom) = PeriodMapper.span(from, to, p)
            return bottom - top
        }
        // 下午两段各一小时：几乎等高（差 1.5% 以内）
        val a = height(LocalTime.of(14, 0), LocalTime.of(15, 0))
        val b = height(LocalTime.of(15, 0), LocalTime.of(16, 0))
        assertEquals(a, b, 0.02f)
        // 上午跨过 20 分钟大课间的一对差得最多，也从 1.11 : 0.89 收敛到 1.08 : 0.92
        val c = height(LocalTime.of(8, 0), LocalTime.of(9, 0))
        val d = height(LocalTime.of(9, 0), LocalTime.of(10, 0))
        assertEquals(1f + 5f / 65f, c, 1e-4f)
        assertEquals(1f - 5f / 65f, d, 1e-4f)
    }

    @Test
    fun `整段落在午休里的日程仍有最小跨度`() {
        val (top, bottom) = PeriodMapper.span(LocalTime.of(12, 0), LocalTime.of(13, 0), p)
        assertEquals(4f, top, 1e-4f)
        assertEquals(4.1f, bottom, 1e-4f)
        // 只落在 10 分钟课间里的日程现在有真实高度（10/55 行），不再靠最小跨度兜底
        val (t2, b2) = PeriodMapper.span(LocalTime.of(8, 45), LocalTime.of(8, 55), p)
        assertEquals(45f / 55f, t2, 1e-4f)
        assertEquals(1f, b2, 1e-4f)
    }

    @Test
    fun `作息之外贴到最近的一节`() {
        assertEquals(1..1, PeriodMapper.periodsFor(LocalTime.of(6, 30), LocalTime.of(7, 30), p))
        assertEquals(12..12, PeriodMapper.periodsFor(LocalTime.of(22, 0), LocalTime.of(23, 0), p))
        // 正好落在午休（11:40-13:30）：贴到下一节
        assertEquals(5..5, PeriodMapper.periodsFor(LocalTime.of(12, 0), LocalTime.of(13, 0), p))
    }
}
