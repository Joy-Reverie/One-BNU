package io.github.joyreverie.onebnu.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {

    @Test
    fun `有快照就先用快照，内容是空的也算`() {
        assertTrue(CachePolicy.preferSnapshot(hasSnapshot = true, forceRefresh = false))
        // 没有快照只能去连教务
        assertFalse(CachePolicy.preferSnapshot(hasSnapshot = false, forceRefresh = false))
        // 显式刷新 / 后台刷新绕过快照
        assertFalse(CachePolicy.preferSnapshot(hasSnapshot = true, forceRefresh = true))
    }

    @Test
    fun `第一次查到空也要存，之后的空不覆盖好数据`() {
        // 还没有任何快照：把「查到的就是空」存下来，下次进页面不用再等一次网络
        assertTrue(CachePolicy.shouldWrite(liveHasContent = false, snapshotHasContent = false))
        // 已有有内容的快照：教务瞬态返回空表时不许覆盖
        assertFalse(CachePolicy.shouldWrite(liveHasContent = false, snapshotHasContent = true))
        // 有内容一律写
        assertTrue(CachePolicy.shouldWrite(liveHasContent = true, snapshotHasContent = false))
        assertTrue(CachePolicy.shouldWrite(liveHasContent = true, snapshotHasContent = true))
    }
}
