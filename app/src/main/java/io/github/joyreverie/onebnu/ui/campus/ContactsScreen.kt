package io.github.joyreverie.onebnu.ui.campus

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.CampusContacts
import io.github.joyreverie.onebnu.data.model.CampusDirectory
import io.github.joyreverie.onebnu.data.model.ContactGroup
import io.github.joyreverie.onebnu.data.model.ContactHours
import io.github.joyreverie.onebnu.data.model.ContactRow
import io.github.joyreverie.onebnu.data.model.ContactTel
import io.github.joyreverie.onebnu.data.model.EmergencyContact
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

/** 搜索结果最多列这么多条，再多就提示缩小范围。 */
private const val MAX_HITS = 80

/**
 * 校内联系方式：内置北京校区各部门公开的办公电话（`res/raw/campus_contacts.json`，逐条抄自各单位官网），
 * 顶部搜索 + 分区筛选，紧急号码单独抬起；用户自己加的号码另成一组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val directory = remember { CampusContacts.load(context) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var custom by remember { mutableStateOf(PhoneStore.load(context)) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PhoneEntry?>(null) }
    val searching = query.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校内联系方式") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "添加号码") }
        },
    ) { padding ->
        val pad = LocalScreenInfo.current.listPadding(top = 4.dp, bottom = 88.dp)
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchBox(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (!searching) {
                GroupChips(directory.groups, selected) { selected = it }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = pad,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    searching -> searchResults(directory, custom, query, onDial = { dial(context, it) })
                    selected == null -> {
                        item { EmergencyCard(directory.emergency) { dial(context, it) } }
                        if (custom.isNotEmpty()) {
                            item { CustomCard(custom, onDial = { dial(context, it) }, onDelete = { deleting = it }) }
                        }
                        items(directory.groups, key = { it.id }) { g ->
                            GroupCard(
                                group = g,
                                updated = directory.updated,
                                expanded = expanded[g.id] == true,
                                onToggle = { expanded[g.id] = expanded[g.id] != true },
                                onDial = { dial(context, it) },
                            )
                        }
                    }
                    else -> {
                        val g = directory.groups.first { it.id == selected }
                        item {
                            GroupCard(group = g, updated = directory.updated, expanded = true, onToggle = null, onDial = { dial(context, it) })
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddPhoneDialog(
            onDismiss = { adding = false },
            onConfirm = { name, number ->
                custom = custom + PhoneEntry(name, number)
                PhoneStore.save(context, custom)
                adding = false
            },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${e.name}」") },
            text = { Text("将从本机移除这条号码。") },
            confirmButton = {
                TextButton(onClick = {
                    custom = custom.filterNot { it == e }
                    PhoneStore.save(context, custom)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("搜部门、业务、老师、楼号或号码", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "清除") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun GroupChips(groups: List<ContactGroup>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部") },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
        items(groups, key = { it.id }) { g ->
            FilterChip(
                selected = selected == g.id,
                onClick = { onSelect(if (selected == g.id) null else g.id) },
                label = { Text(g.title) },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}

/** 紧急求助：两列大号码，一眼能按。 */
@Composable
private fun EmergencyCard(items: List<EmergencyContact>, onDial: (String) -> Unit) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(8.dp))
                Text("紧急求助", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            items.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { e ->
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onDial(e.dial) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(
                                    e.pretty,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 1,
                                )
                                Text(
                                    e.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 用户自己添加的号码。 */
@Composable
private fun CustomCard(entries: List<PhoneEntry>, onDial: (String) -> Unit, onDelete: ((PhoneEntry) -> Unit)?) {
    BnuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                "我添加的",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            entries.forEachIndexed { i, e ->
                if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = LocalAccents.current.hairline)
                Row(
                    Modifier.fillMaxWidth().clickable { onDial(e.number) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            e.number,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { onDelete(e) }) {
                            Icon(Icons.Outlined.Delete, "删除", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

/** 一个分区：折叠时只有标题、副题和条数；展开后是说明、办公时间、小节与全部条目。 */
@Composable
private fun GroupCard(
    group: ContactGroup,
    updated: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    onDial: (String) -> Unit,
) {
    val context = LocalContext.current
    BnuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (group.subtitle.isNotBlank()) {
                        Text(
                            group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "${group.rowCount} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (onToggle != null) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        if (expanded) "收起" else "展开",
                        Modifier.padding(start = 4.dp).rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }

            if (expanded) {
                if (group.hours.isNotEmpty()) {
                    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) { HoursTable(group.hours) }
                }
                group.sections.forEach { s ->
                    if (s.title.isNotBlank()) {
                        Text(
                            s.title,
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Divider(color = LocalAccents.current.hairline)
                    }
                    s.rows.forEachIndexed { i, r ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = LocalAccents.current.hairline)
                        ContactRowView(r, onDial)
                    }
                    // 一个分区里各小节抄自不同页面时，来源与日期标在小节上
                    if (s.sourceUrl.isNotBlank()) {
                        SourceLine(
                            text = "${s.sourceLabel.ifBlank { "来源页" }} · ${s.sourceDateLabel}",
                            url = s.sourceUrl,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
                if (group.sourceUrl.isNotBlank()) {
                    Divider(color = LocalAccents.current.hairline)
                    SourceLine(
                        text = "${group.sourceLabel.ifBlank { "来源页" }} · ${group.sourceDateLabel}",
                        url = group.sourceUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoursTable(hours: List<ContactHours>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                Text("办公时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            hours.forEach { h ->
                Row(Modifier.padding(top = 2.dp)) {
                    Text(h.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(h.value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 一条联系方式：事项 + 标签，找谁 · 在哪，号码药丸（可拨），邮箱与补充。 */
/** 一行可点的来源页链接，分区底部与小节底部共用。 */
@Composable
private fun SourceLine(text: String, url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable { openUrl(context, url) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew, "打开来源",
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactRowView(row: ContactRow, onDial: (String) -> Unit, overline: String? = null) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (overline != null) {
            Text(overline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.what,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.h24) Tag("24H", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            if (row.unverified) Tag("待核实", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        val meta = listOf(row.who, row.where).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.tels.isNotEmpty() || row.email != null) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.tels.forEach { t -> TelPill(t) { onDial(t.dial) } }
                row.email?.let { mail ->
                    Pill(icon = Icons.Outlined.Mail, text = mail, onClick = { openUrl(context, "mailto:$mail") })
                }
            }
        }
    }
}

@Composable
private fun TelPill(tel: ContactTel, onClick: () -> Unit) {
    Pill(
        icon = Icons.Filled.Phone,
        text = if (tel.ext.isBlank()) tel.pretty else "${tel.pretty} ${tel.ext}",
        mono = true,
        onClick = onClick,
    )
}

@Composable
private fun Pill(icon: ImageVector, text: String, mono: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (mono) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Spacer(Modifier.width(6.dp))
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/** 搜索结果：内置条目带「分区 · 小节」小标，自添加的排最前。 */
private fun LazyListScope.searchResults(
    directory: CampusDirectory,
    custom: List<PhoneEntry>,
    query: String,
    onDial: (String) -> Unit,
) {
    val needle = query.replace(Regex("\\s+"), "").lowercase()
    val customHits = custom.filter {
        it.name.replace(Regex("\\s+"), "").lowercase().contains(needle) || it.number.contains(needle)
    }
    val hits = directory.search(query)
    if (customHits.isEmpty() && hits.isEmpty()) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有找到「${query.trim()}」", style = MaterialTheme.typography.titleSmall)
            }
        }
        return
    }
    item {
        Text(
            "${customHits.size + hits.size} 条结果",
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (customHits.isNotEmpty()) {
        item { CustomCard(customHits, onDial = onDial, onDelete = null) }
    }
    if (hits.isNotEmpty()) {
        item {
            BnuCard(Modifier.fillMaxWidth()) {
                Column {
                    hits.take(MAX_HITS).forEachIndexed { i, hit ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 16.dp), color = LocalAccents.current.hairline)
                        val overline = listOf(hit.group.title, hit.section.title).filter { it.isNotBlank() }.joinToString(" · ")
                        ContactRowView(hit.row, onDial, overline = overline)
                    }
                    if (hits.size > MAX_HITS) {
                        Divider(color = LocalAccents.current.hairline)
                        Text(
                            "还有 ${hits.size - MAX_HITS} 条，请输入更具体的关键词",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/** 只打开拨号盘，由用户确认后再拨出 —— 因此不需要 CALL_PHONE 权限。 */
private fun dial(context: Context, number: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
