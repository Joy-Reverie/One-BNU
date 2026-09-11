package io.github.joyreverie.onebnu.ui.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

/**
 * 学校课程中心的入口由 OneVPN 保护，方案、手册和大纲也会随学校发布实时变动。
 * 因此这里保留一个原生导航页，再交给受限的内嵌浏览器打开官方地址；不抓取或内置副本。
 */
private const val COURSE_CENTER =
    "https://onevpn.bnu.edu.cn/https/77726476706e69737468656265737421fbf45b8469326645300d8db9d6562d/www/dd/vue/spa/jw-pyfa#"

private data class CourseCenterItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val url: String,
)

private val COURSE_CENTER_ITEMS = listOf(
    CourseCenterItem(
        title = "培养方案",
        description = "查看个人培养方案、课程模块与完成要求",
        icon = Icons.Outlined.AccountTree,
        url = "$COURSE_CENTER/pyfa",
    ),
    CourseCenterItem(
        title = "教学手册",
        description = "查阅学校发布的教学手册与培养说明",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        url = "$COURSE_CENTER/jxsc",
    ),
    CourseCenterItem(
        title = "教学大纲",
        description = "查看课程性质、课程类别及教学大纲信息",
        icon = Icons.Outlined.Description,
        url = "$COURSE_CENTER/jxdg",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CultivationPlanScreen(
    onBack: () -> Unit,
    onOpenOfficialPage: (title: String, url: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("培养方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = LocalScreenInfo.current.listPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(COURSE_CENTER_ITEMS, key = { it.title }) { item ->
                CourseCenterRow(item, onClick = { onOpenOfficialPage(item.title, item.url) })
            }
        }
    }
}

@Composable
private fun CourseCenterRow(item: CourseCenterItem, onClick: () -> Unit) {
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
                    item.icon,
                    null,
                    Modifier.padding(10.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
