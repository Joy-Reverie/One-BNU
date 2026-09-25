package io.github.joyreverie.onebnu.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Email
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.ui.components.OnResumed
import io.github.joyreverie.onebnu.core.net.OneVpnSso
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.ui.Routes
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.ListSkeleton
import io.github.joyreverie.onebnu.ui.components.SectionHeader
import io.github.joyreverie.onebnu.ui.components.dashedOutline
import io.github.joyreverie.onebnu.ui.event.EventEditorSheet
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.courseAccent
import io.github.joyreverie.onebnu.ui.theme.listPadding
import io.github.joyreverie.onebnu.ui.theme.GlowBlob
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * 首页「校园服务」里的一个入口。[key] 是稳定键：设置里的「校园服务」按它记哪些入口默认展示，
 * 以后改名、换图标、调顺序都不能动它。
 */
internal data class ServiceEntry(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val route: String,
    val tint: Int,
)

private const val COURSE_CENTER = OneVpnSso.COURSE_CENTER_BASE

private val BEIJING_ENTRIES = listOf(
    ServiceEntry("exam", "考试安排", Icons.Outlined.EditCalendar, Routes.EXAM, 0),
    ServiceEntry("classroom", "空闲教室", Icons.Outlined.MeetingRoom, Routes.CLASSROOM, 1),
    ServiceEntry("calendar", "校历周次", Icons.Outlined.CalendarMonth, Routes.CALENDAR, 2),
    ServiceEntry("library", "图书馆", Icons.Outlined.LocalLibrary, Routes.web("图书馆", "http://www.lib.bnu.edu.cn/", false), 3),
    ServiceEntry("map", "校园平面图", Icons.Outlined.Map, Routes.MAP, 4),
    ServiceEntry("phone", "校内联系", Icons.Outlined.Phone, Routes.PHONE, 5),
    ServiceEntry(
        "portal",
        "数字京师",
        Icons.Outlined.Public,
        Routes.portalWeb("数字京师门户", "https://one.bnu.edu.cn/tp_nup/index.html", true),
        6,
    ),
    // 原入口在数字京师首页的「邮件」卡片；这里直接要那张卡片用的免密链接进学生邮箱
    ServiceEntry("mail", "师大邮箱", Icons.Outlined.Email, Routes.MAIL, 3),
    // 云盘账号密码与数字京师相同，页内先用已保存的账号替它登录，免得再输一次
    ServiceEntry("pan", "师大云盘", Icons.Outlined.Cloud, Routes.PAN, 6),
    ServiceEntry("academic", "教务系统", Icons.Outlined.AccountBalance, Routes.web("教务系统", "http://zyfw.bnu.edu.cn/", true), 7),
    ServiceEntry("pyfa", "培养方案", Icons.Outlined.AccountTree, Routes.oneVpnWeb("培养方案", "$COURSE_CENTER/pyfa"), 0),
    ServiceEntry("jxsc", "教学手册", Icons.AutoMirrored.Outlined.MenuBook, Routes.oneVpnWeb("教学手册", "$COURSE_CENTER/jxsc"), 1),
    ServiceEntry("jxdg", "教学大纲", Icons.Outlined.Description, Routes.oneVpnWeb("教学大纲", "$COURSE_CENTER/jxdg"), 2),
)

private val ZHUHAI_ENTRIES = listOf(
    ServiceEntry("exam", "考试安排", Icons.Outlined.EditCalendar, Routes.EXAM, 0),
    ServiceEntry("classroom", "空闲教室", Icons.Outlined.MeetingRoom, Routes.CLASSROOM, 1),
    ServiceEntry("calendar", "校历周次", Icons.Outlined.CalendarMonth, Routes.CALENDAR, 2),
    ServiceEntry("library", "图书馆", Icons.Outlined.LocalLibrary, Routes.web("珠海图书馆", "https://library.bnuzh.edu.cn/", false), 3),
    // 珠海门户的 accessToken Cookie 作用域是 /nup；根路径会被 aTrust 网关接管，
    // 因此入口必须落在 /nup/，WebView 仍复用当前珠海 CAS 会话免二次输入。
    ServiceEntry("portal", "珠海门户", Icons.Outlined.Public, Routes.web("珠海门户", "https://one.bnuzh.edu.cn/nup/", true), 4),
    ServiceEntry("academic", "珠海教务", Icons.Outlined.AccountBalance, Routes.web("珠海教务系统", "https://jwxt.bnuzh.edu.cn/caslogin", true), 5),
    // 课程中心（培养方案 / 教学手册 / 教学大纲）是两校区共用的一套系统，
    // 但它认的是北京 CAS：珠海走普通网页入口，由课程中心自己决定要不要登录，
    // 绝不把珠海账号送去北京认证域（OneVpnSso 对珠海本来也直接拒绝）。
    ServiceEntry("pyfa", "培养方案", Icons.Outlined.AccountTree, Routes.web("培养方案", "$COURSE_CENTER/pyfa", false), 0),
    ServiceEntry("jxsc", "教学手册", Icons.AutoMirrored.Outlined.MenuBook, Routes.web("教学手册", "$COURSE_CENTER/jxsc", false), 1),
    ServiceEntry("jxdg", "教学大纲", Icons.Outlined.Description, Routes.web("教学大纲", "$COURSE_CENTER/jxdg", false), 2),
)

