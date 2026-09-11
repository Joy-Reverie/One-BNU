package io.github.joyreverie.onebnu.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.ui.Routes
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.ListSkeleton
import io.github.joyreverie.onebnu.ui.components.SectionHeader
import io.github.joyreverie.onebnu.ui.event.EventEditorSheet
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.courseAccent
import io.github.joyreverie.onebnu.ui.theme.listPadding
import io.github.joyreverie.onebnu.ui.theme.GlowBlob
import java.time.LocalDate
import java.time.LocalTime

private data class Entry(
    val label: String,
    val icon: ImageVector,
    val route: String,
    val tint: Int,
)

private val BEIJING_ENTRIES = listOf(
    Entry("考试安排", Icons.Outlined.EditCalendar, Routes.EXAM, 0),
    Entry("空闲教室", Icons.Outlined.MeetingRoom, Routes.CLASSROOM, 1),
    Entry("校历周次", Icons.Outlined.CalendarMonth, Routes.CALENDAR, 2),
    Entry("图书馆", Icons.Outlined.LocalLibrary, Routes.web("图书馆", "http://www.lib.bnu.edu.cn/", false), 3),
    Entry("校园平面图", Icons.Outlined.Map, Routes.MAP, 4),
    Entry("校内联系", Icons.Outlined.Phone, Routes.PHONE, 5),
    Entry("数字京师", Icons.Outlined.Public, Routes.web("数字京师门户", "https://one.bnu.edu.cn/tp_nup/", true), 6),
    Entry("教务系统", Icons.Outlined.AccountBalance, Routes.web("教务系统", "http://zyfw.bnu.edu.cn/", true), 7),
)

private val ZHUHAI_ENTRIES = listOf(
    Entry("考试安排", Icons.Outlined.EditCalendar, Routes.EXAM, 0),
    Entry("空闲教室", Icons.Outlined.MeetingRoom, Routes.CLASSROOM, 1),
    Entry("校历周次", Icons.Outlined.CalendarMonth, Routes.CALENDAR, 2),
    Entry("图书馆", Icons.Outlined.LocalLibrary, Routes.web("珠海图书馆", "https://library.bnuzh.edu.cn/", false), 3),
    Entry("珠海门户", Icons.Outlined.Public, Routes.web("珠海门户", "https://one.bnuzh.edu.cn/", false), 4),
    Entry("珠海教务", Icons.Outlined.AccountBalance, Routes.web("珠海教务系统", "https://jwxt.bnuzh.edu.cn/caslogin", true), 5),
)

/** 今日时间轴上的一项：一节课或一条日程，统一按开始时刻排序。 */
private data class TimelineItem(
    val startAt: LocalTime,
    val start: String,
    val end: String,
    val title: String,
    val detail: String,
    val accent: Color,
    val event: PersonalEvent?,
)

/** 日程编辑：null 不显示；[PersonalEvent] 为空表示新建。 */
private data class EditorTarget(val event: PersonalEvent?)

@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val store = ServiceLocator.events
    HomeContent(
        s = s,
        onRetry = { vm.refresh() },
        onNavigate = { nav.navigate(it) },
        onSaveEvent = { store.upsert(it) },
        onDeleteEvent = { store.delete(it.id) },
        campus = ServiceLocator.activeCampus,
    )
}

