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
 */
class StoreRepository(
    private val engine: SyncEngine,
    private val store: SyncStore,
) {
    private val _apps = MutableStateFlow<AppIndex>(emptyMap())
    val apps: StateFlow<AppIndex> = _apps.asStateFlow()

    private val _cards = MutableStateFlow<DiscoverIndex>(emptyList())
    val cards: StateFlow<DiscoverIndex> = _cards.asStateFlow()

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    /** 启动时：先读本地缓存（离线可用）；缓存为空（首启）走前台全量，否则后台静默增量 */
    suspend fun bootstrap() {
        reloadFromCache()
        val hasLocal = _apps.value.isNotEmpty() || _cards.value.isNotEmpty()
        if (hasLocal) {
            refresh()
        } else {
            forceFull() // 首次使用：前台阻塞式全量下载（规范书定稿）
        }
        reloadFromCache()
    }

    /** 开屏静默：双通道 Auto（调用方放后台协程） */
    suspend fun refresh() {
        engine.sync(SyncChannel.AppIndex, SyncMode.Auto)
        engine.sync(SyncChannel.Discover, SyncMode.Auto)
    }

    /** 首次使用/损坏兜底：前台全量 */
    suspend fun forceFull() {
        engine.sync(SyncChannel.AppIndex, SyncMode.Full)
        engine.sync(SyncChannel.Discover, SyncMode.Full)
        reloadFromCache()
    }

    /** 客户端自身更新检查（独立于双通道） */
    suspend fun checkSelfUpdate(updater: SelfUpdater) {
        _updateAvailable.value = updater.check()
    }

    fun reloadFromCache() {
        store.readCachedText(SyncChannel.AppIndex)?.let {
            runCatching { _apps.value = AppIndexParser.parse(it) }
        }
        store.readCachedText(SyncChannel.Discover)?.let {
            runCatching { _cards.value = DiscoverIndexParser.parse(it) }
        }
    }
}
