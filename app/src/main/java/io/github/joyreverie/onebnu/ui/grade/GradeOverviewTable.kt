package io.github.joyreverie.onebnu.ui.grade

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import java.util.Locale

/**
 * 成绩总览表：一行一门课，按学期从上到下排。
 *
 * 与「明细」卡片的分工是：这里只回答「这学期修了什么、各是多少分」，成绩构成、
 * 权重、备注留在明细里。学期写成整组的表头带而不是每行重复一列 —— 手机上一行放不下
 * 七列，学期占一列课程名就只剩几个字；做成分组带还顺带把学期之间分开了。
 * 列宽跟着系统字号放大，放大到一行装不下时整张表可以左右滑。
 */
@Composable
internal fun GradeOverviewTable(
    grades: List<Grade>,
    scale: GpaScale,
    manuallyExcludedCourseKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    // 传进来的成绩已按学年、学期倒序排好，groupBy 保持这个顺序，同一学期自然连在一起。
    val groups = remember(grades) { grades.groupBy { it.termLabel }.toList() }
    val metrics = TableMetrics(LocalDensity.current.fontScale)
    val scroll = rememberScrollState()
    val accents = LocalAccents.current

    BnuCard(modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val tableWidth = maxOf(maxWidth, metrics.minWidth)
            Column(Modifier.horizontalScroll(scroll).width(tableWidth)) {
                TableHeader(metrics)
                groups.forEachIndexed { groupIndex, (term, rows) ->
                    TermBand(
                        term = term,
                        rows = rows,
                        scale = scale,
                        manuallyExcludedCourseKeys = manuallyExcludedCourseKeys,
                        metrics = metrics,
                        first = groupIndex == 0,
                    )
                    rows.forEachIndexed { rowIndex, grade ->
                        if (rowIndex > 0) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = metrics.edge),
                                color = accents.hairline,
                            )
                        }
                        GradeTableRow(
                            grade = grade,
                            scale = scale,
                            metrics = metrics,
                            manuallyExcluded = grade.calculationKey in manuallyExcludedCourseKeys &&
                                GpaCalculator.isEligible(grade, scale),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeader(metrics: TableMetrics) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = metrics.edge, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
    ) {
        // 窄列里的四字表头自己断行会断成「单课绩 / 点」，所以写死断在中间
        HeaderCell("课程名称", Modifier.weight(1f), TextAlign.Start)
        HeaderCell("学分", Modifier.width(metrics.credits), TextAlign.Center)
        HeaderCell("单课\n绩点", Modifier.width(metrics.point), TextAlign.Center)
        HeaderCell("平时\n成绩", Modifier.width(metrics.part), TextAlign.Center)
        HeaderCell("期末\n成绩", Modifier.width(metrics.part), TextAlign.Center)
        HeaderCell("总成绩", Modifier.width(metrics.total), TextAlign.Center)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 2,
    )
}

/** 学期分组的表头带：一道主色竖条 + 学期名 + 这学期的绩点与学分，同时充当学期之间的分界。 */
@Composable
private fun TermBand(
    term: String,
    rows: List<Grade>,
    scale: GpaScale,
    manuallyExcludedCourseKeys: Set<String>,
    metrics: TableMetrics,
    first: Boolean,
) {
    val summary = remember(rows, scale, manuallyExcludedCourseKeys) {
        GpaCalculator.summarize(rows, scale, manuallyExcludedCourseKeys)
    }
    if (!first) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(horizontal = metrics.edge, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            term,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                summary.gpa?.let { append("绩点 ${"%.2f".format(Locale.ROOT, it)} · ") }
                append("${trimNumber(summary.earnedCredits)} 学分")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
    HorizontalDivider(color = LocalAccents.current.hairline)
}

@Composable
private fun GradeTableRow(
    grade: Grade,
    scale: GpaScale,
    metrics: TableMetrics,
    manuallyExcluded: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = metrics.edge, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
    ) {
        Text(
            grade.courseName,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        ValueCell(trimNumber(grade.credits), metrics.credits)
        PointCell(grade, scale, manuallyExcluded, metrics.point)
        ValueCell(grade.usualScoreText?.takeIf { it.isNotBlank() } ?: "—", metrics.part)
        ValueCell(grade.finalScoreText?.takeIf { it.isNotBlank() } ?: "—", metrics.part)
        Text(
            grade.scoreText.ifBlank { "—" },
            Modifier.width(metrics.total),
            style = MaterialTheme.typography.titleSmall,
            color = scoreColor(grade),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ValueCell(text: String, width: Dp) {
    Text(
        text,
        Modifier.width(width),
        style = MaterialTheme.typography.bodySmall,
        color = if (text == "—") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** 缓考写「缓考」而不是那个占位的 0；手动排除的画一道删除线，和上面的提示对得上。 */
@Composable
private fun PointCell(grade: Grade, scale: GpaScale, manuallyExcluded: Boolean, width: Dp) {
    val point = scale.pointOf(grade)
    val text = when {
        grade.isDeferredExam -> "缓考"
        point == null -> "—"
        else -> "%.2f".format(Locale.ROOT, point)
    }
    Text(
        text,
        Modifier.width(width),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (point != null && !manuallyExcluded) FontWeight.Medium else FontWeight.Normal,
        color = if (point == null || manuallyExcluded) MaterialTheme.colorScheme.outline
        else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (manuallyExcluded) TextDecoration.LineThrough else null,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** 2.0 学分写成「2」，1.5 学分保留小数。 */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.ROOT, value)

/**
 * 表格列宽。字号放大时按比例放大，装不下就整张表左右滑 ——
 * 列宽固定而字号变大，只会把表头裁掉一半。
 */
private class TableMetrics(fontScale: Float) {
    private val k = fontScale.coerceIn(1f, 1.8f)
    val credits = 30.dp * k
    val point = 38.dp * k
    val part = 36.dp * k
    val total = 46.dp * k
    private val nameMin = 92.dp * k
    val gap = 5.dp
    val edge = 10.dp
    val minWidth: Dp
        get() = nameMin + credits + point + part * 2 + total + gap * 5 + edge * 2
}
