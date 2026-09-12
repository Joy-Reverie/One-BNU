package io.github.joyreverie.onebnu.ui.campus

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.ListSkeleton
import io.github.joyreverie.onebnu.ui.components.ZoomableImageDialog

/**
 * 校园平面图 / 楼宇索引。
 *
 * 学校没有提供带坐标的建筑数据，凭空标点会指错地方，所以这里不画自绘地图，
 * 而是内嵌官方平面图，并用教务系统里真实的楼房清单做一个可搜索的楼宇索引，
 * 点击后交给系统地图应用按名称检索定位。
 *
 * 本应用只面向北京校区，不再出现校区选项：楼房清单直接取北京校区的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = ServiceLocator.repo

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var buildings by remember { mutableStateOf<List<Option>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showMap by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    if (showMap) {
        ZoomableImageDialog(
            res = R.drawable.campus_map,
            contentDescription = "北京师范大学校园平面图",
            onClose = { showMap = false },
        )
    }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        val forceRefresh = attempt > 0
        when (val c = repo.mainCampus(forceRefresh = forceRefresh)) {
            is Outcome.Ok -> when (val b = repo.buildings(c.data.code, forceRefresh = forceRefresh)) {
                is Outcome.Ok -> buildings = b.data
                is Outcome.Empty -> error = b.reason
                is Outcome.Error -> error = b.message
            }
            is Outcome.Empty -> error = c.reason
            is Outcome.Error -> error = c.message
        }
        loading = false
    }

    val filtered = remember(buildings, query) {
        if (query.isBlank()) buildings
        else buildings.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园平面图") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索楼宇，如「教七」「京师大厦」") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // 平面图是本地资源，不依赖网络，始终放在列表顶部；楼宇索引加载失败只影响它自己
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { MapThumbnail(onOpen = { showMap = true }) }

                when {
                    loading -> item { ListSkeleton(rows = 4, Modifier.padding(top = 4.dp)) }
                    error != null && buildings.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().height(260.dp)) {
                            ErrorBox(error!!) { attempt++ }
                        }
                    }
                    else -> {
                        items(filtered, key = { it.code }) { b ->
                            BuildingCard(b.name) { openInMaps(context, "北京师范大学${b.name}") }
                        }
                        if (filtered.isEmpty() && query.isNotBlank()) {
                            item {
                                Text(
                                    "没有匹配「$query」的楼宇",
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 平面图入口：列表顶部放一张缩略图，点开看大图。 */
@Composable
private fun MapThumbnail(onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Image(
                painter = painterResource(R.drawable.campus_map),
                contentDescription = "北京师范大学校园平面图",
                modifier = Modifier.fillMaxWidth().height(190.dp),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Map, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("校园平面图", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "点击放大",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun BuildingCard(name: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Place, null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "地图",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** geo: 查询交给系统地图应用；没有地图应用时退到高德网页版。 */
private fun openInMaps(context: Context, keyword: String) {
    val uri = Uri.parse("geo:0,0?q=${Uri.encode(keyword)}")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://uri.amap.com/search?keyword=${Uri.encode(keyword)}"),
                    ),
                )
            }
        }
}
