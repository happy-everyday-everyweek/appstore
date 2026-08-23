package me.spica27.spicamusic.di

import me.spica27.spicamusic.core.preferences.PreferencesManager
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * App 模块的依赖注入配置
 */
object AppModule {
    val appModule =
        module {
            // PreferencesManager
            single { PreferencesManager(androidContext()) }

            // 商店主页 ViewModel（底栏页面状态）
            viewModel {
                StoreViewModel()
            }
        }
}
