package io.github.joyreverie.onebnu.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.CategoryRules
import io.github.joyreverie.onebnu.data.model.CategorySource
import io.github.joyreverie.onebnu.data.model.CourseCategory
import io.github.joyreverie.onebnu.data.model.CreditLedger
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.LedgerEntry
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.TermLedger
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.ListSkeleton
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreditsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    /** 各学期修读台账。 */
    val ledger: CreditLedger? = null,
    /** 教务给出的培养方案学分要求（很多院系没录入，为空就不显示）。 */
    val requirements: List<InfoItem> = emptyList(),
)

/**
 * 学分核算：把每个学期的选课课程表拼起来，按模块归类求和。
 * 归类依据依次是手动指定、课表/培养方案官方模块、课程中心/成绩单课程性质、选课结果兜底、最后才推断。
 */
class CreditsViewModel : ViewModel() {
    private val repo = ServiceLocator.repo
    private val manual = ServiceLocator.creditCategories
    private val _state = MutableStateFlow(CreditsUiState())
    val state: StateFlow<CreditsUiState> = _state.asStateFlow()

    private var schedules: List<Schedule> = emptyList()
    private var grades: List<Grade> = emptyList()
    private var modules: Map<String, CourseCategory> = emptyMap()
    private var selection: Map<String, CourseCategory> = emptyMap()

    init { load() }

    fun load() {
        _state.value = CreditsUiState(loading = true)
        viewModelScope.launch {
            val terms = when (val t = repo.terms()) {
                is Outcome.Ok -> t.data
                is Outcome.Empty -> { _state.value = CreditsUiState(loading = false, emptyReason = t.reason); return@launch }
                is Outcome.Error -> { _state.value = CreditsUiState(loading = false, error = t.message); return@launch }
            }
            val loaded = ArrayList<Schedule>()
            var firstError: String? = null
            for (term in terms) {
                when (val s = repo.schedule(term)) {
                    is Outcome.Ok -> loaded += s.data
                    is Outcome.Empty -> Unit
                    is Outcome.Error -> if (firstError == null) firstError = s.message
                }
            }
            if (loaded.isEmpty()) {
                _state.value = CreditsUiState(loading = false, error = firstError, emptyReason = if (firstError == null) "还没有选课记录" else null)
                return@launch
            }
            schedules = loaded
            grades = (repo.grades() as? Outcome.Ok)?.data.orEmpty()
            val moduleLabels = LinkedHashMap<String, String>()
            loaded.map { it.term }.distinctBy { it.code }.forEach { term ->
                (repo.courseModules(term) as? Outcome.Ok)?.data.orEmpty().forEach { (code, label) ->
                    moduleLabels.putIfAbsent(code, label)
                }
            }
            modules = moduleLabels.mapNotNull { (code, label) ->
                CategoryRules.fromGradeType(label)?.let { code to it }
            }.toMap()
            selection = (repo.selectionCategories() as? Outcome.Ok)?.data.orEmpty().mapNotNull { (code, label) ->
                CategoryRules.fromGradeType(label)?.let { code to it }
            }.toMap()
            val requirements = (repo.creditRequirement() as? Outcome.Ok)?.data.orEmpty()
            _state.value = CreditsUiState(
                loading = false,
                ledger = CategoryRules.build(
                    schedules,
                    grades,
                    manual.all(),
                    modules,
                    selection,
                ),
                requirements = requirements,
            )
        }
    }

    /** 手动改一门课的模块；传 null 恢复自动归类。 */
    fun setCategory(courseCode: String, category: CourseCategory?) {
        manual.set(courseCode, category)
        _state.value = _state.value.copy(ledger = CategoryRules.build(schedules, grades, manual.all(), modules, selection))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onBack: () -> Unit, vm: CreditsViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学分核算") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CreditsContent(s, onRetry = { vm.load() }, onSetCategory = { code, c -> vm.setCategory(code, c) })
        }
    }
}

