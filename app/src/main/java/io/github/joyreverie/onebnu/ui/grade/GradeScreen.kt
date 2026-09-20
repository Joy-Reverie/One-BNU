package io.github.joyreverie.onebnu.ui.grade

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.GpaSummary
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.CacheBanner
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.LoadingBox
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.glow
import io.github.joyreverie.onebnu.ui.theme.listPadding
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(vm: GradeViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var scaleMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("成绩", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "平时 · 期末 · 绩点",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { vm.load(forceRefresh = true) }) {
                        Icon(Icons.Filled.Refresh, "刷新成绩")
                    }
                    Box {
                        IconButton(onClick = { scaleMenu = true }) {
                            Icon(Icons.Filled.Tune, "绩点算法")
                        }
                        DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                            GpaScale.entries.forEach { scale ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                scale.label,
                                                fontWeight = if (scale == s.scale) FontWeight.Bold else FontWeight.Normal,
                                            )
                                            Text(
                                                scale.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    },
                                    onClick = { scaleMenu = false; vm.setScale(scale) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.fromCache) {
                CacheBanner(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    refreshing = s.refreshing,
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    s.loading -> LoadingBox("正在查询成绩…")
                    s.error != null -> ErrorBox(s.error!!) { vm.load(forceRefresh = true) }
                    s.emptyReason != null -> EmptyGradeState(s.emptyReason!!) { vm.load(forceRefresh = true) }
                    else -> GradeContent(s, onSetIncludedCourses = vm::setIncludedCourses)
                }
            }
        }
    }
}

@Composable
private fun EmptyGradeState(reason: String, onRetry: () -> Unit) {
    EmptyBox(reason, onRetry = onRetry)
}