/** 这个校区的全部入口，按首页上的顺序；哪些默认展示、哪些折叠见 [effectivePinned]。 */
internal fun serviceEntries(campus: Campus): List<ServiceEntry> =
    if (campus == Campus.BEIJING) BEIJING_ENTRIES else ZHUHAI_ENTRIES

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
    OnResumed { vm.onResumed() }
    val store = ServiceLocator.events
    val pinnedEntries by ServiceLocator.settings.pinnedServiceEntriesFlow.collectAsState()
    HomeContent(
        s = s,
        onRetry = { vm.refresh(forceRefresh = true) },
        onNavigate = { nav.navigate(it) },
        onSaveEvent = { store.upsert(it) },
        onDeleteEvent = { store.delete(it.id) },
        campus = ServiceLocator.activeCampus,
        pinnedEntries = pinnedEntries,
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
    /** 设置里挑的默认展示入口键（见 [ServiceEntry.key]）；null 表示没改过，见 [effectivePinned]。 */
    pinnedEntries: Set<String>? = null,
    /** 「校园服务」一打开是否就是展开的；只有 debug 预览会传 true。 */
    expandServices: Boolean = false,
) {
    val screen = LocalScreenInfo.current
    val pad = screen.listPadding(top = 0.dp, bottom = 24.dp)
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    // 默认展示的在前、折叠的在后，各自保持首页顺序
    val (pinnedServices, foldedServices) = remember(campus, pinnedEntries) {
        val entries = serviceEntries(campus)
        val pinned = effectivePinned(entries.map { it.key }, pinnedEntries).toSet()
        entries.partition { it.key in pinned }
    }
    // 点开一个服务再返回时保持展开；重新打开应用回到收起
    var servicesExpanded by rememberSaveable { mutableStateOf(expandServices) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = HOME_LIST_BOTTOM),
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
                        ErrorBox(s.error) { onRetry() }
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
                ServiceGrid(
                    screen.serviceColumns,
                    pinnedServices,
                    foldedServices,
                    expanded = servicesExpanded,
                    onToggle = { servicesExpanded = !servicesExpanded },
                    onNavigate = onNavigate,
                )
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
@OptIn(ExperimentalLayoutApi::class)
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
                color = Color.White.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.userName.ifBlank { "同学" },
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            if (s.subtitle.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                // 系统字体放大后三枚芯片一行放不下，让它们换行而不是把最后一枚挤成两行
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.subtitle.split(" · ").take(3).forEach { HeroChip(it) }
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
        // 系统字体放大到 1.3 倍时 46dp 固定宽会把「08:00」裁成「08:0」，给下限即可
        Column(
            Modifier.widthIn(min = 46.dp).padding(top = ROW_PAD, end = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                item.start,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                item.end,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                softWrap = false,
            )
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

/**
 * 首页「校园服务」卡片。默认只摆 [pinned]（最多 12 个，手机上正好三行四列），其余的 [folded] 收在底部的
 * V 形箭头后面：点开接着往下排成同一张宫格，再点收起。没有折叠的入口就不画箭头。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServiceGrid(
    columns: Int,
    pinned: List<ServiceEntry>,
    folded: List<ServiceEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val click: (ServiceEntry) -> Unit = { onNavigate(it.route) }
    val rows = (pinned + folded).chunked(columns)
    // 默认展示占几行（没排满的那行也算）：那一行里折叠的入口随展开淡入，再往后的行整行展开
    val shownRows = (pinned.size + columns - 1) / columns
    // 宫格在列表最底下，点开后新的一行多半长在屏幕外；展开过程中跟着把卡片连同列表底部的留白挪进屏幕，
    // 停下来和滑到底时一样，免得点了像没反应
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val below = with(LocalDensity.current) { HOME_LIST_BOTTOM.toPx() }
    var revealing by remember { mutableStateOf(false) }

    BnuCard(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .onSizeChanged { size ->
                if (revealing) {
                    scope.launch { requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + below)) }
                }
            },
    ) {
        Column {
            Column(Modifier.padding(horizontal = 8.dp)) {
                if (shownRows > 0) {
                    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(TILE_ROW_GAP)) {
                        rows.take(shownRows).forEachIndexed { r, row ->
                            TileRow(columns, row, click, revealFrom = pinned.size - r * columns, expanded = expanded)
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded && rows.size > shownRows,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column(Modifier.padding(top = TILE_ROW_GAP), verticalArrangement = Arrangement.spacedBy(TILE_ROW_GAP)) {
                        rows.drop(shownRows).forEach { row -> TileRow(columns, row, click) }
                    }
                }
            }
            if (folded.isEmpty()) {
                Spacer(Modifier.height(18.dp))
            } else {
                FoldHandle(
                    expanded,
                    onClick = {
                        revealing = !expanded
                        onToggle()
                    },
                )
            }
        }
    }
}

/**
 * 只看不点的宫格，设置里的首页预览用：[entries] 按列数分行，凑不满 [slots] 个的格子画成虚线空位，
 * 标出默认展示还能放几个。
 */
@Composable
internal fun ServiceSlots(columns: Int, entries: List<ServiceEntry>, slots: Int, modifier: Modifier = Modifier) {
    val cells: List<ServiceEntry?> = entries + List((slots - entries.size).coerceAtLeast(0)) { null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TILE_ROW_GAP)) {
        cells.chunked(columns).forEach { row -> TileRow(columns, row, onClick = null) }
    }
}

/**
 * 宫格的一行：按列数均分，末行不足的用空位补齐，入口始终与上一行对齐；null 画成虚线空位。
 * 下标从 [revealFrom] 起的是折叠的入口，只在 [expanded] 时淡入。[onClick] 为空时只展示、不可点。
 */
@Composable
private fun TileRow(
    columns: Int,
    row: List<ServiceEntry?>,
    onClick: ((ServiceEntry) -> Unit)?,
    revealFrom: Int = row.size,
    expanded: Boolean = true,
) {
    Row(Modifier.fillMaxWidth()) {
        row.forEachIndexed { i, e ->
            when {
                e == null -> EmptySlot(Modifier.weight(1f))
                i < revealFrom -> EntryTile(e, Modifier.weight(1f), onClick = onClick?.let { click -> { click(e) } })
                else -> Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                        EntryTile(e, Modifier.fillMaxWidth(), onClick = onClick?.let { click -> { click(e) } })
                    }
                }
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * 宫格底部的 V 形箭头：点一下展开折叠的入口、箭头翻成朝上，再点收起。
 * [onClick] 为空时只画不点 —— 设置里的首页预览用，底下也不留触摸区，外面的卡片自己有内边距。
 */
@Composable
internal fun FoldHandle(expanded: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "fold")
    Box(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(top = 4.dp, bottom = if (onClick != null) 10.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = when {
                onClick == null -> null
                expanded -> "收起"
                else -> "展开更多服务"
            },
            Modifier.rotate(turn),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 预览里的空位：和入口图标一样大的虚线方块，标签那一行留白，行高与有入口的格子一致。 */
@Composable
private fun EmptySlot(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 6.dp).clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE_ICON)
                .dashedOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), TILE_ICON_CORNER),
        )
        Spacer(Modifier.height(7.dp))
        Text("", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EntryTile(e: ServiceEntry, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .clip(RoundedCornerShape(Shape.tile))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ServiceIcon(e)
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

/** 入口的彩色图标底块，颜色随色系走；首页宫格与设置里的入口列表共用，后者用小一号的尺寸。 */
@Composable
internal fun ServiceIcon(
    entry: ServiceEntry,
    box: Dp = TILE_ICON,
    corner: Dp = TILE_ICON_CORNER,
    icon: Dp = 23.dp,
) {
    val accents = LocalAccents.current
    Box(
        Modifier
            .size(box)
            .clip(RoundedCornerShape(corner))
            .background(accents.courseColors[entry.tint % accents.courseColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            entry.icon,
            null,
            Modifier.size(icon),
            tint = accents.courseAccents[entry.tint % accents.courseAccents.size],
        )
    }
}

/** 首页列表底部的留白；「校园服务」展开时连它一起挪进屏幕。 */
private val HOME_LIST_BOTTOM = 24.dp
private val TILE_ICON = 46.dp
private val TILE_ICON_CORNER = 15.dp
private val TILE_ROW_GAP = 18.dp
