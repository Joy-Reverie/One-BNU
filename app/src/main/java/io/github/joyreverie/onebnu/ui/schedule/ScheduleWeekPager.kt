package io.github.joyreverie.onebnu.ui.schedule

import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** 分页负责单指水平拖动与回弹，网格继续负责纵向滚动和双指缩放。 */
@Composable
internal fun ScheduleWeekPager(
    schedule: Schedule,
    state: ScheduleUiState,
    zoom: Float,
    onWeekChange: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit,
    onClick: (Course, ClassSession) -> Unit,
    onEventClick: (PersonalEvent) -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = state.week - 1, pageCount = { state.maxWeek })
    val change = rememberUpdatedState(onWeekChange)
    LaunchedEffect(state.week) {
        if (pager.settledPage != state.week - 1) {
            pager.animateScrollToPage(state.week - 1, animationSpec = tween(280))
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect {
            change.value(it + 1)
        }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
        ScheduleGrid(
            schedule, state.copy(week = page + 1), zoom, onZoom, onZoomEnd, onClick, onEventClick,
        )
    }
}
