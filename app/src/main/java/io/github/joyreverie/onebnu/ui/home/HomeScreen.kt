package io.github.joyreverie.onebnu.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import io.github.joyreverie.onebnu.ui.components.RollingNumber
import io.github.joyreverie.onebnu.ui.schedule.rememberMinuteClock
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
    // 云盘只认自己的登录接口、不经北京统一认证，珠海账号同样登得上：与北京同一个入口，用珠海保存的账号替它登录
    ServiceEntry("pan", "师大云盘", Icons.Outlined.Cloud, Routes.PAN, 6),
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
    val endAt: LocalTime,
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
    /** 把「现在」钉在某个时刻，只有 debug 预览会传。 */
    now: LocalTime? = null,
) {
    val screen = LocalScreenInfo.current
    val clock by rememberMinuteClock(now)
    val items = remember(s.todayCourses, s.todayEvents, s.periodTimes) { timelineItems(s) }
    val pad = screen.listPadding(top = 0.dp, bottom = 24.dp)
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    // 默认展示的在前、折叠的在后，各自保持首页顺序；收起时放几个跟着屏幕走，转屏、分屏后重新分
    val limit = screen.pinnedServiceLimit
    val (pinnedServices, foldedServices) = remember(campus, pinnedEntries, limit) {
        val entries = serviceEntries(campus)
        val pinned = effectivePinned(entries.map { it.key }, pinnedEntries, limit).toSet()
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
        item { HeroCard(s, items, clock) }

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
                    else -> TodayTimeline(items, clock, onEventClick = { editor = EditorTarget(it) })
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

/**
 * 顶部主视觉：渐变 + 模糊光斑，信息按「问候 / 姓名 / 学期周次」三级排布。
 * 今天有课或日程时右侧挂一枚进度环：结束了几项、一共几项，随时间推进转圈、数字滚动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(s: HomeUiState, items: List<TimelineItem>, now: LocalTime) {
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

        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            if (items.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                TodayRing(done = items.count { !now.isBefore(it.endAt) }, total = items.size)
            }
        }
    }
}

/** 今日进度环：环转到「结束项 / 总项」，中间的数字跟着滚动；全部结束时环闭合。 */
@Composable
private fun TodayRing(done: Int, total: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val sweep by animateFloatAsState(
        if (shown && total > 0) done.toFloat() / total else 0f,
        tween(700, easing = FastOutSlowInEasing),
        label = "ring",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(RING_SIZE)
                .drawBehind {
                    val w = 5.dp.toPx()
                    val inset = w / 2
                    val arc = Size(size.width - w, size.height - w)
                    drawArc(Color.White.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                    if (sweep > 0f) {
                        drawArc(Color.White, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                RollingNumber(done, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "/$total",
                    Modifier.padding(bottom = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("今日完成", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
    }
}

private val RING_SIZE = 64.dp

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

/** 今日课程与日程合成一条时间轴，按开始时刻排序。 */
private fun timelineItems(s: HomeUiState): List<TimelineItem> {
    val courses = s.todayCourses.map { item ->
        val startSpec = s.periodTimes.getOrNull(item.session.startPeriod - 1)?.substringBefore('-').orEmpty()
        val endSpec = s.periodTimes.getOrNull(item.session.endPeriod - 1)?.substringAfter('-').orEmpty()
        val startAt = PeriodMapper.parse(startSpec) ?: LocalTime.MIDNIGHT
        TimelineItem(
            startAt = startAt,
            endAt = PeriodMapper.parse(endSpec) ?: startAt,
            start = startSpec.ifBlank { "—" },
            end = endSpec,
            title = item.course.name,
            detail = listOfNotNull(
                item.session.location.takeIf { it.isNotBlank() },
                item.teachers.joinToString("、").takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            accent = Color.Unspecified,
            event = null,
        )
    }
    val events = s.todayEvents.map { e ->
        TimelineItem(
            startAt = e.start,
            endAt = e.end,
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
    return (courses + events).sortedBy { it.startAt }
}

/**
 * 时间轴走到哪儿了，以圆点为单位：-1 表示第一项还没开始；i 表示第 i 项已开始，
 * 小数部分是从第 i 项开始到第 i+1 项开始之间过去的比例，线就画到那里。最后一项开始后停在最后一个点。
 */
private fun timelineProgress(items: List<TimelineItem>, now: LocalTime): Float {
    val i = items.indexOfLast { !now.isBefore(it.startAt) }
    if (i < 0) return -1f
    if (i == items.lastIndex) return i.toFloat()
    val a = items[i].startAt.toSecondOfDay()
    val b = items[i + 1].startAt.toSecondOfDay()
    val frac = if (b > a) (now.toSecondOfDay() - a).toFloat() / (b - a) else 1f
    return i + frac.coerceIn(0f, 1f)
}

/**
 * 今日课程与日程用同一条时间轴呈现：左侧时间刻度 + 竖线圆点。日程带「日程」标签，点开可改。
 * 已经开始的点实心、还没到的空心；线从第一个点起一段一段画到「现在」，进页面时从头画起，之后随时间往前走。
 * 正在进行的那项圆点带光晕，已经结束的整行淡一些。
 */
@Composable
private fun TodayTimeline(items: List<TimelineItem>, now: LocalTime, onEventClick: (PersonalEvent) -> Unit) {
    val target = timelineProgress(items, now)
    val drawn = remember { Animatable(-1f) }
    LaunchedEffect(target) {
        val span = kotlin.math.abs(target - drawn.value)
        drawn.animateTo(target, tween((220 + span * 240).toInt().coerceAtMost(1400), delayMillis = 120, easing = FastOutSlowInEasing))
    }
    val progress = { drawn.value }

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
                    index = i,
                    last = i == items.lastIndex,
                    progress = progress,
                    ongoing = !now.isBefore(item.startAt) && now.isBefore(item.endAt),
                    ended = !now.isBefore(item.endAt),
                    onClick = item.event?.let { e -> { onEventClick(e) } },
                )
            }
        }
    }
}

/**
 * 时间轴的一行：时间刻度、轨道（线随行高伸缩，首尾行不画多余的段）、标题与说明。
 * [progress] 只在绘制时读，时间轴动起来时不触发重组。
 */
@Composable
private fun TimelineRow(
    item: TimelineItem,
    index: Int,
    last: Boolean,
    progress: () -> Float,
    ongoing: Boolean,
    ended: Boolean,
    onClick: (() -> Unit)?,
) {
    val accent = if (item.event != null) MaterialTheme.colorScheme.tertiary else courseAccent(item.title)
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val fade by animateFloatAsState(if (ended) 0.55f else 1f, tween(400), label = "ended")
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
    ) {
        // 系统字体放大到 1.3 倍时 46dp 固定宽会把「08:00」裁成「08:0」，给下限即可
        Column(
            Modifier.widthIn(min = 46.dp).padding(top = ROW_PAD, end = 4.dp).graphicsLayer { alpha = fade },
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
            // 上一点到这一点的线：前 80% 画在上一行的下半段，后 20% 画在这一行的上半段
            Box(
                Modifier.width(2.dp).height(ROW_PAD + 4.dp).then(
                    if (index == 0) Modifier else Modifier.trackSegment(line, ink) { ((progress() - (index - 1) - 0.8f) / 0.2f) },
                ),
            )
            Box(
                Modifier.size(10.dp).drawBehind {
                    val reached = progress() >= index - 0.001f
                    val r = size.minDimension / 2
                    if (ongoing && reached) drawCircle(accent.copy(alpha = 0.22f), radius = r * 2f)
                    if (reached) {
                        drawCircle(accent, radius = r)
                    } else {
                        drawCircle(surface, radius = r)
                        drawCircle(accent, radius = r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                },
            )
            Box(
                Modifier.width(2.dp).weight(1f).then(
                    if (last) Modifier else Modifier.trackSegment(line, ink) { (progress() - index) / 0.8f },
                ),
            )
        }

        Column(Modifier.weight(1f).padding(vertical = ROW_PAD).graphicsLayer { alpha = fade }) {
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

/** 轨道的一段：底色细线，上面按 [fill]（0~1，超出会被夹住）从上往下盖一层走过的颜色。 */
private fun Modifier.trackSegment(base: Color, ink: Color, fill: () -> Float): Modifier = drawBehind {
    drawRect(base)
    val f = fill().coerceIn(0f, 1f)
    if (f > 0f) drawRect(ink, size = Size(size.width, size.height * f))
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
 * 首页「校园服务」卡片。默认只摆 [pinned]（整行整行地放，最多 [pinnedServiceLimit] 个：手机竖屏三行、平板和横屏两行），
 * 其余的 [folded] 收在底部的 V 形箭头后面：点开接着往下排成同一张宫格，再点收起。没有折叠的入口就不画箭头。
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
                            TileRow(columns, row, click, revealFrom = pinned.size - r * columns, expanded = expanded, foldedCount = folded.size)
                        }
                    }
                }
                // 高度整块展开，入口一个接一个弹出来；收起时倒着一个个缩回去，再合上
                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded && rows.size > shownRows,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(tween(FOLD_MS, delayMillis = FOLD_STEP_MS * folded.size / 2), shrinkTowards = Alignment.Top),
                ) {
                    Column(Modifier.padding(top = TILE_ROW_GAP), verticalArrangement = Arrangement.spacedBy(TILE_ROW_GAP)) {
                        rows.drop(shownRows).forEachIndexed { r, row ->
                            val first = (shownRows + r) * columns - pinned.size
                            TileRow(columns, row, click, tileModifier = { i ->
                                Modifier.animateEnterExit(foldEnter(first + i), foldExit(first + i, folded.size))
                            })
                        }
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

/** 第 [k] 个折叠入口弹出来：稍晚一点起步，从略小、略靠上的位置落到原位。 */
private fun foldEnter(k: Int): EnterTransition {
    val delay = k * FOLD_STEP_MS
    return fadeIn(tween(FOLD_MS, delayMillis = delay)) +
        scaleIn(tween(FOLD_MS, delayMillis = delay, easing = FastOutSlowInEasing), initialScale = 0.7f) +
        slideInVertically(tween(FOLD_MS, delayMillis = delay, easing = FastOutSlowInEasing)) { -it / 3 }
}

/** 收起时倒过来：最后一个先缩回去，第一个最后走。 */
private fun foldExit(k: Int, count: Int): ExitTransition {
    val delay = (count - 1 - k).coerceAtLeast(0) * FOLD_STEP_MS / 2
    return fadeOut(tween(FOLD_MS / 2, delayMillis = delay)) +
        scaleOut(tween(FOLD_MS / 2, delayMillis = delay), targetScale = 0.7f)
}

private const val FOLD_MS = 260
private const val FOLD_STEP_MS = 35

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
    /** 折叠入口一共几个，倒序收起时据此排先后。 */
    foldedCount: Int = 0,
    /** 给第 i 格加的修饰，展开区里用它挂逐个弹出的动画。 */
    tileModifier: @Composable (Int) -> Modifier = { Modifier },
) {
    Row(Modifier.fillMaxWidth()) {
        row.forEachIndexed { i, e ->
            when {
                e == null -> EmptySlot(Modifier.weight(1f))
                i < revealFrom -> EntryTile(e, Modifier.weight(1f).then(tileModifier(i)), onClick = onClick?.let { click -> { click(e) } })
                else -> Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(
                        expanded,
                        enter = foldEnter(i - revealFrom),
                        exit = foldExit(i - revealFrom, foldedCount),
                    ) {
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
