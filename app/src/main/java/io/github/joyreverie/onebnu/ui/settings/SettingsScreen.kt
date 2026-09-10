package io.github.joyreverie.onebnu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.ui.components.SectionCard
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

/**
 * 设置：绩点口径、作息时间、网络诊断、检查更新。
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
    var scale by remember { mutableStateOf(settings.gpaScale) }
    val periods = remember { settings.periodTimes }
    val checker = remember(currentVersion) { UpdateChecker(currentVersion) }

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

            item {
                SectionCard("作息时间") {
                    periods.forEachIndexed { i, t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("第 ${i + 1} 节", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                t,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (i < periods.lastIndex) Divider()
                    }
                }
            }

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
                            Text(
                                "逐项检查各系统可达性，查看或重置本机设备标识",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
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
