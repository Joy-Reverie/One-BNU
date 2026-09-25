package io.github.joyreverie.onebnu.ui.settings

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.ui.home.FoldHandle
import io.github.joyreverie.onebnu.ui.home.ServiceIcon
import io.github.joyreverie.onebnu.ui.home.ServiceSlots
import io.github.joyreverie.onebnu.ui.home.effectivePinned
import io.github.joyreverie.onebnu.ui.home.pinnedServiceLimit
import io.github.joyreverie.onebnu.ui.home.serviceEntries
import io.github.joyreverie.onebnu.ui.home.withPinned
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

/**
 * 设置 → 校园服务：挑首页「校园服务」默认展示哪些入口，最多 [pinnedServiceLimit] 个，和首页收起时一样整行整行地算
 * （手机竖屏三行、平板和横屏两行）；没挑的折叠在首页宫格下方的箭头后面，点开还在。
 *
 * 上面是首页收起时的预览：列数、格子数都和这块屏幕上的首页一样，挑中的入口按首页顺序摆进去，空着的画成虚线格，
 * 还有折叠的入口就在底下画箭头。存的是挑中的入口，见 `Settings.pinnedServiceEntriesFlow`；首页通过流立即跟着变。
 * 入口按校区各一套，这里只列当前校区的。
 *
 * 开关时页面布局一点不动：格子数只跟屏幕走、不跟开关走，箭头和「恢复默认」的位置一直留着，列表也按首页顺序不重排。
 * 不然预览少一行、按钮冒出来，下面的开关跟着上下挪，连着点几个就容易点错行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceEntriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    val stored by settings.pinnedServiceEntriesFlow.collectAsState()
    val entries = serviceEntries(ServiceLocator.activeCampus)
    val keys = remember(entries) { entries.map { it.key } }
    val screen = LocalScreenInfo.current
    val limit = screen.pinnedServiceLimit
    val pinnedKeys = remember(keys, stored, limit) { effectivePinned(keys, stored, limit) }
    val pinned = remember(entries, pinnedKeys) { entries.filter { it.key in pinnedKeys } }
    val full = pinnedKeys.size >= limit
    // 同一条提示反复点只刷新时长，不排一串
    val fullToast = remember(context, limit) {
        Toast.makeText(context, "默认最多展示 $limit 个入口", Toast.LENGTH_SHORT)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = screen.listPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val folds = pinned.size < entries.size
                SectionCard("首页预览") {
                    ServiceSlots(screen.serviceColumns, pinned, slots = limit)
                    FoldHandle(expanded = false, onClick = null, modifier = Modifier.alpha(if (folds) 1f else 0f))
                }
            }

            item {
                val canReset = pinnedKeys != effectivePinned(keys, null, limit)
                val resetAlpha by animateFloatAsState(if (canReset) 1f else 0f, label = "reset")
                SectionCard(
                    "默认展示",
                    trailing = {
                        TextButton(
                            onClick = settings::resetServiceEntries,
                            enabled = canReset,
                            modifier = Modifier
                                // 按钮比标题高一截：只按零高参与排版、居中压在标题行上，触摸区照旧上下伸出去，
                                // 标题行不被撑高，和上面「首页预览」的标题留白一样
                                .layout { measurable, constraints ->
                                    val button = measurable.measure(constraints)
                                    layout(button.width, 0) { button.place(0, -button.height / 2) }
                                }
                                .alpha(resetAlpha)
                                .then(if (canReset) Modifier else Modifier.clearAndSetSemantics {}),
                        ) { Text("恢复默认") }
                    },
                ) {
                    entries.forEachIndexed { i, e ->
                        val on = e.key in pinnedKeys
                        // 整行是一个开关：点哪里都能切换，读屏也只读一次「名称 + 开关状态」。
                        // 放满后没挑的开关画成不可用，点了只提示上限。
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .toggleable(
                                    value = on,
                                    role = Role.Switch,
                                    onValueChange = { want ->
                                        val next = withPinned(keys, stored, e.key, want, limit)
                                        if (next == null) fullToast.show() else settings.setPinnedServiceEntries(next)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ServiceIcon(e, box = 36.dp, corner = 12.dp, icon = 20.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(e.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = null, enabled = on || !full)
                        }
                        if (i < entries.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