/** 纯展示部分，debug 预览页直接喂样例状态。 */
@Composable
internal fun CreditsContent(
    s: CreditsUiState,
    onRetry: () -> Unit,
    onSetCategory: (String, CourseCategory?) -> Unit,
) {
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    when {
        s.loading -> Column(Modifier.padding(16.dp)) { ListSkeleton(rows = 4) }
        s.error != null -> ErrorBox(s.error, onRetry = onRetry)
        s.ledger == null -> EmptyBox(s.emptyReason ?: "还没有选课记录", onRetry = onRetry)
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = LocalScreenInfo.current.listPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(s.ledger) }
            val inferredCount = s.ledger.entries.count { it.source == CategorySource.INFERRED }
            if (inferredCount > 0) {
                item { UnconfirmedCategoryNotice(inferredCount) }
            }
            s.ledger.terms.asReversed().forEach { term ->
                item(key = term.term.code) { TermCard(term) { editing = it } }
            }
            if (s.requirements.isNotEmpty()) {
                item { RequirementsCard(s.requirements) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    editing?.let { entry ->
        CategoryDialog(
            entry = entry,
            onDismiss = { editing = null },
            onPick = { c ->
                onSetCategory(entry.course.code, c)
                editing = null
            },
        )
    }
}

@Composable
private fun UnconfirmedCategoryNotice(count: Int) {
    BnuCard(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "$count 门课程待确认分类",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "教务暂未返回官方类别，当前按规则暂分；点击课程行即可手动调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 总览：总学分 + 各模块学分。 */
@Composable
private fun SummaryCard(ledger: CreditLedger) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    fmt(ledger.total),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "学分 · ${ledger.entries.count { it.counted }} 门",
                    Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(12.dp))
            CourseCategory.entries.forEach { c ->
                val v = ledger.totalOf(c)
                if (v <= 0.0) return@forEach
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(categoryColor(c)))
                    Spacer(Modifier.width(10.dp))
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(fmt(v), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 一个学期：学期名与小计，下面每门课一行，点行可改模块。 */
@Composable
private fun TermCard(term: TermLedger, onEdit: (LedgerEntry) -> Unit) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    term.term.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fmt(term.total)} 学分",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Divider(color = LocalAccents.current.hairline)
            term.entries.forEachIndexed { i, e ->
                if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = LocalAccents.current.hairline)
                EntryRow(e) { onEdit(e) }
            }
        }
    }
}

@Composable
private fun EntryRow(e: LedgerEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(categoryColor(e.category)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.course.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryTag(e.category, inferred = e.source == CategorySource.INFERRED)
                if (!e.counted) {
                    Spacer(Modifier.width(6.dp))
                    Text("重修不计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Text(
            fmt(e.course.credits),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (e.counted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

/** 模块标签：推断出来的用空心样式，成绩单或手动确认的用实心。 */
@Composable
private fun CategoryTag(c: CourseCategory, inferred: Boolean) {
    val color = categoryColor(c)
    Surface(
        shape = RoundedCornerShape(50),
        color = if (inferred) Color.Transparent else color.copy(alpha = 0.16f),
        border = if (inferred) androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)) else null,
    ) {
        Text(
            c.label,
            Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun CategoryDialog(entry: LedgerEntry, onDismiss: () -> Unit, onPick: (CourseCategory?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.course.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                CourseCategory.entries.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(c) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(c)))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            c.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (c == entry.category) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (c == entry.category) {
                            Text(
                                when (entry.source) {
                                    CategorySource.MANUAL -> "手动"
                                    CategorySource.SCHEDULE -> "课表"
                                    CategorySource.SELECTION_RESULT -> "选课结果（兜底）"
                                    CategorySource.COURSE_CENTER -> "课程中心"
                                    CategorySource.MODULE -> "培养方案"
                                    CategorySource.GRADE -> "成绩单"
                                    CategorySource.INFERRED -> "推断"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (entry.source == CategorySource.MANUAL) {
                TextButton(onClick = { onPick(null) }) { Text("恢复自动") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RequirementsCard(items: List<InfoItem>) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column {
            Text(
                "培养方案要求",
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Divider(color = LocalAccents.current.hairline)
            items.forEachIndexed { i, item ->
                if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = LocalAccents.current.hairline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(item.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun categoryColor(c: CourseCategory): Color {
    val accents = LocalAccents.current.courseAccents
    return accents[c.ordinal % accents.size]
}

private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
