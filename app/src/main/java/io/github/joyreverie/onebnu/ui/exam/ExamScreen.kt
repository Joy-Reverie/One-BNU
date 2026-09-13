package io.github.joyreverie.onebnu.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.data.model.Exam
import io.github.joyreverie.onebnu.ui.components.CacheBanner
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.LoadingBox
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(onBack: () -> Unit, vm: ExamViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("考试安排") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (s.rounds.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { menu = true }) {
                                Text(
                                    s.round?.name?.take(14) ?: "选择轮次",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Filled.ExpandMore, null)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                s.rounds.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.name) },
                                        onClick = { menu = false; vm.selectRound(r) },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 缓存优先渲染时给一行说明；后台取到新数据这行自己消失
            if (s.fromCache) {
                CacheBanner(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    refreshing = s.refreshing,
                )
            }
            Box(Modifier.weight(1f)) {
                when {
                    s.loading -> LoadingBox("正在查询考试安排…")
                    s.error != null -> ErrorBox(s.error!!) { vm.load(forceRefresh = true) }
                    s.emptyReason != null -> EmptyBox(
                        s.emptyReason!!,
                        hint = "考试安排由教务在考试周前统一发布",
                        onRetry = { vm.load(forceRefresh = true) },
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = LocalScreenInfo.current.listPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.exams, key = { it.courseName + it.time }) { ExamCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamCard(e: Exam) {
    val days = e.date?.let {
        runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it)) }.getOrNull()
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    e.courseName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (days != null) {
                    Spacer(Modifier.width(12.dp))
                    CountdownChip(days)
                }
            }
            Spacer(Modifier.height(10.dp))
            ExamLine("时间", e.time.ifBlank { "待定" })
            ExamLine("地点", e.location.ifBlank { "待定" })
            if (e.seat.isNotBlank()) ExamLine("座位号", e.seat)
            if (e.examType.isNotBlank() || e.category.isNotBlank()) {
                ExamLine(
                    "类别",
                    listOf(e.category, e.examType).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
        }
    }
}

@Composable
private fun CountdownChip(days: Long) {
    val (text, color) = when {
        days < 0 -> "已结束" to MaterialTheme.colorScheme.outline
        days == 0L -> "今天" to MaterialTheme.colorScheme.error
        days <= 3 -> "$days 天后" to MaterialTheme.colorScheme.error
        days <= 7 -> "$days 天后" to MaterialTheme.colorScheme.tertiary
        else -> "$days 天后" to MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun ExamLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
