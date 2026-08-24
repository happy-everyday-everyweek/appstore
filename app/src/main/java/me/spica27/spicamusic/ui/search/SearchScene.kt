package me.spica27.spicamusic.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
 * 搜索页：顶部搜索框 + 实时结果列表。
 *
 * 组件与动效对齐上游生产版（SearchHeader / SearchInputField / 三态转场 / 空态呼吸动画）：
 * - BasicTextField 定制圆角输入框（无下划线、容器 surfaceContainerHigh）
 * - 状态栏/输入法内边距（edge-to-edge 下输入框与列表不被键盘遮挡）
 * - 列表/空态/无结果三态 AnimatedContent 转场（fade + 垂直滑入）
 * - 空态与无结果提示带呼吸动画
 * 页面语义不变：按名称/包名/简介/仓库匹配聚合包数据。
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

        // 全面屏适配：全屏搜索页避开状态栏与系统导航条；实色背景避免转场露出黑边
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .imePadding()
                    .navigationBarsPadding(),
        ) {
            SearchHeader(
                keyword = keyword,
                onKeywordChange = { keyword = it },
                onBack = { path.popTop() },
                onClear = { keyword = "" },
                onSubmitted = { focusManager.clearFocus() },
                focusRequester = focusRequester,
            )

            val state =
                when {
                    keyword.isBlank() -> SearchState.Idle
                    results.isEmpty() -> SearchState.NoResult
                    else -> SearchState.Result
                }
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (
                        fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 12 }
                    ).togetherWith(fadeOut(tween(140)))
                },
                label = "search_state_transition",
                modifier = Modifier.fillMaxSize(),
            ) { s ->
                when (s) {
                    SearchState.Idle -> SearchIdleHint()
                    SearchState.NoResult -> SearchNoResultHint()
                    SearchState.Result ->
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

private enum class SearchState { Idle, Result, NoResult }

/**
 * 头部：返回钮 + 圆角搜索输入框（对齐上游 SearchHeader / SearchInputField 组件）。
 */
@Composable
private fun SearchHeader(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSubmitted: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "back",
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                    ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp).size(20.dp),
            )
            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = 44.dp, end = 48.dp)
                        .focusRequester(focusRequester),
                textStyle =
                    TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = { onSubmitted() },
                    ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (keyword.isEmpty()) {
                            Text(
                                text = stringResource(R.string.store_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.7f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (keyword.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 空态提示：呼吸动画图标 + 文案（对齐上游 SearchIdleHint 的动效）。
 */
@Composable
private fun SearchIdleHint() {
    val transition = rememberInfiniteTransition(label = "idle_bob")
    val bob by
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "idle_bob_value",
        )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = bob),
                modifier =
                    Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            scaleX = 0.9f + 0.1f * bob
                            scaleY = 0.9f + 0.1f * bob
                        },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f + 0.3f * bob),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 无结果提示：呼吸动画（对齐上游 SearchNoResultHint 的动效）。
 */
@Composable
private fun SearchNoResultHint() {
    val transition = rememberInfiniteTransition(label = "noresult_bob")
    val bob by
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "noresult_bob_value",
        )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = bob),
                modifier =
                    Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = 0.9f + 0.1f * bob
                            scaleY = 0.9f + 0.1f * bob
                        },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.search_no_result),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f + 0.3f * bob),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Box(
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * bob),
                            shape = CircleShape,
                        ).padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.search_no_result_clear),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
