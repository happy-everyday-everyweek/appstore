package me.spica27.spicamusic.di

import me.spica27.spicamusic.BuildConfig
import me.spica27.spicamusic.core.preferences.PreferencesManager
import me.spica27.spicamusic.store.Downloader
import me.spica27.spicamusic.store.SelfUpdater
import me.spica27.spicamusic.store.SelfUpdaterImpl
import me.spica27.spicamusic.store.StoreRepository
import me.spica27.spicamusic.store.SyncEngine
import me.spica27.spicamusic.store.SyncStore
import me.spica27.spicamusic.store.gitlink.GitLinkDownloader
import me.spica27.spicamusic.ui.home.StoreViewModel
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

            // 市场同步引擎（全 GitLink 直链模式：git hub.com 直链资产，零 API）
            single<SyncEngine> { SyncEngine(get(), get()) }

            // 客户端自身更新（独立于商店收录；GitLink 直链 patch.json 判定版本）
            single<SelfUpdater> {
                SelfUpdaterImpl(
                    downloader = get(),
                    appContext = androidContext(),
                    currentVersionName = BuildConfig.VERSION_NAME,
                )
            }

            // 商店数据仓库（StateFlow 数据源）
            single<StoreRepository> { StoreRepository(get(), get()) }

            // 商店主页 ViewModel（数据仓库 + 同步启动）
            viewModel {
                me.spica27.spicamusic.ui.home
                    .HomeViewModel()
            }

            // 商店 ViewModel（数据 / 下载 / 自更新）
            viewModel { StoreViewModel(get(), get(), get()) }
        }
}
