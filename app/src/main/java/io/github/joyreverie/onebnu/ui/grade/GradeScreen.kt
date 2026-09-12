package io.github.joyreverie.onebnu.ui.grade

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaSummary
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.LoadingBox
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.Shape
import io.github.joyreverie.onebnu.ui.theme.GlowBlob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(vm: GradeViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var scaleMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩与绩点") },
                actions = {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> LoadingBox("正在查询成绩…")
                s.error != null -> ErrorBox(s.error!!) { vm.load(forceRefresh = true) }
                s.emptyReason != null -> EmptyBox(s.emptyReason!!, onRetry = { vm.load(forceRefresh = true) })
                else -> GradeContent(s, onSetIncludedCourses = vm::setIncludedCourses)
            }
        }
    }
}

@Composable
internal fun GradeContent(
    s: GradeUiState,
    onSetIncludedCourses: (Set<String>) -> Unit = {},
) {
    var showCoursePicker by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = LocalScreenInfo.current.listPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { s.overall?.let { OverallCard(it, s.scale) } }

        item { CalculationScopeCard(s, onClick = { showCoursePicker = true }) }

        if (s.officialPointsMissing) {
            item {
                NoteCard(
                    "教务系统没有返回官方绩点，当前显示的是按「${s.scale.label}」本地换算的结果，" +
                        "与学校官方口径可能不一致，仅供参考。",
                )
            }
        }

        s.overall?.takeIf { it.deferredCount > 0 }?.let { summary ->
            item {
                NoteCard(
                    "检测到 ${summary.deferredCount} 门缓考课程，已自动排除；即使教务暂记为 0 分，" +
                        "也不会拉低绩点或加权均分。",
                )
            }
        }

        s.overall?.takeIf { it.manuallyExcludedCount > 0 }?.let { summary ->
            item {
                NoteCard(
                    "你已在「计算范围」中排除 ${summary.manuallyExcludedCount} 门课程；" +
                        "该选择只保存在本机，随时可以重新勾选。",
                )
            }
        }

        s.overall?.let { summary ->
            val unavailableCount = summary.excludedCount - summary.deferredCount - summary.manuallyExcludedCount
            if (unavailableCount > 0) {
                item {
                    NoteCard(
                        "另有 $unavailableCount 门课程未计入绩点" +
                            "（通过/免修等无分数记录，或当前口径下教务未给出绩点），" +
                            "但其学分已计入已获学分。",
                    )
                }
            }
        }

        if (s.byTerm.size > 1) {
            item {
                Text("各学期", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            items(s.byTerm, key = { it.first }) { (label, summary) ->
                TermCard(label, summary)
            }
        }

        item {
            Text("全部课程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(s.grades, key = { it.calculationKey }) { g ->
            GradeRow(g, s.scale, manuallyExcluded = g.calculationKey in s.manuallyExcludedCourseKeys)
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

/** 明确、可发现的手动入口；比只放在顶栏溢出菜单里更适合这类会影响结果的设置。 */
@Composable
private fun CalculationScopeCard(s: GradeUiState, onClick: () -> Unit) {
    val eligible = s.grades.filter { GpaCalculator.isEligible(it, s.scale) }
    val included = eligible.count { it.calculationKey !in s.manuallyExcludedCourseKeys }
    val deferred = s.grades.count { it.isDeferredExam }

    BnuCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
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
                val detail = buildString {
                    append("已选 $included/${eligible.size} 门")
                    if (deferred > 0) append(" · 缓考 $deferred 门自动排除")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text("选择") }
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
                    "仅影响本机显示的平均绩点和加权均分。缓考（含暂记 0 分）及当前口径下无可用绩点的记录始终不参与计算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(s.grades, key = { it.calculationKey }) { grade ->
                        val selectable = GpaCalculator.isEligible(grade, s.scale)
                        val checked = selectable && grade.calculationKey in selectedKeys
                        val rowModifier = if (selectable) {
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedKeys = if (checked) selectedKeys - grade.calculationKey
                                    else selectedKeys + grade.calculationKey
                                }
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        Row(
                            rowModifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                TextButton(onClick = { selectedKeys = eligibleKeys }) { Text("全选可计算项") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun selectionDetail(grade: Grade, scale: GpaScale): String = when {
    grade.isDeferredExam -> "缓考：暂记 0 分也不参与绩点计算"
    !GpaCalculator.isEligible(grade, scale) -> "当前口径下没有可用绩点"
    else -> buildString {
        append("${grade.termLabel} · ${grade.credits} 学分")
        scale.pointOf(grade)?.let { append(" · 绩点 ${String.format("%.2f", it)}") }
    }
}

@Composable
private fun OverallCard(summary: GpaSummary, scale: GpaScale) {
    val accents = LocalAccents.current
    // 绩点满值随口径变化：五分制 5.0，四分制 4.0
    val maxGpa = if (scale == GpaScale.STANDARD_4 || scale == GpaScale.LINEAR_4) 4.0 else 5.0
    val ratio = ((summary.gpa ?: 0.0) / maxGpa).coerceIn(0.0, 1.0).toFloat()
    val sweep by animateFloatAsState(ratio, tween(700), label = "gpaSweep")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.cardLarge))
            .background(accents.heroGradient),
    ) {
        GlowBlob(
            Color.White,
            Modifier.align(Alignment.TopEnd).size(260.dp).offset(x = 80.dp, y = (-100).dp),
            alpha = 0.18f,
        )
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GpaDial(
                    sweep = sweep,
                    value = summary.gpa,
                    max = maxGpa,
                    modifier = Modifier.size(104.dp),
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        scale.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "平均绩点",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        summary.weightedAverage?.let { "加权均分 " + String.format("%.2f", it) } ?: "暂无均分",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("已获学分", String.format("%.1f", summary.earnedCredits), Modifier.weight(1f))
                HeroStat("计点学分", String.format("%.1f", summary.gradedCredits), Modifier.weight(1f))
                HeroStat("课程数", summary.courseCount.toString(), Modifier.weight(1f))
            }
        }
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
                color = Color.White,
                startAngle = 135f, sweepAngle = 270f * sweep, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value?.let { String.format("%.2f", it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                "/ " + String.format("%.1f", max),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(Shape.chip),
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun TermCard(label: String, summary: GpaSummary) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${summary.courseCount} 门 · ${String.format("%.1f", summary.earnedCredits)} 学分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                summary.gpa?.let { String.format("%.2f", it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GradeRow(g: Grade, scale: GpaScale, manuallyExcluded: Boolean) {
    val point = scale.pointOf(g)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    g.courseName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    g.scoreText.ifBlank { "—" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(g),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(g.termLabel)
                    append(" · ")
                    append("${g.credits} 学分")
                    if (g.courseType.isNotBlank()) append(" · ${g.courseType}")
                    when {
                        g.isDeferredExam -> append(" · 缓考，不计入绩点")
                        manuallyExcluded && GpaCalculator.isEligible(g, scale) -> append(" · 已从计算范围排除")
                        point != null -> append(" · 绩点 ${String.format("%.2f", point)}")
                    }
                    if (g.remark.isNotBlank() && !(g.isDeferredExam && g.remark.trim() == "缓考")) {
                        append(" · ${g.remark}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun scoreColor(g: Grade): androidx.compose.ui.graphics.Color {
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
private fun NoteCard(text: String) {
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
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
