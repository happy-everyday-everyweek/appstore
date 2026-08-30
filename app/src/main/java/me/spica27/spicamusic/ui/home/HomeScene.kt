package me.spica27.spicamusic.ui.home

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.ui.home.player_bar.BottomBarScrollConnection
import me.spica27.spicamusic.ui.home.player_bar.BottomMediaBarV2
import me.spica27.spicamusic.ui.home.player_bar.rememberBottomBarScrollConnection
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * Only 主框架（基于上游生产版恢复后修改）：
 * 底栏为原版展开态胶囊（Tab 指示器动画 + 搜索按钮），
 * 页面内容由全屏槽位承载（推荐 / 全部 / 设置）。
 */
class HomeScene : StackScene() {
    @Composable
    override fun Content() {
        val homeViewModel: HomeViewModel = koinActivityViewModel()
        val currentPage by homeViewModel.currentPage.collectAsStateWithLifecycle()

        // 自更新提示（独立于商店收录；静默发现新版本即提示）
        val context = LocalContext.current
        val path = me.spica27.navkit.path.LocalNavigationPath.current
        val storeViewModel: me.spica27.spicamusic.ui.home.StoreViewModel = koinActivityViewModel()
        val updateAvailable by storeViewModel.updateAvailable.collectAsStateWithLifecycle()
        val lastSyncError by storeViewModel.lastSyncError.collectAsStateWithLifecycle()
        val syncing by storeViewModel.syncing.collectAsStateWithLifecycle()
        val apps by storeViewModel.apps.collectAsStateWithLifecycle()
        val cards by storeViewModel.cards.collectAsStateWithLifecycle()
        val downloadProgress by storeViewModel.downloadProgress.collectAsStateWithLifecycle()
        val syncStage by storeViewModel.syncStage.collectAsStateWithLifecycle()

        LaunchedEffect(updateAvailable) {
            updateAvailable?.let {
                Toast
                    .makeText(
                        context,
                        "发现新版本 ${it.versionName}，可在 GitHub Release 下载：${it.releaseUrl}",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }

        val bottomBarScrollConnection = rememberBottomBarScrollConnection()

        CompositionLocalProvider(
            LocalBottomBarScrollConnection provides bottomBarScrollConnection,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BottomMediaBarV2(bottomBarScrollConnection = bottomBarScrollConnection)

                // 首启前台阻塞引导：本地无任何数据且同步未成功前，全屏展示进度/错误
                val isFirstRun = apps.isEmpty() && cards.isEmpty()
                if (isFirstRun) {
                    InitSyncLayer(
                        syncing = syncing,
                        progress = downloadProgress,
                        stage = syncStage,
                        error = lastSyncError,
                        onRetry = { storeViewModel.retryBootstrap() },
                    )
                } else if (!syncing) {
                    // 非首启：同步失败横幅常驻置顶（不静默掩盖），可跳日志页进一步定位
                    lastSyncError?.let { message ->
                        SyncErrorBanner(
                            message = message,
                            modifier = Modifier.align(Alignment.TopCenter),
                            onOpenLogs = {
                                path.push(
                                    me.spica27.spicamusic.ui.settings
                                        .LogsScene(),
                                )
                            },
                            onRetry = { storeViewModel.retryBootstrap() },
                            onDismiss = { storeViewModel.consumeSyncError() },
                        )
                    }
                }
            }
        }
    }
}

/** 同步失败横幅：最近一次失败始终可见，提供重试与日志入口 */
@Composable
private fun SyncErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onOpenLogs: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "同步失败：$message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { onRetry() },
            )
            Text(
                text = "日志",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { onOpenLogs() },
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .padding(start = 4.dp)
                        .clickable { onDismiss() },
            )
        }
    }
}

/**
 * 首次使用引导层：规范书要求首启前台阻塞式下载全量包并展示进度；
 * 失败时展示详细错误并提供重试，成功后自动消失（数据就绪即不再渲染）。
 */
@Composable
private fun InitSyncLayer(
    syncing: Boolean,
    progress: Float?,
    stage: String?,
    error: String?,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Only",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            androidx.compose.foundation.layout
                .Spacer(modifier = Modifier.height(24.dp))
            if (syncing) {
                Text(
                    text = stage ?: "首次使用：正在下载应用数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                androidx.compose.foundation.layout
                    .Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout
                    .Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = progress?.let { "${(it * 100).toInt()}%" } ?: "准备中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (error != null) {
                Text(
                    text = "同步失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                androidx.compose.foundation.layout
                    .Spacer(modifier = Modifier.height(8.dp))
                // 详细错误（含 URL 与镜像尝试原因），可滚动避免超长截断
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
                androidx.compose.foundation.layout
                    .Spacer(modifier = Modifier.height(20.dp))
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text("重试")
                }
            } else {
                Text(
                    text = "正在初始化…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Immutable
enum class HomePage(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Discover(R.string.nav_tab_discover, Icons.Default.Explore),
    Library(R.string.nav_tab_library, Icons.Default.GridView),
    Settings(R.string.nav_tab_settings, Icons.Default.Settings),
}

val LocalBottomBarScrollConnection =
    compositionLocalOf<BottomBarScrollConnection> {
        error("No BottomBarScrollConnection provided. This composable must be called inside a Scene's content lambda.")
    }
