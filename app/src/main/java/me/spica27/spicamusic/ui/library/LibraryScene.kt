package me.spica27.spicamusic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppGrade
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppRow
import me.spica27.spicamusic.ui.components.EmptyPlaceholder
import me.spica27.spicamusic.ui.components.StoreSearchBar
import me.spica27.spicamusic.ui.detail.DetailScene

/**
 * 全部页：应用列表。
 * 数据源由市场同步引擎（SyncEngine）提供，当前为空列表（数据层接入后填充）。
 */
@Composable
fun LibraryScene(onOpenSearch: () -> Unit) {
    val path = LocalNavigationPath.current
    // TODO(sync): 接入 SyncEngine 后替换为真实数据流
    val apps: List<AppMeta> = emptyList()
    var gradeFilter by remember { mutableStateOf<AppGrade?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题
        Text(
            text = stringResource(R.string.nav_tab_library),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        StoreSearchBar(
            onClick = onOpenSearch,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // 评级筛选
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                GradeFilterRow(
                    selected = gradeFilter,
                    onSelect = { gradeFilter = it },
                )
            }
            if (apps.isEmpty()) {
                item {
                    EmptyPlaceholder(
                        text = stringResource(R.string.empty_library),
                        modifier = Modifier.padding(vertical = 64.dp),
                    )
                }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        onClick = { path.push(DetailScene(app)) },
                    )
                }
            }
        }
    }
}
