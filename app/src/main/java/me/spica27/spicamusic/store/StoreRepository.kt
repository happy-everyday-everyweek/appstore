package me.spica27.spicamusic.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.spica27.spicamusic.common.entity.appstore.AppIndex
import me.spica27.spicamusic.common.entity.appstore.AppIndexParser
import me.spica27.spicamusic.common.entity.appstore.DiscoverIndex
import me.spica27.spicamusic.common.entity.appstore.DiscoverIndexParser

/**
 * 商店数据仓库：缓存加载 + 双通道同步触发，向 UI 暴露 StateFlow。
 * 全部页/搜索页/详情页消费 apps；推荐页消费 cards。
 *
 * v2（§6.5 协议探测）：AppIndex 通道先探测 manifest.v2.json（v2 引擎），
 * 探测到 404（对方仍为 v1 形态）→ 自动回退 v1 引擎；Discover 通道体积极小，维持 v1。
 */
class StoreRepository(
    private val v1Engine: SyncEngine,
    private val v2Engine: ManifestSyncEngine,
    private val store: SyncStore,
) {
    data class SyncVersions(
        val appIndexVersion: String? = null,
        val discoverVersion: String? = null,
    )

    private val _apps = MutableStateFlow<AppIndex>(emptyMap())
    val apps: StateFlow<AppIndex> = _apps.asStateFlow()

    private val _cards = MutableStateFlow<DiscoverIndex>(emptyList())
    val cards: StateFlow<DiscoverIndex> = _cards.asStateFlow()

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    private val _syncVersions = MutableStateFlow(SyncVersions())
    val syncVersions: StateFlow<SyncVersions> = _syncVersions.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 同步下载进度（首启/兜底全量等前台场景用于展示；空闲为 null） */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    /** 同步流程阶段文案（测速/清单/下载…），首启引导与设置页展示 */
    private val _syncStage = MutableStateFlow<String?>(null)
    val syncStage: StateFlow<String?> = _syncStage.asStateFlow()

    /** 启动时：先读本地缓存（离线可用）；缓存为空（首启）走前台全量，否则后台静默增量 */
    suspend fun bootstrap() {
        reloadFromCache()
        val hasLocal = _apps.value.isNotEmpty() || _cards.value.isNotEmpty()
        _syncing.value = true
        try {
            if (hasLocal) {
                refreshInternal()
            } else {
                forceFullInternal() // 首次使用：前台阻塞式全量下载（规范书定稿）
            }
        } finally {
            _syncing.value = false
            _downloadProgress.value = null
        }
        reloadFromCache()
    }

    /** 开屏静默：双通道 Auto（调用方放后台协程） */
    suspend fun refresh() {
        _syncing.value = true
        try {
            refreshInternal()
        } finally {
            _syncing.value = false
            _downloadProgress.value = null
        }
        reloadFromCache()
    }

    private suspend fun refreshInternal() = runSync(SyncMode.Auto)

    /** AppIndex 通道：v2 引擎先探测，usedV2=false（manifest.v2 404）时回退 v1 引擎 */
    private suspend fun syncAppIndex(mode: SyncMode): SyncResult {
        val v2Result = v2Engine.sync()
        if (v2Result.usedV2) return v2Result
        DebugLog.i("Sync", "[v2] AppIndex 无 manifest.v2.json，回退 v1 引擎")
        return v1Engine.sync(SyncChannel.AppIndex, mode)
    }

    /** 首次使用/损坏兜底：前台全量 */
    suspend fun forceFull() {
        _syncing.value = true
        try {
            forceFullInternal()
        } finally {
            _syncing.value = false
            _downloadProgress.value = null
        }
        reloadFromCache()
    }

    private suspend fun forceFullInternal() = runSync(SyncMode.Full)

    /**
     * 双通道同步：AppIndex 走 v2→v1 回退，Discover 维持 v1；
     * 统一挂载进度/阶段回调并合并两通道错误（优先 AppIndex 的详细错误）。
     */
    private suspend fun runSync(mode: SyncMode) {
        v1Engine.onProgress = { _downloadProgress.value = it }
        v1Engine.onStage = { _syncStage.value = it }
        v2Engine.onProgress = { _downloadProgress.value = it }
        v2Engine.onStage = { _syncStage.value = it }
        DebugLog.i("Sync", "开始双通道同步（${SyncChannel.AppIndex.repo} / ${SyncChannel.Discover.repo}）")
        val appIndexResult = syncAppIndex(mode)
        val discoverResult = v1Engine.sync(SyncChannel.Discover, mode)
        _lastError.value = syncErrorOf(appIndexResult) ?: syncErrorOf(discoverResult)
        _syncStage.value = null
        if (_lastError.value == null) DebugLog.i("Sync", "双通道同步完成")
    }

    /** 关闭当前同步失败横幅（仅本次会话展示） */
    fun consumeError() {
        _lastError.value = null
    }

    /** 详细错误优先 errorMessage（含 URL/镜像原因），其次枚举描述 */
    private fun syncErrorOf(r: SyncResult): String? = if (r.error == null) null else (r.errorMessage ?: r.error.describe())

    /** 客户端自身更新检查（独立于双通道） */
    suspend fun checkSelfUpdate(updater: SelfUpdater) {
        _updateAvailable.value = updater.check()
    }

    fun reloadFromCache() {
        // v2 优先：index.v2.json 存在即用之（含图标 id → assets/icons/<id>.png 重写）；
        // 否则回落 v1 聚合包缓存 app-index.json（双轨期兼容）
        val v2Index = store.readIndexV2()
        if (v2Index != null) {
            runCatching { _apps.value = AppIndexParser.parseV2(v2Index) }
                .onFailure { e -> DebugLog.e("Sync", "index.v2.json 缓存解析失败: ${e.message} 内容=${v2Index.take(120)}") }
        } else {
            store.readCachedText(SyncChannel.AppIndex)?.let {
                runCatching { _apps.value = AppIndexParser.parse(it) }
                    .onFailure { e -> DebugLog.e("Sync", "AppIndex 缓存解析失败: ${e.message} 内容=${it.take(120)}") }
            }
        }
        store.readCachedText(SyncChannel.Discover)?.let {
            runCatching { _cards.value = DiscoverIndexParser.parse(it) }
                .onFailure { e -> DebugLog.e("Sync", "Discover 缓存解析失败: ${e.message} 内容=${it.take(120)}") }
        }
        _syncVersions.value =
            SyncVersions(
                appIndexVersion = store.readVersion(SyncChannel.AppIndex),
                discoverVersion = store.readVersion(SyncChannel.Discover),
            )
    }
}
