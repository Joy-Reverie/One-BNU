package io.github.joyreverie.onebnu.ui.home

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import io.github.joyreverie.onebnu.ui.theme.ScreenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 首页「校园服务」默认展示与折叠：收起时整行整行地放，没改过就排满，改过的按首页顺序、不超上限。 */
class ServicePinningTest {

    private val thirteen = (1..13).map { "k$it" }

    /** 手机竖屏：三行四列。 */
    private val phone = 12

    /** 平板横屏：两行八列。 */
    private val tablet = 16

    @Test
    fun `上限总是整行：手机竖屏三行，平板和横屏两行`() {
        assertEquals(12, ScreenInfo(WindowWidthSizeClass.Compact, 411, 914).pinnedServiceLimit) // 四列 × 三行
        assertEquals(9, ScreenInfo(WindowWidthSizeClass.Compact, 320, 640).pinnedServiceLimit) // 窄屏三列 × 三行
        assertEquals(12, ScreenInfo(WindowWidthSizeClass.Medium, 800, 1280).pinnedServiceLimit) // 平板竖屏六列 × 两行
        assertEquals(16, ScreenInfo(WindowWidthSizeClass.Medium, 780, 360).pinnedServiceLimit) // 手机横屏八列 × 两行
        assertEquals(16, ScreenInfo(WindowWidthSizeClass.Expanded, 1280, 800).pinnedServiceLimit) // 平板横屏八列 × 两行
    }

    @Test
    fun `没改过：按顺序排满，手机上第 13 个折叠，平板横屏全放得下`() {
        assertEquals(thirteen.take(12), effectivePinned(thirteen, null, phone))
        assertEquals(thirteen, effectivePinned(thirteen, null, tablet))
        assertEquals(listOf("a", "b"), effectivePinned(listOf("a", "b"), null, phone))
    }

    @Test
    fun `改过：按首页顺序排，不认识的键不算`() {
        assertEquals(listOf("k2", "k9"), effectivePinned(thirteen, setOf("k9", "gone", "k2"), phone))
        assertEquals(emptyList<String>(), effectivePinned(thirteen, emptySet(), phone))
    }

    @Test
    fun `存的超了上限也只取前面的：平板上全挑了，换到小窗口里照样截断`() {
        assertEquals(thirteen, effectivePinned(thirteen, thirteen.toSet(), tablet))
        assertEquals(thirteen.take(12), effectivePinned(thirteen, thirteen.toSet(), phone))
        assertEquals(thirteen.take(9), effectivePinned(thirteen, thirteen.toSet(), 9))
    }

    @Test
    fun `没改过时收起一个，存下其余的`() {
        assertEquals((thirteen.take(12) - "k3").toSet(), withPinned(thirteen, null, "k3", pinned = false, limit = phone))
        assertEquals((thirteen - "k3").toSet(), withPinned(thirteen, null, "k3", pinned = false, limit = tablet))
    }

    @Test
    fun `放满时再放一个被拒，先收起一个才放得进`() {
        assertNull(withPinned(thirteen, null, "k13", pinned = true, limit = phone))
        val eleven = withPinned(thirteen, null, "k1", pinned = false, limit = phone)
        assertEquals(
            (thirteen.take(12) - "k1" + "k13").toSet(),
            withPinned(thirteen, eleven, "k13", pinned = true, limit = phone),
        )
    }

    @Test
    fun `平板横屏上限比入口多，收起再放回都不会放满`() {
        val twelve = withPinned(thirteen, null, "k13", pinned = false, limit = tablet)
        assertEquals(thirteen.take(12).toSet(), twelve)
        assertEquals(thirteen.toSet(), withPinned(thirteen, twelve, "k13", pinned = true, limit = tablet))
    }

    @Test
    fun `重复放、重复收都不变；已放满时再放已有的不算超`() {
        assertEquals(setOf("k1", "k2"), withPinned(thirteen, setOf("k1", "k2"), "k2", pinned = true, limit = phone))
        assertEquals(setOf("k1"), withPinned(thirteen, setOf("k1"), "k5", pinned = false, limit = phone))
        assertEquals(thirteen.take(12).toSet(), withPinned(thirteen, null, "k12", pinned = true, limit = phone))
    }

    @Test
    fun `写回时清掉这个校区没有的旧键`() {
        assertEquals(setOf("k1", "k4"), withPinned(thirteen, setOf("k1", "gone"), "k4", pinned = true, limit = phone))
    }

    @Test
    fun `全部收起后是空集合，不是没改过`() {
        assertEquals(emptySet<String>(), withPinned(listOf("a"), setOf("a"), "a", pinned = false, limit = phone))
        assertEquals(emptyList<String>(), effectivePinned(listOf("a"), emptySet(), phone))
    }
}
