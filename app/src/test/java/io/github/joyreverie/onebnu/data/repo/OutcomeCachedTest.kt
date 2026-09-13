package io.github.joyreverie.onebnu.data.repo

import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeCachedTest {

    private val cached = DataFreshness(savedAt = 1_700_000_000_000L, cached = true)
    private val live = DataFreshness(savedAt = 1_700_000_000_000L, cached = false)

    @Test
    fun `空结果也要能区分来自缓存还是刚查到的`() {
        // 「本轮没有你的考试」来自快照时，页面同样要提示可能需要校园网更新
        assertTrue(Outcome.Empty("该轮次下没有你的考试安排", cached).cached)
        assertFalse(Outcome.Empty("该轮次下没有你的考试安排", live).cached)
        assertFalse(Outcome.Empty("该轮次下没有你的考试安排").cached)
    }

    @Test
    fun `有数据与出错时的判定`() {
        assertTrue(Outcome.Ok(listOf(1), cached).cached)
        assertFalse(Outcome.Ok(listOf(1), live).cached)
        assertFalse(Outcome.Error("网络异常").cached)
    }
}
