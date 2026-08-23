package me.spica27.spicamusic.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.ui.discover.DiscoverScene
import me.spica27.spicamusic.ui.library.LibraryScene
import me.spica27.spicamusic.ui.search.SearchScene
import me.spica27.spicamusic.ui.settings.dsh.SettingsPage
import org.koin.compose.viewmodel.koinActivityViewModel

/** 应用市场主框架：底栏四入口（推荐 / 全部 / 搜索 / 设置） */
class HomeScene : StackScene() {
    @Composable
    override fun Content() {
        val storeViewModel: StoreViewModel = koinActivityViewModel()
        val currentPage by storeViewModel.currentPage.collectAsStateWithLifecycle()
        val updateAvailable by storeViewModel.updateAvailable.collectAsStateWithLifecycle()
        val path = LocalNavigationPath.current
        val context = LocalContext.current

        // 自更新提示（独立于商店收录；静默发现新版本即提示）
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

        Box(modifier = Modifier.fillMaxSize()) {
            val pageStateHolder = rememberSaveableStateHolder()
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 86.dp),
            ) {
                pageStateHolder.SaveableStateProvider(currentPage.name) {
                    when (currentPage) {
                        StorePage.Discover ->
                            DiscoverScene(onOpenSearch = { path.push(SearchScene()) })

                        StorePage.Library ->
                            LibraryScene(onOpenSearch = { path.push(SearchScene()) })

                        StorePage.Settings -> SettingsPage()
                    }
                }
            }
            StoreBottomBar(
                currentPage = currentPage,
                onSelect = storeViewModel::navigateTo,
                onSearchClick = { path.push(SearchScene()) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
