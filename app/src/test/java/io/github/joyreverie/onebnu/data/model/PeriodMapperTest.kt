package io.github.joyreverie.onebnu.data.model

import io.github.joyreverie.onebnu.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `默认作息通过校验，自定义作息按节次顺序校验`() {
        assertEquals(null, PeriodMapper.validate(p))

        // 整体挪早、每节 50 分钟：合法
        val early = List(12) { i ->
            val start = LocalTime.of(7, 30).plusMinutes(i * 60L)
            "${PeriodMapper.format(start)}-${PeriodMapper.format(start.plusMinutes(50))}"
        }
        assertEquals(null, PeriodMapper.validate(early))

        // 下课不晚于上课
        assertEquals(
            "第 2 节的下课时间要晚于上课时间",
            PeriodMapper.validate(listOf("08:00-08:45", "08:55-08:55")),
        )
        // 上课早于前一节下课：网格会画错，必须挡住
        assertEquals(
            "第 3 节的上课时间不能早于第 2 节下课",
            PeriodMapper.validate(listOf("08:00-08:45", "08:55-09:40", "09:30-10:15")),
        )
        // 首尾相接不算冲突
        assertEquals(null, PeriodMapper.validate(listOf("08:00-08:45", "08:45-09:30")))
        assertEquals("第 1 节的时间格式不对", PeriodMapper.validate(listOf("八点-九点")))
    }

    @Test
    fun `识别出没有网格高度的长空档`() {
        // 午休 11:40–13:30：整段落在里面的日程在网格里没有自己的位置
        assertTrue(PeriodMapper.insideLongBreak(LocalTime.of(12, 0), LocalTime.of(12, 15), p))
        assertTrue(PeriodMapper.insideLongBreak(LocalTime.of(11, 40), LocalTime.of(13, 30), p))
        // 第一节之前同理
        assertTrue(PeriodMapper.insideLongBreak(LocalTime.of(7, 0), LocalTime.of(7, 45), p))
        // 傍晚 17:10–18:00 的空档
        assertTrue(PeriodMapper.insideLongBreak(LocalTime.of(17, 20), LocalTime.of(17, 50), p))
        // 伸进下午第一节的日程是真重叠，不算空档
        assertFalse(PeriodMapper.insideLongBreak(LocalTime.of(13, 0), LocalTime.of(14, 0), p))
        // 10 分钟、20 分钟的课间本来就并进上一行，不是长空档
        assertFalse(PeriodMapper.insideLongBreak(LocalTime.of(8, 45), LocalTime.of(8, 55), p))
        assertFalse(PeriodMapper.insideLongBreak(LocalTime.of(9, 40), LocalTime.of(10, 0), p))
        // 正常上课时段
        assertFalse(PeriodMapper.insideLongBreak(LocalTime.of(8, 0), LocalTime.of(9, 0), p))
    }
}
