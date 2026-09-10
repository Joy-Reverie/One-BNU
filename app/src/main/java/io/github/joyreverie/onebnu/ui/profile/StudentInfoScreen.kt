package io.github.joyreverie.onebnu.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import io.github.joyreverie.onebnu.ui.components.BnuCard
import io.github.joyreverie.onebnu.ui.components.EmptyBox
import io.github.joyreverie.onebnu.ui.components.ErrorBox
import io.github.joyreverie.onebnu.ui.components.ListSkeleton
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.listPadding

/**
 * 学籍信息。
 *
 * 数据来自 `STU_BaseInfoAction.do`，返回的是 XML（字段为拼音缩写且多数为空），
 * 解析时已按白名单翻译成中文标签，并排除身份证号等敏感字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentInfoScreen(onBack: () -> Unit, vm: ProfileViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学籍信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SessionRepository.State.Idle, SessionRepository.State.Loading ->
                    Column(Modifier.padding(16.dp)) { ListSkeleton(rows = 5) }

                is SessionRepository.State.Failed ->
                    ErrorBox(s.message) { vm.retry() }

                is SessionRepository.State.Ready -> {
                    if (s.profile.details.isEmpty()) {
                        EmptyBox("教务系统没有返回学籍字段", onRetry = { vm.retry() })
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = LocalScreenInfo.current.listPadding(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                BnuCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp)) {
                                        s.profile.details.forEachIndexed { i, item ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                                                Text(
                                                    item.label,
                                                    Modifier.width(88.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.outline,
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    item.value,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            }
                                            if (i < s.profile.details.lastIndex) Divider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