/** 首页的纯展示部分，状态与回调都从外面来，方便 debug 包不登录直接预览。 */
@Composable
internal fun HomeContent(
    s: HomeUiState,
    onRetry: () -> Unit,
    onNavigate: (String) -> Unit,
    onSaveEvent: (PersonalEvent) -> Unit,
    onDeleteEvent: (PersonalEvent) -> Unit,
    campus: Campus = Campus.BEIJING,
) {
    val screen = LocalScreenInfo.current
    val pad = screen.listPadding(top = 0.dp, bottom = 24.dp)
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Hero 需要顶到屏幕边缘，因此不吃列表的左右内边距
        item { HeroCard(s) }

        item {
            Box(Modifier.padding(pad.horizontalOnly())) {
                SectionHeader(
                    "今日课程",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val summary = listOfNotNull(
                                s.todayCourses.size.takeIf { it > 0 }?.let { "$it 节" },
                                s.todayEvents.size.takeIf { it > 0 }?.let { "$it 日程" },
                            ).joinToString(" · ")
                            if (summary.isNotBlank()) {
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            TextButton(
                                onClick = { editor = EditorTarget(null) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("日程", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                )
            }
        }
        item {
            Box(Modifier.padding(pad.horizontalOnly())) {
                when {
                    s.loading && s.todayEvents.isEmpty() -> ListSkeleton(rows = 2)
                    s.error != null && s.todayEvents.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp)) {
                        ErrorBox(s.error!!) { onRetry() }
                    }
                    s.todayCourses.isEmpty() && s.todayEvents.isEmpty() -> EmptyToday(s.todayHint)
                    else -> TodayTimeline(s, onEventClick = { editor = EditorTarget(it) })
                }
            }
        }

        item {
            Box(Modifier.padding(pad.horizontalOnly())) { SectionHeader("校园服务") }
        }
        item {
            Box(Modifier.padding(pad.horizontalOnly())) {
                ServiceGrid(screen.serviceColumns, campus) { onNavigate(it) }
            }
        }
    }

    editor?.let { target ->
        EventEditorSheet(
            initial = target.event,
            defaultDate = LocalDate.now(),
            onDismiss = { editor = null },
            onSave = {
                onSaveEvent(it)
                editor = null
            },
            onDelete = {
                onDeleteEvent(it)
                editor = null
            },
        )
    }
}

/** 只取左右内边距，用于让 Hero 通栏而其余内容对齐。 */
@Composable
private fun androidx.compose.foundation.layout.PaddingValues.horizontalOnly():
    androidx.compose.foundation.layout.PaddingValues {
    val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
    return androidx.compose.foundation.layout.PaddingValues(
        start = calculateStartPadding(ld),
        end = calculateEndPadding(ld),
    )
}

/** 顶部主视觉：渐变 + 模糊光斑，信息按「问候 / 姓名 / 学期周次」三级排布。 */
@Composable
private fun HeroCard(s: HomeUiState) {
    val accents = LocalAccents.current
    val shape = RoundedCornerShape(bottomStart = Shape.cardLarge, bottomEnd = Shape.cardLarge)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accents.heroGradient),
    ) {
        GlowBlob(
            Color.White,
            Modifier.align(Alignment.TopEnd).size(240.dp).offset(x = 70.dp, y = (-90).dp),
            alpha = 0.20f,
        )
        GlowBlob(
            Color.White,
            Modifier.align(Alignment.BottomEnd).size(180.dp).offset(x = 50.dp, y = 70.dp),
            alpha = 0.13f,
        )

        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 24.dp),
        ) {
            Text(
                s.greeting,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.userName.ifBlank { "同学" },
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            if (s.subtitle.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    s.subtitle.split(" · ").take(3).forEachIndexed { i, part ->
                        if (i > 0) Spacer(Modifier.width(8.dp))
                        HeroChip(part)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/** 今日课程与日程用同一条时间轴呈现：左侧时间刻度 + 竖线圆点。日程带「日程」标签，点开可改。 */
@Composable
private fun TodayTimeline(s: HomeUiState, onEventClick: (PersonalEvent) -> Unit) {
    val items = remember(s.todayCourses, s.todayEvents, s.periodTimes) {
        val courses = s.todayCourses.map { item ->
            val startSpec = s.periodTimes.getOrNull(item.session.startPeriod - 1)?.substringBefore('-').orEmpty()
            val endSpec = s.periodTimes.getOrNull(item.session.endPeriod - 1)?.substringAfter('-').orEmpty()
            TimelineItem(
                startAt = PeriodMapper.parse(startSpec) ?: LocalTime.MIDNIGHT,
                start = startSpec.ifBlank { "—" },
                end = endSpec,
                title = item.course.name,
                detail = listOfNotNull(
                    item.session.location.takeIf { it.isNotBlank() },
                    item.course.teacherLabel.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                accent = Color.Unspecified,
                event = null,
            )
        }
        val events = s.todayEvents.map { e ->
            TimelineItem(
                startAt = e.start,
                start = e.start.format(PersonalEvent.HM),
                end = e.end.format(PersonalEvent.HM),
                title = e.title,
                detail = listOfNotNull(
                    e.location.takeIf { it.isNotBlank() },
                    e.note.takeIf { it.isNotBlank() },
                    e.repeatLabel.takeIf { e.repeats },
                ).joinToString(" · "),
                accent = Color.Unspecified,
                event = e,
            )
        }
        (courses + events).sortedBy { it.startAt }
    }

    // 卡片高度只增不减：一节课也撑到与空态同高，行在其中居中；课多了再往下长
    BnuCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TODAY_CARD_MIN)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            items.forEachIndexed { i, item ->
                TimelineRow(
                    item = item,
                    first = i == 0,
                    last = i == items.lastIndex,
                    onClick = item.event?.let { e -> { onEventClick(e) } },
                )
            }
        }
    }
}

/** 时间轴的一行：时间刻度、轨道（线随行高伸缩，首尾行不画多余的段）、标题与说明。 */
@Composable
private fun TimelineRow(item: TimelineItem, first: Boolean, last: Boolean, onClick: (() -> Unit)?) {
    val accent = if (item.event != null) MaterialTheme.colorScheme.tertiary else courseAccent(item.title)
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            Modifier.width(46.dp).padding(top = ROW_PAD),
            horizontalAlignment = Alignment.End,
        ) {
            Text(item.start, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(item.end, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        Column(
            Modifier.width(26.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(2.dp).height(ROW_PAD + 4.dp).background(if (first) Color.Transparent else line))
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else line))
        }

        Column(Modifier.weight(1f).padding(vertical = ROW_PAD)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.event != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50)) {
                        Text(
                            "日程",
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
            if (item.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 今日课程卡的最小高度，与空态卡一致，一节课时也不会缩成一条。 */
private val TODAY_CARD_MIN = 164.dp
private val ROW_PAD = 12.dp

@Composable
private fun EmptyToday(hint: String) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = TODAY_CARD_MIN).padding(vertical = 24.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("☕", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            Text("今天没有课", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ServiceGrid(columns: Int, campus: Campus, onNavigate: (String) -> Unit) {
    val entries = if (campus == Campus.BEIJING) BEIJING_ENTRIES else ZHUHAI_ENTRIES
    BnuCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(vertical = 18.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            entries.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { e ->
                        EntryTile(e, Modifier.weight(1f)) { onNavigate(e.route) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EntryTile(e: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accents = LocalAccents.current
    val bg = accents.courseColors[e.tint % accents.courseColors.size]
    val fg = accents.courseAccents[e.tint % accents.courseAccents.size]

    Column(
        modifier
            .clip(RoundedCornerShape(Shape.tile))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(e.icon, null, Modifier.size(23.dp), tint = fg)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            e.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
