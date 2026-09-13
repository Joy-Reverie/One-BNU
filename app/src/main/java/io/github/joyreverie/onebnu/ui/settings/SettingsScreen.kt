package io.github.joyreverie.onebnu.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.notify.ClassReminder
import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.core.store.ColorTheme
import io.github.joyreverie.onebnu.core.store.ThemeMode
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.ui.components.TimePickerDialog
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding
import io.github.joyreverie.onebnu.widget.TodayWidgetProvider
import java.time.LocalTime

/**
 * 设置：外观、绩点口径、作息时间、网络诊断、检查更新。
 * 「关于与支持」不在这里 —— 它是「我的」页的一级入口，与设置并列。
 *
 * [currentVersion] 默认取构建版本号；debug 包的预览入口可以传一个旧版本号来演练更新流程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDiagnostics: () -> Unit = {},
    currentVersion: String = BuildConfig.VERSION_NAME,
) {
    val settings = ServiceLocator.settings
    val appearance = ServiceLocator.appearance
    var scale by remember { mutableStateOf(settings.gpaScale) }
    val checker = remember(currentVersion) { UpdateChecker(currentVersion) }
    val themeMode by appearance.themeMode.collectAsState()
    val colorTheme by appearance.colorThemeFlow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = LocalScreenInfo.current.listPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("外观") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { appearance.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = ThemeMode.entries.size),
                                icon = { Icon(mode.icon, null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("色系", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ColorTheme.entries.forEach { theme ->
                            Column(
                                Modifier.weight(1f).clickable { appearance.setColorTheme(theme) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                androidx.compose.material3.Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = Color(theme.swatch),
                                    border = if (theme == colorTheme) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {}
                                Spacer(Modifier.height(4.dp))
                                Text(theme.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }

            item {
                SectionCard("绩点口径") {
                    GpaScale.entries.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scale = s
                                settings.gpaScale = s
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = scale == s, onClick = {
                                scale = s
                                settings.gpaScale = s
                            })
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(s.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    s.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }

            item { PeriodTimesCard() }

            item {
                SectionCard("网络") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDiagnostics)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.NetworkCheck, null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("网络诊断", style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward, null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            item { UpdateCard(currentVersion, checker) }
        }
    }
}

/**
 * 作息时间：默认是学校统一作息，点某一格改这一节的上课或下课时刻。
 *
 * 存之前先整表校验（顺序不能颠倒），不合法就提示原因、不落盘 —— 课表网格与提醒都假定各节按先后排列。
 * 改完立即重排提醒、重绘小组件；课表与首页通过 `Settings.periodTimesFlow` 自己跟着变。
 */
@Composable
private fun PeriodTimesCard() {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    val periods by settings.periodTimesFlow.collectAsState()
    // 正在改第几节的哪一端；null 表示没在改
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    // 时间芯片里是 sp 文字，系统字体放大时槽位也要跟着宽，否则「08:00」会被裁掉
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    fun applied() {
        ClassReminder.reschedule(context)
        TodayWidgetProvider.updateAll(context)
    }

    SectionCard(
        "作息时间",
        trailing = {
            if (periods != Settings.PERIOD_TIMES) {
                TextButton(onClick = {
                    settings.resetPeriodTimes()
                    applied()
                }) { Text("恢复默认") }
            }
        },
    ) {
        periods.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${i + 1} 节", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // 右侧时间组的两枚芯片同宽（各占一半），行与行之间对齐；
                // 宽度随系统字体一起长，不再用 64dp 死宽把「08:00」裁掉。
                Row(
                    Modifier.width(PERIOD_TIME_GROUP_WIDTH * fontScale).heightIn(min = PERIOD_TIME_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeChip(t.substringBefore('-'), Modifier.weight(1f)) { editing = i to true }
                    Box(
                        Modifier.width(20.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "–",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TimeChip(t.substringAfter('-'), Modifier.weight(1f)) { editing = i to false }
                }
            }
            if (i < periods.lastIndex) HorizontalDivider()
        }
    }

    editing?.let { (index, isStart) ->
        val (start, end) = PeriodMapper.parsePeriod(periods[index]) ?: (LocalTime.of(8, 0) to LocalTime.of(8, 45))
        TimePickerDialog(
            title = "第 ${index + 1} 节" + if (isStart) "上课" else "下课",
            initial = if (isStart) start else end,
            onDismiss = { editing = null },
            onConfirm = { picked ->
                val next = periods.toMutableList()
                next[index] = if (isStart) {
                    "${PeriodMapper.format(picked)}-${PeriodMapper.format(end)}"
                } else {
                    "${PeriodMapper.format(start)}-${PeriodMapper.format(picked)}"
                }
                val problem = PeriodMapper.validate(next)
                if (problem != null) {
                    Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                } else {
                    settings.setPeriodTimes(next)
                    applied()
                    editing = null
                }
            },
        )
    }
}

/** 可点的时刻，点开时间选择器。 */
@Composable
private fun TimeChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = PERIOD_TIME_ROW_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val PERIOD_TIME_GROUP_WIDTH = 152.dp
private val PERIOD_TIME_ROW_HEIGHT = 40.dp

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Outlined.LightMode
        ThemeMode.DARK -> Icons.Outlined.DarkMode
    }
