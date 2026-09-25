package io.github.joyreverie.onebnu.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 首页「校园服务」默认展示与折叠：没改过取前 12 个，改过的按首页顺序、不超上限。 */
class ServicePinningTest {

    private val thirteen = (1..13).map { "k$it" }

    @Test
    fun `没改过：按顺序取前 12 个，第 13 个折叠`() {
        assertEquals(thirteen.take(12), effectivePinned(thirteen, null))
        assertEquals(listOf("a", "b"), effectivePinned(listOf("a", "b"), null))
    }

    @Test
    fun `改过：按首页顺序排，不认识的键不算`() {
        assertEquals(listOf("k2", "k9"), effectivePinned(thirteen, setOf("k9", "gone", "k2")))
        assertEquals(emptyList<String>(), effectivePinned(thirteen, emptySet()))
    }

    @Test
    fun `存的超了上限也只取前 12 个`() {
        assertEquals(thirteen.take(12), effectivePinned(thirteen, thirteen.toSet()))
    }

    @Test
    fun `没改过时收起一个，存下其余 11 个`() {
        assertEquals((thirteen.take(12) - "k3").toSet(), withPinned(thirteen, null, "k3", pinned = false))
    }

    @Test
    fun `放满 12 个时再放一个被拒，先收起一个才放得进`() {
        assertNull(withPinned(thirteen, null, "k13", pinned = true))
        val eleven = withPinned(thirteen, null, "k1", pinned = false)
        assertEquals(
            (thirteen.take(12) - "k1" + "k13").toSet(),
            withPinned(thirteen, eleven, "k13", pinned = true),
        )
    }

    @Test
    fun `重复放、重复收都不变；已放满时再放已有的不算超`() {
        assertEquals(setOf("k1", "k2"), withPinned(thirteen, setOf("k1", "k2"), "k2", pinned = true))
        assertEquals(setOf("k1"), withPinned(thirteen, setOf("k1"), "k5", pinned = false))
        assertEquals(thirteen.take(12).toSet(), withPinned(thirteen, null, "k12", pinned = true))
    }

    @Test
    fun `写回时清掉这个校区没有的旧键`() {
        assertEquals(setOf("k1", "k4"), withPinned(thirteen, setOf("k1", "gone"), "k4", pinned = true))
    }

    @Test
    fun `全部收起后是空集合，不是没改过`() {
        assertEquals(emptySet<String>(), withPinned(listOf("a"), setOf("a"), "a", pinned = false))
        assertEquals(emptyList<String>(), effectivePinned(listOf("a"), emptySet()))
    }
}
