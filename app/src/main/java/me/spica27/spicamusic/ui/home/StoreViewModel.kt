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
 * 商店 ViewModel：双通道同步、APK 下载、自更新状态。
 * 页面切换见 [HomeViewModel]（底栏页面状态）。
 */
class StoreViewModel(
    private val repository: StoreRepository,
    private val updater: SelfUpdater,
    private val downloader: Downloader,
) : ViewModel() {
    val apps: StateFlow<AppIndex> = repository.apps
    val cards: StateFlow<DiscoverIndex> = repository.cards
    val updateAvailable: StateFlow<UpdateInfo?> = repository.updateAvailable
    val syncState: StateFlow<StoreRepository.SyncVersions> = repository.syncVersions
    val syncing: StateFlow<Boolean> = repository.syncing
    val lastSyncError: StateFlow<String?> = repository.lastError
    val downloadProgress: StateFlow<Float?> = repository.downloadProgress

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _lastDownload = MutableStateFlow<String?>(null)
    val lastDownload: StateFlow<String?> = _lastDownload.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.bootstrap()
            repository.checkSelfUpdate(updater)
        }
    }

    /** 应用详情页依赖：按 id 查应用；upstream 跳转用 */
    fun appById(id: String?): AppMeta? = repository.apps.value[id]

    /** APK 下载：GitLink 下载底座直连开发者 Release，校验 SHA-256 后进入系统安装界面 */
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
                val file =
                    withContext(Dispatchers.IO) {
                        downloader.download(
                            url = app.apkUrl,
                            dest = File(dir, fileName),
                            expectedSha256 = app.apkSha256.ifBlank { null },
                        )
                    }
                _lastDownload.value = "已保存：${file.absolutePath}"
                promptInstall(context, file)
            } catch (e: Exception) {
                _lastDownload.value = "下载失败：${e.message}"
            } finally {
                _downloading.value = false
            }
        }
    }

    /** 经 FileProvider 拉起系统安装界面（下载完成、校验通过后） */
    private fun promptInstall(
        context: Context,
        apk: File,
    ) {
        try {
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
            val intent =
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(intent)
        } catch (e: Exception) {
            _lastDownload.value = "安装引导失败：${e.message}"
        }
    }

    /** 手动触发后台静默同步（设置页"立即同步"），按结果提示 */
    fun refreshSilently(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.refresh() }
            // Toast 必须在主线程
            val error = repository.lastError.value
            android.widget.Toast
                .makeText(
                    context,
                    if (error != null) "同步失败：$error" else "同步完成",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
        }
    }

    /** 手动检查客户端自身更新（保留方法供调试；规格为开屏静默，无 UI 入口） */
    fun checkSelfUpdate(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.checkSelfUpdate(updater) }
            val info = repository.updateAvailable.value
            // Toast 必须在主线程
            android.widget.Toast
                .makeText(
                    context,
                    if (info != null) "发现新版本 ${info.versionName}" else "当前已是最新版本",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
        }
    }

    fun consumeDownloadMessage(onConsumed: () -> Unit) {
        _lastDownload.value = null
        onConsumed()
    }

    /** 关闭当前同步失败横幅（仅本次展示） */
    fun consumeSyncError() {
        repository.consumeError()
    }

    /** 首启同步失败后的重试（前台全量） */
    fun retryBootstrap() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.bootstrap() }
        }
    }

    /** 客户端自身更新下载（GitLink 镜像加速）→ 校验后拉起安装 */
    fun downloadUpdate(context: Context) {
        val info = updateAvailable.value ?: return
        if (_downloading.value) return
        viewModelScope.launch {
            _downloading.value = true
            try {
                val dir = File(context.getExternalFilesDir(null), "downloads")
                val file =
                    withContext(Dispatchers.IO) {
                        downloader.download(
                            url = info.downloadUrl,
                            dest = File(dir, "appstore-update.apk"),
                            expectedSha256 = null,
                        )
                    }
                _lastDownload.value = "更新包已下载：${file.absolutePath}"
                promptInstall(context, file)
            } catch (e: Exception) {
                _lastDownload.value = "更新下载失败：${e.message}"
            } finally {
                _downloading.value = false
            }
        }
    }
}
