package io.github.joyreverie.onebnu.ui.campus

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.media.ImageSaver
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.AcademicCalendar.Season
import io.github.joyreverie.onebnu.data.model.CalendarEvent
import io.github.joyreverie.onebnu.data.model.OfficialCalendar
import io.github.joyreverie.onebnu.data.model.OfficialCalendars
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.ui.components.SectionHeader
import io.github.joyreverie.onebnu.ui.components.ZoomableImageDialog
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAYS = listOf("一", "二", "三", "四", "五", "六", "日")
private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** 没有官方校历可依据的学期，列出的周数。 */
private const val FALLBACK_WEEKS = 20

/** 页面上可切换的一个学期：有官方校历按校历；没有则按惯例推算，并明确标注「推算」。 */
private data class TermView(
    val year: Int,
    val season: Season,
    val official: OfficialCalendar?,
) {
    val label: String get() = "$year-${year + 1} 学年${season.label}学期"
    val firstMonday: LocalDate get() = official?.firstMonday ?: AcademicCalendar.firstMonday(year, season)
    val weeks: Int get() = official?.weeks ?: FALLBACK_WEEKS
    val breakLabel: String get() = if (season == Season.AUTUMN) "寒假" else "暑假"
}

/**
 * 校历周次。
 *
 * 学校每学期以图片形式公布校历，这里内嵌原图（可放大、可保存到相册），
 * 并把周次—日期对照与要点抄录出来。页面上始终标明是哪个学年学期的校历：
 * 未录入官方校历的学期按惯例推算，并明确标注，避免误把上学期的校历当作本学期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val current = remember { AcademicCalendar.currentTerm(today) }
    val (curYear, curSeason) = current

    // 可切换的学期：所有已录入的官方校历，加上今天所在学期（若它还没有官方校历，按推算列出）
    val views = remember {
        val official = OfficialCalendars.ALL.map { TermView(it.year, it.season, it) }
        val cur = official.firstOrNull { it.year == curYear && it.season == curSeason }
            ?: TermView(curYear, curSeason, null)
        (official + cur).distinct().sortedByDescending { it.year * 10 + it.season.order }
    }
    var view by remember { mutableStateOf(views.first { it.year == curYear && it.season == curSeason }) }
    val isCurrentTerm = view.year == curYear && view.season == curSeason
    val currentWeek = if (isCurrentTerm) AcademicCalendar.weekOf(view.firstMonday, today) else null

    var viewer by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 保存到相册：Android 9 及以下先申请存储权限，拿到后再存；10+ 直接存
    var pendingSave by remember { mutableStateOf<OfficialCalendar?>(null) }
    fun doSave(cal: OfficialCalendar) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ImageSaver.saveJpegResource(
                    context, cal.imageRes,
                    "One BNU 校历_${cal.year}-${cal.year + 1}${cal.season.label}",
                )
            }
            snackbar.showSnackbar(
                r.fold({ "已保存到相册：$it" }, { "保存失败：${it.message ?: "未知错误"}" }),
            )
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cal = pendingSave
        pendingSave = null
        if (granted && cal != null) doSave(cal) else scope.launch { snackbar.showSnackbar("未获得存储权限，无法保存到相册") }
    }
    val save: (OfficialCalendar) -> Unit = { cal ->
        if (ImageSaver.needsPermission(context)) {
            pendingSave = cal
            permission.launch(ImageSaver.PERMISSION)
        } else {
            doSave(cal)
        }
    }

    val official = view.official
    if (viewer && official != null) {
        ZoomableImageDialog(
            res = official.imageRes,
            contentDescription = official.title,
            onClose = { viewer = false },
        ) {
            IconButton(onClick = { save(official) }) {
                Icon(Icons.Outlined.Download, "保存到相册", tint = Color.White)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校历周次") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = LocalScreenInfo.current.listPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { TermHeader(view, isCurrentTerm, currentWeek, today) }

            if (views.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        views.forEach { v ->
                            FilterChip(
                                selected = v == view,
                                onClick = { view = v },
                                label = { Text(if (v.official != null) v.label else "${v.label}（推算）") },
                            )
                        }
                    }
                }
            }

            if (official != null) {
                item { CalendarImageCard(official, onOpen = { viewer = true }, onSave = { save(official) }) }
                if (official.events.isNotEmpty() || official.remarks.isNotEmpty()) {
                    item { EventsCard(official) }
                }
            } else {
                item { EstimateNotice(view) }
            }

            item { SectionHeader("周次对照", Modifier.padding(top = 4.dp)) }
            items((1..view.weeks).toList(), key = { it }) { w ->
                WeekRow(view, w, isCurrent = currentWeek == w)
            }

            item {
                Text(
                    if (official != null) {
                        "周次表与要点抄录自上方校历图，如有出入以学校发布的校历为准。"
                    } else {
                        "法定假日与调课以学校发布的校历为准，本页不含这些信息。"
                    },
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun TermHeader(view: TermView, isCurrentTerm: Boolean, currentWeek: Int?, today: LocalDate) {
    val fg = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    view.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = if (view.official != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (view.official != null) "官方校历" else "惯例推算",
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (view.official != null) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "第 1 周自 ${view.firstMonday.year} 年 ${view.firstMonday.format(FMT)}（周一）起，共 ${view.weeks} 周",
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.85f),
            )
            if (isCurrentTerm && currentWeek != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        currentWeek < 1 -> "今天 ${today.format(FMT)}，学期尚未开始"
                        currentWeek > view.weeks -> "今天 ${today.format(FMT)}，本学期校历周次已结束"
                        else -> "今天 ${today.format(FMT)} · 第 $currentWeek 周 周${DAYS[today.dayOfWeek.value - 1]}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = fg,
                )
            }
        }
    }
}

/** 官方校历原图：缩略图点开看大图，另有「查看大图 / 保存到相册」两个按钮。 */
@Composable
private fun CalendarImageCard(cal: OfficialCalendar, onOpen: () -> Unit, onSave: () -> Unit) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column {
            Image(
                painter = painterResource(cal.imageRes),
                contentDescription = cal.title,
                modifier = Modifier.fillMaxWidth().height(220.dp).clickable(onClick = onOpen),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
            Column(Modifier.padding(14.dp)) {
                Text(cal.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "学校党委 / 校长办公室编制 · 点击图片可放大查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看大图")
                    }
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存到相册")
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsCard(cal: OfficialCalendar) {
    SectionCard("校历要点") {
        cal.events.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 10.dp)) {
                Text(
                    eventDateLabel(e),
                    Modifier.width(118.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(e.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        cal.remarks.forEach { r ->
            Text(
                r,
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun eventDateLabel(e: CalendarEvent): String = when {
    e.isSingleDay -> "${e.start.format(FMT)} 周${DAYS[e.start.dayOfWeek.value - 1]}"
    e.start.month == e.end.month -> "${e.start.format(FMT)}–${e.end.dayOfMonth}日"
    else -> "${e.start.format(FMT)}–${e.end.format(FMT)}"
}

/** 没有官方校历的学期：把「推算」两个字说清楚，并提醒更新。 */
@Composable
private fun EstimateNotice(view: TermView) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Outlined.Info, null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "尚未录入${view.label}的官方校历。以下周次按学校惯例推算" +
                    (if (view.season == Season.AUTUMN) "（9 月 1 日起的第一个周一为第 1 周）" else "（2 月 20 日后的第一个周一为第 1 周）") +
                    "，仅供参考；学校发布校历后请更新应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun WeekRow(view: TermView, week: Int, isCurrent: Boolean) {
    val monday = view.firstMonday.plusWeeks((week - 1).toLong())
    val sunday = monday.plusDays(6)
    val official = view.official
    val isBreak = official?.isBreakWeek(week) == true
    val tags = buildList {
        if (isCurrent) add("本周")
        if (isBreak) add(view.breakLabel)
        official?.eventsInWeek(week)?.forEach { add(it.label) }
    }

    val primaryText = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        isBreak -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "第 $week 周",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = primaryText,
                )
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${monday.format(FMT)} — ${sunday.format(FMT)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
