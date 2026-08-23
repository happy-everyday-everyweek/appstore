package me.spica27.spicamusic.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppRow
import me.spica27.spicamusic.ui.detail.DetailScene
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 搜索页：顶部搜索框 + 实时结果列表（按名称/包名/简介/仓库匹配聚合包数据）。
 */
class SearchScene : StackScene() {
    @Composable
    override fun Content() {
        val path = LocalNavigationPath.current
        val viewModel: StoreViewModel = koinActivityViewModel()
        val apps by viewModel.apps.collectAsStateWithLifecycle()
        var keyword by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        val results: List<AppMeta> =
            remember(keyword, apps) {
                val kw = keyword.trim().lowercase()
                if (kw.isEmpty()) {
                    emptyList()
                } else {
                    apps.values
                        .filter { app ->
                            app.name.contains(kw, ignoreCase = true) ||
                                app.packageName.contains(kw, ignoreCase = true) ||
                                app.summary.contains(kw, ignoreCase = true) ||
                                app.repo.contains(kw, ignoreCase = true)
                        }.sortedBy { it.name }
                }
            }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { path.popTop() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "back",
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    TextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.store_search_hint)) },
                        singleLine = true,
                        trailingIcon = {
                            if (keyword.isNotEmpty()) {
                                IconButton(onClick = { keyword = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                    )
                }
            }

            when {
                keyword.isBlank() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_empty_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_result),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { app ->
                            AppRow(
                                app = app,
                                onClick = { path.push(DetailScene(app)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
