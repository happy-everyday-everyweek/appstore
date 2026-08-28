package me.spica27.spicamusic.di

import me.spica27.spicamusic.BuildConfig
import me.spica27.spicamusic.core.preferences.PreferencesManager
import me.spica27.spicamusic.store.BundleLoader
import me.spica27.spicamusic.store.Downloader
import me.spica27.spicamusic.store.GitHubUnlistedSearchSource
import me.spica27.spicamusic.store.ManifestSyncEngine
import me.spica27.spicamusic.store.SelfUpdater
import me.spica27.spicamusic.store.SelfUpdaterImpl
import me.spica27.spicamusic.store.StoreRepository
import me.spica27.spicamusic.store.SyncEngine
import me.spica27.spicamusic.store.SyncStore
import me.spica27.spicamusic.store.UnlistedSearchSource
import me.spica27.spicamusic.store.gitlink.GitLinkDownloader
import me.spica27.spicamusic.store.gitlink.MirrorScheduler
import me.spica27.spicamusic.store.gitlink.MirrorStateStore
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import me.spica27.spicamusic.ui.home.StoreViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * App 模块的依赖注入配置：商店数据层（GitHub 同步 + 下载底座）与页面 ViewModel。
 */
object AppModule {
    val appModule =
        module {
            // PreferencesManager
            single { PreferencesManager(androidContext()) }

            // 下载底座（GitLink 移植：33 镜像智能测速 / 断点续传 / 空文件换源 / SHA-256）
            single<Downloader> { GitLinkDownloader() }

            // 本地同步状态（filesDir/store/）；同时把资产根暴露给 StoreAssets（推荐页封面/正文）
            single<SyncStore> {
                SyncStore(File(androidContext().filesDir, "store")).also {
                    me.spica27.spicamusic.store.StoreAssets.rootDir = it.assetsRoot
                }
            }

            // v2 镜像调度状态（会话级测速缓存，TTL 24h）
            single { MirrorStateStore(File(androidContext().filesDir, "store")) }

            // v2 镜像调度器（会话级选择 + 下载中自适应；实现 ObjectFetcher）
            single<ObjectFetcher> { MirrorScheduler(OkHttpClient(), get()) }

            // 市场同步引擎（全 GitLink 直链模式：git hub.com 直链资产，零 API）
            single<SyncEngine> { SyncEngine(get(), get()) }

            // v2 清单驱动同步引擎（manifest.v2.json 探测 / index / icons；404 回退 v1）
            single<ManifestSyncEngine> { ManifestSyncEngine(get(), get()) }

            // 详情包懒加载（bundle 下载 + 解包 + detail 合并）
            single<BundleLoader> { BundleLoader(get(), get()) }

            // 未收录应用搜索（GitHub Search API；零 API 设计的唯一例外，按需触发）
            single<UnlistedSearchSource> { GitHubUnlistedSearchSource(OkHttpClient()) }

            // 客户端自身更新（独立于商店收录；GitLink 直链 patch.json 判定版本）
            single<SelfUpdater> {
                SelfUpdaterImpl(
                    downloader = get(),
                    appContext = androidContext(),
                    currentVersionName = BuildConfig.VERSION_NAME,
                )
            }

            // 商店数据仓库（StateFlow 数据源）
            single<StoreRepository> { StoreRepository(get(), get(), get()) }

            // 商店主页 ViewModel（数据仓库 + 同步启动）
            viewModel {
                me.spica27.spicamusic.ui.home
                    .HomeViewModel()
            }

            // 商店 ViewModel（数据 / 下载 / 自更新 / 详情包懒加载）
            viewModel { StoreViewModel(get(), get(), get(), get()) }
        }
}
