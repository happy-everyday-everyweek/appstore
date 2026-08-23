package me.spica27.spicamusic.ui.home.player_bar

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.spicamusic.R
import me.spica27.spicamusic.ui.home.HomePage
import me.spica27.spicamusic.ui.home.HomeViewModel
import me.spica27.spicamusic.ui.home.LocalBottomBarScrollConnection
import me.spica27.spicamusic.ui.search.SearchScene
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 底部媒体控制栏（V2 · 应用市场版）
 *
 * 基于上游生产版 BottomMediaBarV2 修改：
 * - 移除音频播放控制条（playBar 槽置空）
 * - 移除收起态（单行 inline 模式与返回键收起），底栏常驻展开态
 * - 全屏槽位承载商店页面内容（推荐 / 全部 / 设置）
 * - 原「加号」按钮位改为搜索按钮
 * - 保留：BottomBarV2 卡片生长 Layout、Tab 指示器动画、SharedTransition、全面屏导航栏内边距
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun BottomMediaBarV2(bottomBarScrollConnection: BottomBarScrollConnection = LocalBottomBarScrollConnection.current) {
    val homeViewModel: HomeViewModel = koinActivityViewModel()
    val navigationPath = LocalNavigationPath.current

    val currentHomePage = homeViewModel.currentPage.collectAsStateWithLifecycle().value

    // 常驻展开态：初始即全屏展开（无收起逻辑）
    val sheetState = rememberBottomBarV2State(initialProgress = 1f)

    SharedTransitionLayout {
        AnimatedContent(targetState = false) { lineMode ->
            if (!lineMode) {
                BottomBarV2(
                    modifier = Modifier.zIndex(2f),
                    state = sheetState,
                    navigationBar = {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(vertical = 8.dp),
                        ) {
                            HomePageSwitcher(
                                modifier =
                                    Modifier
                                        .sharedElement(
                                            sharedContentState = rememberSharedContentState("navigation_bar"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                        ).weight(1f),
                            )
                            // 原「加号」位 → 搜索按钮
                            Box(
                                modifier =
                                    Modifier
                                        .sharedElement(
                                            sharedContentState = rememberSharedContentState("plus_icon"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                        ).size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary)
                                        .clickable { navigationPath.push(SearchScene()) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.nav_tab_search),
                                    tint = MaterialTheme.colorScheme.onTertiary,
                                )
                            }
                        }
                    },
                    playBar = {
                        // 播放控制条已移除：占位空槽（保持 BottomBarV2 测量契约）
                        Box(
                            modifier =
                                Modifier
                                    .sharedElement(
                                        sharedContentState = rememberSharedContentState("player_bar"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                    ).height(1.dp),
                        )
                    },
                    fullScreenPlayer = { _, _ ->
                        // 全屏槽位：商店页面内容（推荐 / 全部 / 设置）
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (currentHomePage) {
                                HomePage.Discover ->
                                    me.spica27.spicamusic.ui.discover.DiscoverScene(
                                        onOpenSearch = { navigationPath.push(SearchScene()) },
                                    )
                                HomePage.Library ->
                                    me.spica27.spicamusic.ui.library.LibraryScene(
                                        onOpenSearch = { navigationPath.push(SearchScene()) },
                                    )
                                HomePage.Settings ->
                                    me.spica27.spicamusic.ui.settings
                                        .SettingsPage()
                            }
                        }
                    },
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Tab 切换器（原版保留）：胶囊指示器 + 图标 + 文字
 */
@Composable
private fun HomePageSwitcher(modifier: Modifier = Modifier) {
    val homeViewModel: HomeViewModel = koinActivityViewModel()
    val tabs = remember { HomePage.entries.toTypedArray() }
    val selectIndex = homeViewModel.currentPage.collectAsStateWithLifecycle().value
    val tabPositions = remember { mutableStateMapOf<HomePage, Dp>() }
    val tabWidths = remember { mutableStateMapOf<HomePage, Dp>() }
    val tabHeight = remember { mutableStateMapOf<HomePage, Dp>() }
    val density = LocalDensity.current

    val indicatorSpec =
        remember {
            spring<Dp>(
                stiffness = Spring.StiffnessMedium,
                dampingRatio = Spring.DampingRatioNoBouncy,
                visibilityThreshold = Dp.VisibilityThreshold,
            )
        }
    val indicatorOffset by animateDpAsState(
        targetValue = tabPositions.getOrElse(selectIndex) { 0.dp },
        label = "indicatorOffset",
        animationSpec = indicatorSpec,
    )
    val indicatorWidth by animateDpAsState(
        targetValue = tabWidths.getOrElse(selectIndex) { 0.dp },
        label = "indicatorWidth",
        animationSpec = indicatorSpec,
    )
    val indicatorHeight by animateDpAsState(
        targetValue = tabHeight.getOrElse(selectIndex) { 0.dp },
        label = "indicatorHeight",
        animationSpec = indicatorSpec,
    )
    val indicatorColor = MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier =
            modifier
                .height(56.dp)
                .padding(end = 12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawWithCache {
                    val paddingValues = 6.dp.toPx()
                    onDrawBehind {
                        if (indicatorWidth > 0.dp && indicatorHeight > 0.dp) {
                            drawRoundRect(
                                color = indicatorColor,
                                topLeft =
                                    Offset(
                                        indicatorOffset.toPx() + paddingValues,
                                        paddingValues,
                                    ),
                                size =
                                    Size(
                                        indicatorWidth.toPx() - 2 * paddingValues,
                                        indicatorHeight.toPx() - 2 * paddingValues,
                                    ),
                                cornerRadius =
                                    CornerRadius(
                                        100f,
                                        100f,
                                    ),
                            )
                        }
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (page in tabs) {
            HomePageSwitchItem(
                modifier =
                    Modifier
                        .onGloballyPositioned {
                            tabPositions[page] = with(density) { it.positionInParent().x.toDp() }
                            tabWidths[page] = with(density) { it.size.width.toDp() }
                            tabHeight[page] = with(density) { it.size.height.toDp() }
                        }.weight(1f),
                icon = {
                    Icon(
                        page.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
                title = stringResource(page.titleRes),
                bandHomePage = page,
            )
        }
    }
}

@Composable
private fun HomePageSwitchItem(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    bandHomePage: HomePage,
) {
    val homeViewModel: HomeViewModel = koinActivityViewModel()

    val currentHomePage = homeViewModel.currentPage.collectAsStateWithLifecycle().value

    val isSelected =
        remember(currentHomePage) {
            currentHomePage == bandHomePage
        }

    Row(
        modifier =
            modifier
                .clickable {
                    if (!isSelected) {
                        homeViewModel.navigateToPage(bandHomePage)
                    }
                }.height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(
            isSelected,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            Row {
                icon()
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
fun rememberBottomBarScrollConnection(
    initialIsInline: Boolean = false,
    scrollThreshold: Dp = 50.dp,
): BottomBarScrollConnection =
    with(LocalDensity.current) {
        val scrollThresholdPx = scrollThreshold.toPx()
        remember(scrollThresholdPx, initialIsInline) {
            BottomBarScrollConnection(initialIsInline, scrollThresholdPx)
        }
    }

@Stable
class BottomBarScrollConnection(
    initialIsInline: Boolean = false,
    private val scrollThresholdPx: Float,
) : NestedScrollConnection {
    var isInline by mutableStateOf(initialIsInline)
        private set

    private var accumulatedScroll = 0f

    fun expand() {
        isInline = false
        accumulatedScroll = 0f
    }

    fun inline() {
        isInline = true
        accumulatedScroll = 0f
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val scrollDelta = available.y
        if ((accumulatedScroll > 0 && scrollDelta < 0) || (accumulatedScroll < 0 && scrollDelta > 0)) {
            accumulatedScroll = 0f
        }
        accumulatedScroll += scrollDelta
        if (accumulatedScroll <= -scrollThresholdPx && !isInline) {
            isInline = true
            accumulatedScroll = 0f
        } else if (accumulatedScroll >= scrollThresholdPx && isInline) {
            isInline = false
            accumulatedScroll = 0f
        }
        return Offset.Zero
    }
}
