package me.spica27.spicamusic.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.spica27.spicamusic.common.entity.appstore.AppIndex
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.common.entity.appstore.DiscoverIndex
import me.spica27.spicamusic.store.Downloader
import me.spica27.spicamusic.store.SelfUpdater
import me.spica27.spicamusic.store.StoreRepository
import me.spica27.spicamusic.store.UpdateInfo
import java.io.File

/**
 * 商店主页 ViewModel：底栏页面状态 + 双通道同步 + APK 下载。
 * 启动即触发：本地缓存加载 → 后台静默增量 → 自更新检查。
 */
class StoreViewModel(
    private val repository: StoreRepository,
    private val updater: SelfUpdater,
    private val downloader: Downloader,
) : ViewModel() {
    private val _currentPage = MutableStateFlow(StorePage.Discover)
    val currentPage: StateFlow<StorePage> = _currentPage.asStateFlow()

    val apps: StateFlow<AppIndex> = repository.apps
    val cards: StateFlow<DiscoverIndex> = repository.cards
    val updateAvailable: StateFlow<UpdateInfo?> = repository.updateAvailable

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _lastDownload = MutableStateFlow<String?>(null)
    val lastDownload: StateFlow<String?> = _lastDownload.asStateFlow()

    init {
        bootstrap()
    }

    fun navigateTo(page: StorePage) {
        _currentPage.value = page
    }

    private fun bootstrap() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.bootstrap()
            repository.checkSelfUpdate(updater)
        }
    }

    /** 应用详情页依赖：按 id 查应用；upstream 跳转用 */
    fun appById(id: String?): AppMeta? = repository.apps.value[id]

    /** APK 下载：GitLink 下载底座直连开发者 Release，校验后提示 */
    fun downloadApk(
        context: Context,
        app: AppMeta,
    ) {
        if (app.apkUrl.isBlank()) {
            _lastDownload.value = "该应用暂无可下载的 APK（待采集）"
            return
        }
        if (_downloading.value) return
        viewModelScope.launch {
            _downloading.value = true
            try {
                val dir = File(context.getExternalFilesDir(null), "downloads")
                val fileName = "${app.name.ifBlank { app.packageName }}_${app.version.releaseTag}.apk"
                withContext(Dispatchers.IO) {
                    downloader.download(
                        url = app.apkUrl,
                        dest = File(dir, fileName),
                        expectedSha256 = app.apkSha256.ifBlank { null },
                    )
                }
                _lastDownload.value = "已保存：${File(dir, fileName).absolutePath}"
            } catch (e: Exception) {
                _lastDownload.value = "下载失败：${e.message}"
            } finally {
                _downloading.value = false
            }
        }
    }

    fun consumeDownloadMessage(onConsumed: () -> Unit) {
        _lastDownload.value = null
        onConsumed()
    }
}
