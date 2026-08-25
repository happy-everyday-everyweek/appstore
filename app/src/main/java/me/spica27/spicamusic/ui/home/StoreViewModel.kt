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
    val syncStage: StateFlow<String?> = repository.syncStage

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** APK 下载任务实时状态（下载栏展示：进度/高采样速度折线图/状态） */
    data class DownloadTaskUi(
        val appId: String? = null, // 归属应用（集合页多行各自展示状态用）
        val fileName: String = "",
        val progress: Float = 0f,
        val speedHistory: List<Long> = emptyList(), // 瞬时速度 B/s（高采样）
        val status: String = "准备中…",
        val done: Boolean = false,
        val lastFile: String? = null, // 下载成功后的 APK 路径（供“安装/重装”按钮使用）
    )

    private val _downloadTask = MutableStateFlow<DownloadTaskUi?>(null)
    val downloadTask: StateFlow<DownloadTaskUi?> = _downloadTask.asStateFlow()

    private val _lastDownload = MutableStateFlow<String?>(null)
    val lastDownload: StateFlow<String?> = _lastDownload.asStateFlow()

    private val _lastDownloadedApk = MutableStateFlow<String?>(null)
    val lastDownloadedApk: StateFlow<String?> = _lastDownloadedApk.asStateFlow()

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

    /** APK 下载：GitLink 下载底座直连开发者 Release，校验 SHA-256 后进入系统安装界面；
     *  实时上报高采样速度（每 150ms 一个采样点，最多保留 90 点）供折线图绘制 */
    fun downloadApk(
        context: Context,
        app: AppMeta,
    ) {
        if (app.apkUrl.isBlank()) {
            _lastDownload.value = "该应用暂无可下载的 APK（待采集）"
            _downloadTask.value = DownloadTaskUi(appId = app.id, fileName = app.name, status = "暂无可下载的 APK", done = true)
            return
        }
        if (_downloading.value) return
        viewModelScope.launch {
            _downloading.value = true
            val fileName = "${app.name.ifBlank { app.packageName }}_${app.version.releaseTag}.apk"
            _downloadTask.value =
                DownloadTaskUi(appId = app.id, fileName = fileName, status = "正在测速挑选最快镜像…")
            var lastSampleMs = 0L
            var lastSampleBytes = 0L
            val history = mutableListOf<Long>()
            try {
                val dir = File(context.getExternalFilesDir(null), "downloads")
                dir.mkdirs()
                val dest = File(dir, fileName)
                val file =
                    withContext(Dispatchers.IO) {
                        downloader.download(
                            url = app.apkUrl,
                            dest = dest,
                            expectedSha256 = app.apkSha256.ifBlank { null },
                            onProgress = { progress ->
                                // 高采样：按落盘字节差 / 时间窗（150ms）计算瞬时真实速率
                                val now = System.currentTimeMillis()
                                val bytes = if (dest.exists()) dest.length() else 0L
                                if (lastSampleMs == 0L) {
                                    lastSampleMs = now
                                    lastSampleBytes = bytes
                                } else if (now - lastSampleMs >= 150L) {
                                    val dtMs = (now - lastSampleMs).coerceAtLeast(1L)
                                    val speedBps = (bytes - lastSampleBytes).coerceAtLeast(0L) * 1000L / dtMs
                                    lastSampleMs = now
                                    lastSampleBytes = bytes
                                    history.add(speedBps)
                                    if (history.size > 90) history.removeAt(0)
                                    _downloadTask.value =
                                        _downloadTask.value?.copy(
                                            progress = progress,
                                            speedHistory = history.toList(),
                                            status = "下载中",
                                        )
                                }
                            },
                        )
                    }
                _downloadTask.value =
                    _downloadTask.value?.copy(
                        progress = 1f,
                        status = "下载完成，正在打开安装器…",
                        done = true,
                        lastFile = file.absolutePath,
                    )
                _lastDownloadedApk.value = file.absolutePath
                _lastDownload.value = "已保存：${file.absolutePath}"
                promptInstall(context, file)
            } catch (e: Exception) {
                _downloadTask.value =
                    _downloadTask.value?.copy(
                        status = "下载失败：${e.message ?: e::class.simpleName}",
                        done = true,
                    )
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

    /** 重新拉起已下载 APK 的安装界面（下载完成按钮“安装”点击） */
    fun reinstallLastDownload(context: Context) {
        val path = _lastDownloadedApk.value ?: return
        val apk = File(path)
        if (!apk.exists()) {
            _lastDownload.value = "已下载的 APK 文件不存在，请重新下载"
            _lastDownloadedApk.value = null
            _downloadTask.value = null
            return
        }
        _lastDownload.value = "正在打开安装器：${apk.name}"
        promptInstall(context, apk)
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

    /** 关闭下载横条弹窗（下载任务继续在后台执行，仅在完成态/失败态关闭后不再显示） */
    fun dismissDownloadTask() {
        _downloadTask.value = null
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
