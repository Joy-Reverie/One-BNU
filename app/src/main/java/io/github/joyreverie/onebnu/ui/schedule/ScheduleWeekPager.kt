package io.github.joyreverie.onebnu.ui.schedule

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule

/**
 * 一周七列的分页部分。
 *
 * [pager] 由 [ScheduleScreen] 持有，是「当前显示第几周」的唯一真源：箭头与周次菜单直接驱动它，
 * 周次再从 `currentPage` 读回来。此前的写法是「周次变了 → LaunchedEffect 滚动到那一页」，
 * 而滚动途中每越过一页又回写一次周次，等于自己把正在跑的动画取消掉，跨多周选择会停在半路。
 *
 * 星期表头、左侧刻度与纵向滚动都留在分页之外，因此切周时刻度不横移、纵向位置也不回到顶部。
 */
@Composable
internal fun RowScope.ScheduleWeekPager(
    schedule: Schedule,
    state: ScheduleUiState,
    pager: PagerState,
    rowHeight: Dp,
    titleSize: TextUnit,
    subSize: TextUnit,
    onClick: (Course, ClassSession) -> Unit,
    onEventClick: (PersonalEvent) -> Unit = {},
) {
    HorizontalPager(
        state = pager,
        modifier = Modifier.weight(1f).fillMaxWidth().height(rowHeight * ScheduleLayout.PERIODS),
        key = { it },
        verticalAlignment = Alignment.Top,
    ) { page ->
        DayColumns(
            schedule = schedule,
            state = state.copy(week = page + 1),
            rowHeight = rowHeight,
            titleSize = titleSize,
            subSize = subSize,
            modifier = Modifier.fillMaxSize(),
            onClick = onClick,
            onEventClick = onEventClick,
        )
    }
}
