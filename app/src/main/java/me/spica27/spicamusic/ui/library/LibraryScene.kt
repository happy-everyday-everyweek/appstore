package me.spica27.spicamusic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppGrade
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppRow
import me.spica27.spicamusic.ui.components.EmptyPlaceholder
import me.spica27.spicamusic.ui.components.StoreSearchBar
import me.spica27.spicamusic.ui.detail.DetailScene
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 全部页：应用列表（聚合包数据流）。
 * 按评级降序（A→E）展示；评级筛选行。
 */
@Composable
fun LibraryScene(onOpenSearch: () -> Unit) {
    val path = LocalNavigationPath.current
    val viewModel: StoreViewModel = koinActivityViewModel()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    var gradeFilter by remember { mutableStateOf<AppGrade?>(null) }

    // 排序：A→E，同名按 id；过滤：评级
    val orderedApps: List<AppMeta> =
        apps.values
            .sortedWith(compareBy({ it.grade.ordinal }, { it.name }))
            .filter { gradeFilter == null || it.grade == gradeFilter }

    // 全面屏适配：顶部避开状态栏；列表底部避让常驻底栏
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            text = stringResource(R.string.nav_tab_library),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        StoreSearchBar(
            onClick = onOpenSearch,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp),
        ) {
            item {
                GradeFilterRow(
                    selected = gradeFilter,
                    onSelect = { gradeFilter = it },
                )
            }
            if (orderedApps.isEmpty()) {
                item {
                    // 首次/手动同步中或失败时，空态给出明确状态而非笼统的"暂无应用"
                    val syncing = viewModel.syncing.collectAsStateWithLifecycle().value
                    val error = viewModel.lastSyncError.collectAsStateWithLifecycle().value
                    val hint =
                        when {
                            syncing -> "正在同步应用列表…"
                            error != null -> "同步失败：$error\n下拉设置页可手动重试"
                            else -> stringResource(R.string.empty_library)
                        }
                    EmptyPlaceholder(
                        text = hint,
                        modifier = Modifier.padding(vertical = 64.dp),
                    )
                }
            } else {
                items(orderedApps, key = { it.id }) { app ->
                    AppRow(
                        app = app,
                        onClick = { path.push(DetailScene(app)) },
                    )
                }
            }
        }
    }
}