@Composable
internal fun GradeContent(
    s: GradeUiState,
    onSetIncludedCourses: (Set<String>) -> Unit = {},
) {
    var showCoursePicker by rememberSaveable { mutableStateOf(false) }
    var selectedTerm by rememberSaveable { mutableIntStateOf(0) }
    val screen = LocalScreenInfo.current
    val allTerms = listOf("全部") + s.byTerm.map { it.first }
    val filteredGrades = remember(s.grades, selectedTerm, allTerms) {
        if (selectedTerm == 0) s.grades else s.grades.filter { it.termLabel == allTerms.getOrNull(selectedTerm) }
    }
    val summary = remember(s.grades, s.scale, s.manuallyExcludedCourseKeys, selectedTerm) {
        if (selectedTerm == 0) s.overall
        else s.byTerm.firstOrNull { it.first == allTerms.getOrNull(selectedTerm) }?.second
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = LocalScreenInfo.current.listPadding(top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (screen.isCompact) 12.dp else 16.dp),
    ) {
        item { GradeIntro(s.grades.size, s.scale) }
        item {
            summary?.let {
                GradeSummaryCard(
                    summary = it,
                    scale = s.scale,
                    compact = screen.isCompact,
                )
            }
        }
        item {
            TermFilter(
                terms = allTerms,
                selected = selectedTerm,
                onSelected = { selectedTerm = it },
            )
        }
        item {
            CalculationScopeCard(
                s = s,
                onClick = { showCoursePicker = true },
            )
        }

        if (s.officialPointsMissing) {
            item {
                NoteCard(
                    icon = Icons.Outlined.Info,
                    text = "教务系统没有返回官方绩点，当前显示的是按「${s.scale.label}」本地换算的结果。",
                )
            }
        }

        summary?.let { current ->
            if (current.deferredCount > 0) {
                item {
                    NoteCard(
                        icon = Icons.Outlined.Info,
                        text = "缓考 ${current.deferredCount} 门：即使暂记为 0 分，也不会计入绩点和加权均分。",
                    )
                }
            }
            if (current.supersededCount > 0) {
                item {
                    NoteCard(
                        icon = Icons.Outlined.Info,
                        text = "重修或补考记录只保留最后一次成绩参与统计。",
                    )
                }
            }
            if (current.manuallyExcludedCount > 0) {
                item {
                    NoteCard(
                        icon = Icons.Outlined.Info,
                        text = "已从本机计算范围排除 ${current.manuallyExcludedCount} 门课程，可随时重新勾选。",
                    )
                }
            }
            val unavailableCount = current.excludedCount - current.deferredCount -
                current.manuallyExcludedCount - current.supersededCount
            if (unavailableCount > 0) {
                item {
                    NoteCard(
                        icon = Icons.Outlined.Info,
                        text = "另有 $unavailableCount 门通过制、免修或无可用绩点的课程未计入绩点。",
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = if (selectedTerm == 0) "全部课程" else allTerms.getOrNull(selectedTerm).orEmpty(),
                count = filteredGrades.size,
            )
        }
        if (filteredGrades.isEmpty()) {
            item { Text("该学期暂无成绩", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (screen.isExpanded) {
            itemsIndexed(
                filteredGrades.chunked(2),
                key = { index, grades -> grades.joinToString("|") { it.calculationKey } + "#$index" },
            ) { _, grades ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    grades.forEach { grade ->
                        GradeRow(
                            grade,
                            s.scale,
                            manuallyExcluded = grade.calculationKey in s.manuallyExcludedCourseKeys,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (grades.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            itemsIndexed(filteredGrades, key = { index, g -> "${g.calculationKey}#$index" }) { _, g ->
                GradeRow(g, s.scale, manuallyExcluded = g.calculationKey in s.manuallyExcludedCourseKeys)
            }
        }
    }

    if (showCoursePicker) {
        CourseSelectionDialog(
            s = s,
            onApply = onSetIncludedCourses,
            onDismiss = { showCoursePicker = false },
        )
    }
}

@Composable
private fun GradeIntro(count: Int, scale: GpaScale) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                Icons.Outlined.School,
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("学习表现", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count == 0) "暂无成绩记录" else "$count 门课程 · ${scale.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TermFilter(terms: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 1.dp),
    ) {
        items(terms.size, key = { it }) { index ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelected(index) },
                label = {
                    Text(
                        terms[index].replace("学年", ""),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** 明确、可发现的手动入口；比只放在顶栏溢出菜单里更适合这类会影响结果的设置。 */
@Composable
private fun CalculationScopeCard(s: GradeUiState, onClick: () -> Unit) {
    val eligible = s.grades.filter { GpaCalculator.isEligible(it, s.scale) }
    val included = eligible.count { it.calculationKey !in s.manuallyExcludedCourseKeys }
    val deferred = s.grades.count { it.isDeferredExam }

    BnuCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Icon(
                    Icons.Outlined.Checklist,
                    null,
                    Modifier.padding(9.dp).size(21.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("计算范围", style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append("已选 $included/${eligible.size} 门")
                        if (deferred > 0) append(" · 缓考 $deferred 门自动排除")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text("调整") }
        }
    }
}

/** 选择本机的 GPA / 加权均分计算范围。不可计算和缓考项保留在列表中，避免用户误以为成绩丢失。 */
@Composable
private fun CourseSelectionDialog(
    s: GradeUiState,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val eligible = remember(s.grades, s.scale) {
        s.grades.filter { GpaCalculator.isEligible(it, s.scale) }
    }
    val eligibleKeys = remember(eligible) { eligible.map { it.calculationKey }.toSet() }
    var selectedKeys by remember(s.grades, s.scale, s.manuallyExcludedCourseKeys) {
        mutableStateOf(eligibleKeys - s.manuallyExcludedCourseKeys)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择计点课程") },
        text = {
            Column {
                Text(
                    "仅影响本机显示的平均绩点和加权均分。缓考及当前口径下无可用绩点的记录始终不参与计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    itemsIndexed(s.grades, key = { index, g -> "${g.calculationKey}#$index" }) { _, grade ->
                        val selectable = GpaCalculator.isEligible(grade, s.scale)
                        val checked = selectable && grade.calculationKey in selectedKeys
                        val rowModifier = if (selectable) {
                            Modifier.fillMaxWidth().clickable {
                                selectedKeys = if (checked) selectedKeys - grade.calculationKey
                                else selectedKeys + grade.calculationKey
                            }
                        } else Modifier.fillMaxWidth()
                        Row(rowModifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                enabled = selectable,
                                onCheckedChange = { enabled ->
                                    if (selectable) {
                                        selectedKeys = if (enabled) selectedKeys + grade.calculationKey
                                        else selectedKeys - grade.calculationKey
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(grade.courseName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    selectionDetail(grade, s.scale),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (grade != s.grades.last()) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(selectedKeys)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { selectedKeys = eligibleKeys }) { Text("全选") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun selectionDetail(grade: Grade, scale: GpaScale): String = when {
    grade.isDeferredExam -> "缓考：不参与绩点计算"
    !GpaCalculator.isEligible(grade, scale) -> "当前口径下没有可用绩点"
    else -> buildString {
        append("${grade.termLabel} · ${grade.credits} 学分")
        scale.pointOf(grade)?.let { append(" · 绩点 ${"%.2f".format(Locale.ROOT, it)}") }
    }
}

@Composable
private fun GradeSummaryCard(summary: GpaSummary, scale: GpaScale, compact: Boolean) {
    val accents = LocalAccents.current
    val maxGpa = when (scale) {
        GpaScale.LINEAR_5 -> 5.0
        GpaScale.OFFICIAL, GpaScale.STANDARD_4, GpaScale.LINEAR_4 -> 4.0
    }
    val ratio = ((summary.gpa ?: 0.0) / maxGpa).coerceIn(0.0, 1.0).toFloat()
    val sweep by animateFloatAsState(ratio, tween(700), label = "gpaSweep")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.cardLarge))
            .background(accents.heroGradient)
            .glow(Color.White, alpha = 0.16f, cx = 0.95f, cy = -0.15f, radius = 0.65f),
    ) {
        if (compact) {
            Column(Modifier.padding(20.dp)) {
                SummaryHeader(summary, scale, sweep, maxGpa)
                Spacer(Modifier.height(18.dp))
                SummaryStats(summary)
            }
        } else {
            Row(
                Modifier.padding(24.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryHeader(summary, scale, sweep, maxGpa)
                Spacer(Modifier.width(24.dp))
                SummaryStats(summary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryHeader(summary: GpaSummary, scale: GpaScale, sweep: Float, maxGpa: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GpaDial(sweep, summary.gpa, maxGpa, Modifier.size(100.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(scale.label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            Text("平均绩点", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                summary.weightedAverage?.let { "加权均分 ${"%.2f".format(Locale.ROOT, it)}" } ?: "暂无加权均分",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun SummaryStats(summary: GpaSummary, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroStat("已获学分", "%.1f".format(Locale.ROOT, summary.earnedCredits), Modifier.weight(1f))
        HeroStat("计点学分", "%.1f".format(Locale.ROOT, summary.gradedCredits), Modifier.weight(1f))
        HeroStat("课程数", summary.courseCount.toString(), Modifier.weight(1f))
    }
}

/** 绩点表盘：270° 圆弧 + 中心数值，比一行数字更直观地表达「距满绩多远」。 */
@Composable
private fun GpaDial(sweep: Float, value: Double?, max: Double, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.22f),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = Color.White, startAngle = 135f, sweepAngle = 270f * sweep, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { "%.2f".format(Locale.ROOT, it) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("/ ${"%.1f".format(Locale.ROOT, max)}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(Shape.chip)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
            Text(
                count.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GradeRow(
    g: Grade,
    scale: GpaScale,
    manuallyExcluded: Boolean,
    modifier: Modifier = Modifier,
) {
    val point = scale.pointOf(g)
    val hasBreakdown = !g.usualScoreText.isNullOrBlank() || !g.finalScoreText.isNullOrBlank()
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        g.courseName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(g.termLabel, "${g.credits} 学分").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                ScoreBadge(text = g.scoreText.ifBlank { "—" }, color = scoreColor(g))
            }

            if (hasBreakdown) {
                Spacer(Modifier.height(14.dp))
                ScoreBreakdown(g)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeTag(
                    text = when {
                        g.isDeferredExam -> "缓考 · 不计绩点"
                        manuallyExcluded && GpaCalculator.isEligible(g, scale) -> "已排除"
                        else -> point?.let { "绩点 ${"%.2f".format(Locale.ROOT, it)}" } ?: "不计点"
                    },
                    color = when {
                        g.isDeferredExam -> MaterialTheme.colorScheme.surfaceVariant
                        manuallyExcluded && GpaCalculator.isEligible(g, scale) -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (g.examType.isNotBlank()) {
                    Text(g.examType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                if (g.courseType.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(g.courseType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (g.remark.isNotBlank() && !(g.isDeferredExam && g.remark.trim() == "缓考")) {
                Spacer(Modifier.height(6.dp))
                Text(g.remark, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScoreBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(12.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun ScoreBreakdown(g: Grade) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScorePart("平时成绩", g.usualScoreText ?: "—", Modifier.weight(1f))
            ScorePart("期末成绩", g.finalScoreText ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        ScoreProgress(g.score)
    }
}

@Composable
private fun ScorePart(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = LocalAccents.current.raised,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ScoreProgress(total: Double?) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("成绩概览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                total?.let { "总评 ${"%.1f".format(Locale.ROOT, it)}" } ?: "暂无可用分数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(5.dp))
        Surface(
            Modifier.fillMaxWidth().height(7.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (total != null) {
                    Box(
                        Modifier.fillMaxWidth((total / 100.0).coerceIn(0.0, 1.0).toFloat())
                            .height(7.dp)
                            .background(scoreProgressColor(total), RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}

private fun scoreProgressColor(score: Double): Color = when {
    score >= 85 -> Color(0xFF3A9C78)
    score >= 60 -> Color(0xFF3A63B8)
    else -> Color(0xFFC65353)
}

@Composable
private fun scoreColor(g: Grade): Color {
    val s = g.score ?: io.github.joyreverie.onebnu.data.model.GradeScale.letterToScore(g.scoreText)
    return when {
        g.isDeferredExam -> MaterialTheme.colorScheme.outline
        s == null -> MaterialTheme.colorScheme.onSurfaceVariant
        s >= 85 -> MaterialTheme.colorScheme.primary
        s >= 60 -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun GradeTag(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColorForTag(color),
        )
    }
}

@Composable
private fun contentColorForTag(container: Color): Color = when {
    container == MaterialTheme.colorScheme.primaryContainer -> MaterialTheme.colorScheme.onPrimaryContainer
    container == MaterialTheme.colorScheme.tertiaryContainer -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun NoteCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
