package me.spica27.spicamusic.ui.home

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.ui.home.player_bar.BottomBarScrollConnection
import me.spica27.spicamusic.ui.home.player_bar.BottomMediaBarV2
import me.spica27.spicamusic.ui.home.player_bar.rememberBottomBarScrollConnection
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 应用市场主框架（基于上游生产版恢复后修改）：
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
        val storeViewModel: me.spica27.spicamusic.ui.home.StoreViewModel = koinActivityViewModel()
        val updateAvailable by storeViewModel.updateAvailable.collectAsStateWithLifecycle()
        val lastSyncError by storeViewModel.lastSyncError.collectAsStateWithLifecycle()
        val syncing by storeViewModel.syncing.collectAsStateWithLifecycle()

        // 首次/前台同步失败提示（无本地缓存时启动后出错的显式告知）
        LaunchedEffect(lastSyncError, syncing) {
            if (!syncing && lastSyncError != null && storeViewModel.apps.value.isEmpty()) {
                Toast
                    .makeText(
                        context,
                        "首次同步失败：$lastSyncError",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
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
